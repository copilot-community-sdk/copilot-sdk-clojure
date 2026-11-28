(ns github.copilot-sdk.native-fs-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-fs :as fs]
            [github.copilot-sdk.reverse :as reverse])
  (:import [java.util Arrays Base64]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private config {:initial-cwd "/virtual" :session-state-path "/sessions" :conventions "posix"})

(defn- provider []
  (let [files (atom {})]
    {:read-file (fn [p] (or (get @files p) (throw (ex-info "missing" {:code "ENOENT"}))))
     :write-file (fn [p text _mode] (swap! files assoc p text))
     :append-file (fn [p text _mode] (swap! files update p str text))
     :stat (fn [p] {:is-file true :is-directory false :size (count (get @files p))
                    :mtime "2026-01-01T00:00:00Z" :birthtime "2026-01-01T00:00:00Z"})
     :exists (fn [p] (contains? @files p)) :mkdir (fn [_ _ _]) :rm (fn [p _ _] (swap! files dissoc p))
     :rename (fn [src dest] (swap! files #(-> % (assoc dest (get % src)) (dissoc src))))
     :readdir (fn [_] (vec (keys @files))) :readdir-with-types (fn [_] (mapv #(hash-map :name % :type :file) (keys @files)))}))

(defn- with-owner [options f]
  (peer/with-peer {}
    (fn [conn request!]
      (let [owner (reverse/open! conn {:session-fs options})]
        (try
          (f owner #(request! (str "sessionFs." %1) (assoc %2 :sessionId "s")) conn)
          (finally (reverse/close! owner)))))))

(defn- register! [owner handle source]
  (reverse/register! owner "s" handle {:state {:create-session-fs-provider source}}))

(deftest metadata-and-registration-boundaries
  (is (= (fs/provider-params config) {"initialCwd" "/virtual" "sessionStatePath" "/sessions" "conventions" "posix"}))
  (is (= (get (fs/provider-params (assoc config :capabilities {:read-file-range true :binary false})) "capabilities")
         {"readFileRange" true "binary" false}))
  (doseq [bad [(dissoc config :initial-cwd) (assoc config :capabilities {:read-file-range nil})
               (assoc config :capabilities {:unimplemented true}) (assoc config :conventions "unix")]]
    (is (thrown? clojure.lang.ExceptionInfo (fs/provider-params bad))))
  (with-owner (assoc config :capabilities {:read-file-range true})
    (fn [owner request conn]
      (is (thrown? clojure.lang.ExceptionInfo (register! owner :handle nil)))
      (is (thrown? clojure.lang.ExceptionInfo (reverse/open! conn {:session-fs config})))
      (is (thrown? clojure.lang.ExceptionInfo (register! owner :handle (provider))))
      (is (thrown? clojure.lang.ExceptionInfo (register! owner :handle (assoc (provider) :read-file-range (fn [p] p)))))
      (is (some? (:error (request "readFile" {:path "/never-local"})))))))

(deftest framed-text-bytes-and-byte-ranges
  (let [data (.getBytes "a\u2603\uD83D\uDE80z" "UTF-8")
        calls (atom []) written (atom nil)
        p (assoc (provider)
                 :read-file-range (fn [path offset length]
                                    (swap! calls conj [path offset length])
                                    (let [start (min offset (alength data))
                                          end (min (alength data) (+ start (or length (alength data))))]
                                      {:content (Arrays/copyOfRange data (int start) (int end))
                                       :byte-length (alength data)}))
                 :read-file-bytes (fn [_] data)
                 :write-file-bytes (fn [_ bytes mode] (reset! written [(vec bytes) mode])))
        b64 #(.encodeToString (Base64/getEncoder) ^bytes %)]
    (with-owner (assoc config :capabilities {:binary true :read-file-range true})
      (fn [owner request _]
        (let [handle (Object.)
              reg (register! owner handle (fn [s] (is (identical? s handle)) p))]
          (try
            (is (= (:result (request "writeFile" {:path "v" :content "one"})) nil))
            (is (= (:result (request "appendFile" {:path "v" :content "!"})) nil))
            (is (= (:result (request "readFile" {:path "v"})) {:content "one!"}))
            (is (= (:result (request "readdirWithTypes" {:path "/"})) {:entries [{:name "v" :type "file"}]}))
            (is (= (get-in (request "stat" {:path "v"}) [:result :isFile]) true))
            (is (= (get-in (request "readFile" {:path "/missing"}) [:result :error :code]) "ENOENT"))
            (is (= (:result (request "readFileBytes" {:path "v"})) {:content (b64 data)}))
            (is (= (:result (request "writeFileBytes" {:path "v" :content (b64 data) :mode 0})) nil))
            (is (= @written [(vec data) 0]))
            (reset! written nil)
            (is (= (get-in (request "writeFileBytes" {:path "v" :content "AB=="}) [:result :code]) "UNKNOWN"))
            (is (nil? @written))
            (doseq [[offset length end] [[2 2 4] [4 nil 9] [3 0 3] [9 2 9] [10 nil 9]]]
              (is (= (:result (request "readFileRange" (cond-> {:path "v" :offset offset}
                                                         length (assoc :length length))))
                     {:content (b64 (Arrays/copyOfRange data (int (min offset 9)) (int end))) :byteLength 9})))
            (let [n (count @calls)]
              (doseq [bad [-1 0.5 true 9007199254740992]]
                (is (= (get-in (request "readFileRange" {:path "v" :offset bad}) [:result :error :code]) "UNKNOWN")))
              (is (= (count @calls) n)))
            (finally (reverse/unregister! owner reg))))))))

(deftest range-capability-is-independent-and-never-falls-back
  (let [calls (atom []) p (assoc (provider)
                                 :read-file (fn [_] (throw (AssertionError. "Range read invoked text reader")))
                                 :read-file-range (fn [_ _ _] (swap! calls conj :range)
                                                    {:content (byte-array [42]) :byte-length 7}))]
    (with-owner (assoc config :capabilities {:read-file-range true})
      (fn [owner request _]
        (register! owner nil p)
        (is (= (:result (request "readFileRange" {:path "v" :offset 0 :length 1})) {:content "Kg==" :byteLength 7}))
        (is (= (get-in (request "readFileRange" {:path "v" :offset 0 :length 2}) [:result :error :code]) "UNKNOWN"))
        (is (= (get-in (request "readFileRange" {:path "v" :offset 1 :length -1}) [:result :error :code]) "UNKNOWN"))
        (is (= (get-in (request "readFileBytes" {:path "v"}) [:result :error :code]) "UNKNOWN"))
        (is (= @calls [:range :range]))))))

(deftest application-execution-is-bounded-and-cancellable
  (with-owner config
    (fn [owner _ conn]
      (let [entered (CountDownLatch. 4) release (a/chan)
            reg (register! owner nil (assoc (provider) :read-file (fn [_] (.countDown entered) release)))
            {:keys [request-handler request-preparer]} (:connection @(:state-atom conn))
            prepared (:params (request-preparer "sessionFs.readFile" {:session-id "s" :path "v"}))
            task #(a/<!! (request-handler "sessionFs.readFile" prepared))
            requests (mapv (fn [_] (future (task))) (range 4))]
        (try
          (is (.await entered 3 TimeUnit/SECONDS))
          (let [queued (future (task))]
            (try
              (reverse/unregister! owner reg)
              (is (= (get-in (deref queued 3000 {}) [:error :code]) -32001))
              (finally (future-cancel queued))))
          (is (= (mapv #(get-in (deref % 3000 {}) [:error :code]) requests) (vec (repeat 4 -32001))))
          (finally
            (a/close! release)
            (doseq [f requests] (future-cancel f))))))))

(deftest old-requests-never-reach-a-replacement
  (with-owner config
    (fn [owner request conn]
      (let [started (promise) blocked (a/chan)
            reg (register! owner nil (assoc (provider) :read-file (fn [_] (deliver started true) blocked)))
            {:keys [request-handler request-preparer]} (get @(:state-atom conn) :connection)
            captured (:params (request-preparer "sessionFs.readFile" {:session-id "s" :path "/x"}))
            pending (future (request "readFile" {:path "x"}))]
        (try
          (is (true? (deref started 3000 false)))
          (reverse/unregister! owner reg)
          (is (some? (:error (deref pending 3000 {}))))
          (let [replacement (register! owner nil (assoc (provider) :read-file (constantly "other")))]
            (reverse/unregister! owner reg)
            (is (some? (:error (a/<!! (request-handler "sessionFs.readFile" captured)))))
            (is (= (:result (request "readFile" {:path "x"})) {:content "other"}))
            (reverse/unregister! owner replacement)
            (is (some? (:error (request "readFile" {:path "x"})))))
          (finally (a/close! blocked) (future-cancel pending)))))))

(deftest sqlite-and-write-failures-have-distinct-envelopes
  (let [raise (fn [& _] (throw (ex-info "partial" {:type :session-fs-write-failure})))
        received (atom nil)
        p (assoc (provider) :write-file raise :append-file raise
                 :sqlite {:exists (fn [] (throw (ex-info "database closed" {})))
                          :query (fn [type query params]
                                   (reset! received [type query params])
                                   {:rows [{:vendor/SQL_Name 3}] :columns ["vendor/SQL_Name"] :rows-affected 1})
                          :transaction (fn [_] (throw (ex-info "not committed" {:type :session-fs-sqlite-transaction-failure
                                                                                :error-class :busy-or-locked})))})]
    (with-owner (assoc config :capabilities {:sqlite true})
      (fn [owner request _]
        (register! owner nil p)
        (is (= (:result (request "writeFile" {:path "v" :content ""}))
               {:code "UNKNOWN" :message "partial" :writeChanged true}))
        (is (= (:result (request "appendFile" {:path "v" :content ""})) {:code "UNKNOWN" :message "partial"}))
        (is (= (get-in (request "readFileBytes" {:path "/not-local"}) [:result :error :code]) "UNKNOWN"))
        (is (= (:result (request "sqliteQuery" {:query "q" :queryType "query" :params {"$X_Mixed" nil}}))
               {:rows [{:vendor/SQL_Name 3}] :columns ["vendor/SQL_Name"] :rowsAffected 1}))
        (is (= @received [:query "q" {:$X_Mixed nil}]))
        (is (= (:result (request "sqliteTransaction" {:statements []}))
               {:results [] :error {:errorClass "busyOrLocked" :message "not committed"}}))
        (is (= (get-in (request "sqliteExists" {}) [:error :code]) -32603))))))
