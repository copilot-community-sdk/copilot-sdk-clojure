(ns github.copilot-sdk.native-client-test
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.memory-fs-fixture :as memory-fs]
            [github.copilot-sdk.native-callbacks :as callbacks]
            [github.copilot-sdk.native-client :as client]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.native-offline-fixture :as offline]
            [github.copilot-sdk.protocol :as rpc])
  (:import [java.io OutputStream PipedInputStream PipedOutputStream]
           [java.nio.charset StandardCharsets]))

(defn- fixture-host [version methods closed]
  (let [in (PipedInputStream. 65536)
        peer (PipedOutputStream. in)
        buffer (java.io.ByteArrayOutputStream.)
        answer (fn []
                 (let [wire (.toString buffer "UTF-8")
                       body (subs wire (+ 4 (.indexOf wire "\r\n\r\n")))
                       {:keys [id method params]} (json/read-str body :key-fn keyword)
                       metadata {:sessionId "sample" :startTime "2026-11-01T00:00:00Z"
                                 :modifiedTime "2026-11-02T00:00:00Z" :isRemote false
                                 :clientName "owner" :lastClientName ""}
                       result (case method
                                "connect" {:protocolVersion version}
                                "ping" {:message (str "pong: " (:message params)) :protocolVersion version}
                                "session.getLastId" {}
                                "status.get" {:version "fixture" :protocolVersion version}
                                "auth.getStatus" {:isAuthenticated false}
                                ("session.create" "session.resume") {:sessionId (:sessionId params)
                                                                     :capabilities {:ui {:elicitation true}}}
                                "session.ui.elicitation" (case (:message params)
                                                           "accept" {:action "accept"
                                                                     :content {"ticketCode" ["a"] "ticket_code" false
                                                                               "extra" {"vendor/a.b" 1 "fieldOne" false
                                                                                        "field_one" 2}}}
                                                           {:action (:message params)})
                                ("session.abort" "session.detach" "session.options.update" "session.delete") {:success true}
                                "session.getMetadata" (if (= "sample" (:sessionId params)) {:session metadata} {})
                                "session.list" {:sessions [metadata]}
                                "session.getMessages" {:events [{:id "evt" :type "assistant.message"
                                                                 :timestamp "2026-11-01T00:00:00Z"
                                                                 :data {:content "hi" :Original_Key nil
                                                                        :precise 100000000000000000001N}}]}
                                "runtime.shutdown" {})
                       bytes (.getBytes (json/write-str {:jsonrpc "2.0" :id id :result result})
                                        StandardCharsets/UTF_8)]
                   (swap! methods conj method)
                   (.write peer (.getBytes (str "Content-Length: " (alength bytes) "\r\n\r\n")
                                           StandardCharsets/US_ASCII))
                   (.write peer bytes)
                   (.flush peer)
                   (.reset buffer)))]
    {:in in :out (proxy [OutputStream] []
                   (write
                     ([b] (.write buffer b))
                     ([b off len] (.write buffer ^bytes b (int off) (int len))))
                   (flush [] (answer)))
     :configure-ui! (constantly nil) :close! (fn [] (swap! closed inc) (.close peer))}))

(deftest an-incompatible-native-host-is-closed-before-any-session-is-opened
  (let [closed (atom 0)
        methods (atom [])]
    (with-redefs [host/open! (fn [_] (fixture-host 3 methods closed))]
      (let [error (try (client/open-client! {:mode :coding-agent
                                             :plugins {:builtin-directories [(.getAbsolutePath (java.io.File. "plugin"))]}})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (is (= (ex-data error) {:type :protocol-version-mismatch :expected 4 :actual 3}))
        (is (= @closed 1))
        (is (= @methods ["connect"]))))))

(deftest detaching-a-stale-session-handle-does-not-detach-its-replacement
  (let [closed (atom 0)
        methods (atom [])]
    (with-redefs [host/open! (fn [_] (fixture-host 4 methods closed))]
      (let [c (client/open-client! {:mode :coding-agent})]
        (try
          (let [original (client/open-session! c {})
                id (client/session-id original)]
            (client/close-session! original)
            (let [current (client/resume-session! c id {})]
              (client/close-session! original)
              (is (= (:type (ex-data (:error (a/<!! (client/abort! original)))))
                     :session-closed))
              (is (nil? (client/await!! (client/abort! current))))
              (client/close-session! current)))
          (finally (client/close-client! c)))
        (client/close-client! c)
        (is (= @methods ["connect" "session.create" "session.detach" "session.resume"
                         "session.abort" "session.detach" "runtime.shutdown"]))
        (is (= @closed 1))))))

(deftest elicitation-preserves-submitted-fields
  (with-redefs [host/open! (fn [_] (fixture-host 4 (atom []) (atom 0)))]
    (with-open [client (sdk/open-client!! {:mode :coding-agent})
                session (sdk/open-session!! client {})]
      (doseq [[action result] [["accept" {:action :accept
                                          :content {"ticketCode" ["a"] "ticket_code" false
                                                    "extra" {"vendor/a.b" 1 "fieldOne" false "field_one" 2}}}]
                               ["decline" {:action :decline}]
                               ["cancel" {:action :cancel}]]]
        (is (= (sdk/await!! (sdk/elicit! session {:message action :requested-schema {:type "object" :properties {}}}))
               result))))))

(deftest metadata-and-history-preserve-their-distinct-contracts
  (let [closed (atom 0)
        methods (atom [])]
    (with-redefs [host/open! (fn [_] (fixture-host 4 methods closed))]
      (with-open [c (client/open-client! {:mode :coding-agent})
                  s (client/open-session! c {:session-id "sample"})]
        (let [metadata (client/await!! (client/session-metadata c "sample"))
              idiom (first (client/await!! (client/history s false)))
              raw (first (client/await!! (client/history s true)))]
          (is (= (client/await!! (client/status c)) {:version "fixture" :protocol-version 4}))
          (is (= (client/await!! (client/ping c "probe")) {:message "pong: probe" :protocol-version 4}))
          (is (nil? (client/await!! (client/last-session-id c))))
          (is (= (client/await!! (client/auth-status c)) {:authenticated? false}))
          (is (= metadata (first (client/await!! (client/list-sessions c {})))))
          (is (= (select-keys metadata [:client-name :last-client-name :remote?])
                 {:client-name "owner" :last-client-name "" :remote? false}))
          (is (instance? java.time.Instant (:start-time metadata)))
          (is (nil? (client/await!! (client/session-metadata c "missing"))))
          (is (= (:type idiom) :copilot/assistant.message))
          (is (= (get raw "data") {"Original_Key" nil "precise" 100000000000000000001N "content" "hi"}))
          (client/delete-session!! c "sample")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Session is closed"
                                (client/await!! (client/history s false))))))
      (is (not-any? #{"session.detach"} @methods)))))

(deftest close-retries-only-the-native-quiescence-barrier
  (let [methods (atom [])
        attempts (atom 0)
        closed (atom 0)]
    (with-redefs [host/open! (fn [_]
                               (update (fixture-host 4 methods closed) :close!
                                       (fn [release!]
                                         #(if (= 1 (swap! attempts inc))
                                            (throw (ex-info "Native connection is not quiescent" {:retryable? true}))
                                            (release!)))))]
      (let [c (client/open-client! {:mode :coding-agent})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not quiescent" (client/close-client! c)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Client is closed" (client/open-session! c {})))
        (client/close-client! c)
        (client/close-client! c)
        (is (= @closed 1))
        (is (= @attempts 2))
        (is (= @methods ["connect" "runtime.shutdown"]))))))

(defn- take!! [ch]
  (let [[value port] (a/alts!! [ch (a/timeout 3000)] :priority true)]
    (when-not (= port ch) (throw (ex-info "Timed out waiting for channel" {})))
    value))

(defn- bounded [f]
  (let [result (deref f 3000 ::timeout)]
    (when (= ::timeout result)
      (when (future? f) (future-cancel f))
      (throw (ex-info "Timed out waiting for operation" {})))
    result))

(defn- with-runtime [opts handler exercise]
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        closed (atom 0)]
    (try
      (rpc/set-request-handler!
       conn
       (fn [m p]
         (or (handler m p)
             (a/go {:result (case m
                              "connect" {:protocol-version 4}
                              ("session.create" "session.resume") {:session-id (:session-id p)}
                              "session.detach" {:success true}
                              "runtime.shutdown" {})}))))
      (with-redefs [host/open! (fn [host-opts]
                                 (#'host/host-config host-opts {})
                                 {:in <-client :out ->client :configure-ui! (constantly nil)
                                  :close! #(do (swap! closed inc) (.close ->client))})]
        (let [c (sdk/open-client!! (merge {:mode :coding-agent} opts))]
          (try (exercise c conn)
               (finally (sdk/force-close!! c)))))
      (finally (rpc/disconnect conn) (is (= @closed 1))))))

(deftest trusted-plugin-directories-are-registered-during-startup
  (let [path #(.getAbsolutePath (java.io.File. ^String %))
        paths [(path "plugin B") (path "plugin A") (path "plugin B")]
        fs {:initial-cwd "/" :session-state-path "/sessions" :conventions "posix"}]
    (doseq [[plugins expected] [[{} []] [{:plugins {}} []] [{:plugins {:builtin-directories []}} []]
                                [{:plugins {:builtin-directories paths}} [["plugins.builtin.set" {:paths paths}]]]]]
      (let [calls (atom [])]
        (with-runtime (assoc plugins :session-fs fs)
          (fn [method params]
            (swap! calls conj [method params])
            (case method
              "plugins.builtin.set" (a/go {:result nil})
              "sessionFs.setProvider" (a/go {:result {:success true}})
              nil))
          (fn [_ _] (is (= @calls (concat [["connect" {}]] expected [["sessionFs.setProvider" fs]])))))))
    (let [calls (atom [])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"plugin registration rejected"
                            (with-runtime {:plugins {:builtin-directories paths} :session-fs fs}
                              (fn [method _]
                                (swap! calls conj method)
                                (when (= method "plugins.builtin.set")
                                  (a/go {:error {:code -32000 :message "plugin registration rejected"}})))
                              (fn [_ _] (is false "Startup should fail before returning the client")))))
      (is (= @calls ["connect" "plugins.builtin.set"])))
    (with-redefs [host/open! (fn [_] (throw (AssertionError. "Validation must precede host startup")))]
      (doseq [plugins [nil {:directories paths} {:builtin-directories nil} {:builtin-directories (seq paths)}
                       {:builtin-directories ["relative"]} {:builtin-directories [nil]}]]
        (is (thrown? clojure.lang.ExceptionInfo (sdk/open-client!! {:mode :coding-agent :plugins plugins}))
            (pr-str plugins))))))

(deftest status-retains-runtime-supplied-host-diagnostics
  (let [result {:version "fixture" :protocol-version 4 :host-diagnostics {:error-reporting "pending"}}]
    (with-runtime {}
      (fn [method _] (when (= method "status.get") (a/go {:result result})))
      (fn [c _] (is (= (sdk/await!! (sdk/status c)) result))))))

(deftest blocked-session-transitions-leave-unrelated-sessions-usable
  (let [wire (a/chan 8)]
    (with-runtime {}
      (fn [method {:keys [session-id]}]
        (when (or (and (= "a" session-id) (#{"session.create" "session.detach"} method))
                  (= "session.abort" method))
          (let [reply (a/promise-chan)]
            (a/offer! wire [method session-id reply])
            reply)))
      (fn [c _]
        (let [opening (future (sdk/open-session!! c {:session-id "a"}))
              [method id opened] (take!! wire)]
          (is (= [method id] ["session.create" "a"]))
          (let [b (bounded (future (sdk/open-session!! c {:session-id "b"})))]
            (a/put! opened {:result {:session-id "a"}})
            (let [s (bounded opening)
                  close #(future (try (sdk/close!! s) (catch Exception e e)))
                  closing (close)
                  [method id detached] (take!! wire)]
              (is (= [method id] ["session.detach" "a"]))
              (is (nil? (bounded (future (sdk/close!! b)))))
              (a/put! detached {:result {:success false}})
              (is (instance? clojure.lang.ExceptionInfo (bounded closing)))
              (let [op (sdk/abort! s)
                    [method id reply] (take!! wire)]
                (is (= [method id] ["session.abort" "a"]))
                (a/put! reply {:result {:success false}})
                (is (= (:result (ex-data (:error (take!! op)))) {:success false})))
              (let [retry (close)
                    [method id detached] (take!! wire)]
                (is (= [method id] ["session.detach" "a"]))
                (a/put! detached {:result {:success true}})
                (is (nil? (bounded retry))))
              (is (= (:type (ex-data (:error (take!! (sdk/send! s "closed"))))) :session-closed)))))))))

(deftest force-close-wakes-an-unacknowledged-create
  (let [admitted (promise)
        reply (a/chan)
        calls (atom [])]
    (with-runtime {}
      (fn [method _params]
        (swap! calls conj method)
        (when (= "session.create" method) (deliver admitted true) reply))
      (fn [c _]
        (let [opening (future (try (sdk/open-session!! c {:session-id "stalled"})
                                   (catch Exception e e)))]
          (try
            (is (true? (bounded admitted)))
            (is (nil? (bounded (future (sdk/force-close!! c)))))
            (is (instance? clojure.lang.ExceptionInfo (bounded opening)))
            (is (= @calls ["connect" "session.create"]))
            (finally (a/close! reply))))))))

(deftest terminal-callback-failure-stops-new-work-only-on-its-own-handle
  (with-redefs-fn
    {#'callbacks/claim-limit 1}
    #(with-runtime {}
       (fn [m _] (when (= m "session.abort") (a/go {:result {:success true}})))
       (fn [c conn]
         (with-open [s (sdk/open-session!! c {:session-id "failed"})
                     other (sdk/open-session!! c {:session-id "healthy"})]
           (let [events (sdk/subscribe s)
                 emit (fn [id] (rpc/send-notification
                                conn "session.event"
                                {:session-id "failed" :event {:type "permission.requested"
                                                              :data {:request-id id :permission-request {:kind "read"}}}}))]
             (try
               (emit "one")
               (is (= :copilot/permission.requested (:type (take!! events))))
               (emit "two")
               (is (= :callback-claim-limit (-> (take!! events) :error ex-data :type)))
               (is (= :callback-claim-limit (-> (sdk/send! s "must not be sent") take!! :error ex-data :type)))
               (is (nil? (sdk/await!! (sdk/abort! other))))
               (finally (a/close! events)))))))))

(deftest graceful-client-shutdown-shares-in-flight-detaches
  (let [wire (a/chan 8)]
    (with-runtime {}
      (fn [method {:keys [session-id]}]
        (when (= method "session.detach")
          (let [ack (a/promise-chan)]
            (a/offer! wire [session-id ack])
            ack)))
      (fn [c _]
        (let [[first-session & _] (mapv #(sdk/open-session!! c {:session-id %}) ["first" "second" "third"])
              close-first (future (sdk/close!! first-session))
              first-frame (take!! wire)
              close-client (future (sdk/close!! c))
              frames (try [first-frame (take!! wire) (take!! wire)]
                          (finally (a/put! (second first-frame) {:result {:success true}})))]
          ;; Receipt of all three requests does not depend on any individual acknowledgment.
          (is (= (set (map first frames)) #{"first" "second" "third"}))
          (doseq [[_ ack] (rest frames)] (a/put! ack {:result {:success true}}))
          (is (nil? (bounded close-first)))
          (is (nil? (bounded close-client)))
          (is (nil? (a/poll! wire))))))))

(deftest graceful-close-joins-deletion-of-an-unopened-session
  (let [wire (a/chan 4)
        deleting (a/promise-chan)
        detached (a/promise-chan)]
    (with-runtime {}
      (fn [method _]
        (case method
          "session.delete" (do (a/offer! wire method) deleting)
          "session.detach" (do (a/offer! wire method) detached)
          "runtime.shutdown" (do (a/offer! wire method) (a/go {:result {}}))
          nil))
      (fn [c _]
        (sdk/open-session!! c {:session-id "live"})
        (let [attempt #(try (%) (catch Exception e e))
              delete (future (attempt #(sdk/delete-session!! c "saved")))]
          (is (= (take!! wire) "session.delete"))
          (let [close (future (attempt #(sdk/close!! c)))]
            (is (= (take!! wire) "session.detach"))
            (a/put! detached {:result {:success true}})
            (a/put! deleting {:error {:code -32000 :message "Deletion refused"}})
            (let [failure (bounded delete)]
              (is (= (ex-message failure) "Deletion refused"))
              (is (identical? (bounded close) failure)))
            (is (= (take!! wire) "runtime.shutdown"))))))))

(deftest exact-non-uuid-and-provider-session-ids-stay-out-of-host-prefix-lookup
  (let [id "0123456789abcdef0123456789abcdef"
        provider (memory-fs/provider (atom {"/" nil}))
        fs {:initial-cwd "/" :session-state-path "/state" :conventions "posix"}]
    (doseq [[client-opts requested opts] [[{} id {}]
                                          [{:session-fs fs} "deadbeef" {:state {:create-session-fs-provider provider}}]]]
      (let [calls (atom [])]
        (with-runtime client-opts
          (fn [m {:keys [session-id]}]
            (when-not (#{"connect" "sessionFs.setProvider" "session.detach"} m)
              (swap! calls conj [m session-id]))
            (case m
              "sessionFs.setProvider" (a/go {:result {:success true}})
              "sessions.findByPrefix" (a/go {:result {}})
              "session.delete" (a/go {:result {:success true}})
              nil))
          (fn [c _]
            (when (:session-fs client-opts)
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"provider on each session"
                                    (sdk/resume-session!! c requested {}))))
            (with-open [s (sdk/resume-session!! c requested opts)]
              (is (= (sdk/session-id s) requested))
              (if (:session-fs client-opts)
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"filesystem provider"
                                      (sdk/delete-session!! c requested)))
                (is (nil? (sdk/delete-session!! c requested)))))
            (is (= @calls (cond-> [["session.resume" requested]]
                            (not (:session-fs client-opts)) (conj ["session.delete" requested]))))))))))

(deftest interruption-does-not-reopen-a-detached-or-deleted-handle
  (doseq [method ["session.detach" "session.delete"]]
    (let [accepted (promise)
          reply (a/promise-chan)]
      (with-runtime {}
        (fn [m _] (when (= m method) (deliver accepted true) reply))
        (fn [c _]
          (let [s (sdk/open-session!! c {:session-id "original"})
                caller (promise)
                op (future
                     (deliver caller (Thread/currentThread))
                     (try
                       (if (= method "session.detach") (sdk/close!! s) (sdk/delete-session!! c "original"))
                       :unexpected
                       (catch InterruptedException _ (.isInterrupted (Thread/currentThread)))))]
            (try
              (bounded accepted)
              (.interrupt ^Thread (bounded caller))
              (a/put! reply {:result {:success true}})
              (is (true? (bounded op)))
              (is (= :session-closed (-> (sdk/abort! s) take!! :error ex-data :type)))
              (finally (a/close! reply)))))))))

(deftest unanswered-detach-or-delete-fences-the-session-id
  (doseq [[method id open?] [["session.detach" "uncertain" true] ["session.delete" "uncertain" true]
                             ["session.delete" "not-open" false]]]
    (let [accepted (promise)
          reply (a/promise-chan)
          deadline (a/chan)
          timer a/timeout]
      (with-runtime {}
        (fn [m _] (when (= m method) (deliver accepted true) reply))
        (fn [c _]
          (let [s (when open? (sdk/open-session!! c {:session-id id}))]
            (with-redefs [a/timeout (fn [^long ms] (if (= 10000 ms) deadline (timer ms)))]
              (let [op (future (try
                                 (if (= method "session.detach") (sdk/close!! s) (sdk/delete-session!! c id))
                                 (catch clojure.lang.ExceptionInfo e e)))]
                (try
                  (bounded accepted)
                  (a/close! deadline)
                  (is (= "Request timeout" (ex-message (bounded op))))
                  (when s (is (= :session-closed (-> (sdk/abort! s) take!! :error ex-data :type))))
                  (is (= :session-cleanup-unconfirmed
                         (try (sdk/resume-session!! c id {})
                              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
                  (finally (a/put! reply {:result {:success true}}) (a/close! reply)))))))))))

(deftest delayed-send-and-observer-do-not-cross-a-resumed-handle
  (let [tracing (promise)
        headers (a/promise-chan)
        gate (atom nil)
        seen (a/chan 8)
        entered (promise)
        release (promise)
        sent (atom [])]
    (with-runtime {:observability {:on-get-trace-context
                                   #(if (compare-and-set! gate :block nil)
                                      (do (deliver tracing true) headers)
                                      {})}}
      (fn [m p]
        (when (= "session.send" m)
          (swap! sent conj (:prompt p))
          (a/go {:result {:message-id (:prompt p)}})))
      (fn [c conn]
        (let [id "reused"
              emit #(rpc/send-notification conn "session.event"
                                           {:session-id id :event {:id % :type "session.info" :data {}}})
              old (sdk/open-session!! c {:session-id id :events {:on-event
                                                                 (fn [{:keys [id]}]
                                                                   (when (= id "hold")
                                                                     (deliver entered true)
                                                                     @release))}})
              old-stream (sdk/subscribe old)]
          (try
            (reset! gate :block)
            (let [delayed (sdk/send! old "old")]
              (is (true? (bounded tracing)))
              (emit "hold")
              (is (true? (bounded entered)))
              (emit "queued")
              (is (= ["hold" "queued"] (mapv :id [(take!! old-stream) (take!! old-stream)])))
              (sdk/close!! old)
              (with-open [fresh (sdk/resume-session!! c id {:events {:on-event #(a/offer! seen (:id %))}})]
                (a/put! headers {})
                (is (some? (:error (take!! delayed))))
                (emit "fresh")
                (is (= (take!! seen) "fresh"))
                (is (= (take!! (sdk/send! fresh "new")) {:ok "new"}))
                (is (= @sent ["new"]))))
            (finally
              (deliver release true)
              (a/close! headers)
              (a/close! old-stream))))))))

(deftest terminal-observer-errors-do-not-replace-a-structured-turn-result
  (let [wire (a/chan 1)]
    (with-runtime {}
      (fn [m p]
        (when (= "session.sendAndWait" m)
          (let [reply (a/promise-chan)]
            (a/offer! wire [p reply])
            reply)))
      (fn [c conn]
        (with-open [s (sdk/open-session!! c {:session-id "typed"
                                             :events {:on-event (fn [{:keys [id]}]
                                                                  (when (= id "fence")
                                                                    (throw (ex-info "Observer failed" {}))))}})]
          (let [op (sdk/send-and-wait! s {:prompt "json" :response-schema {:type "object"}})
                [params reply] (take!! wire)]
            (is (= (get-in params [:response-format :json-schema :schema]) {:type "object"}))
            (a/put! reply {:result {:message-id "sent" :aborted false :completion-event-id "fence"
                                    :response {:type "assistant.message" :data {:content "{}"}}}})
            (rpc/send-notification conn "session.event" {:session-id "typed" :event {:type "session.idle" :id "fence"}})
            (is (= (get-in (take!! op) [:ok :data :content]) "{}"))))))))

(defn -main [base-url prompt]
  (try
    (sdk/with-client [c {:mode :empty :base-directory (System/getenv "HOME")
                         :github {:use-logged-in-user? false}}]
      (let [opts {:tools {:available-tools []} :model "fixture/chat" :events {:streaming? true}
                  :inference {:providers [{:name "fixture" :provider-type :openai :base-url base-url}]
                              :models [{:id "chat" :provider "fixture" :model-id "gpt-4o"}]}
                  :github {:capi {:enabled? false}}}
            id (sdk/with-session [s c (assoc opts :name "Offline prefix regression")]
                 (sdk/await!! (sdk/send-and-wait! s {:prompt prompt :timeout-ms 30000}))
                 (sdk/session-id s))
            prefix (subs id 0 8)
            resume (fn [config]
                     (with-open [s (sdk/resume-session!! c prefix config)]
                       [(sdk/session-id s)
                        (get-in (last (filter #(= "assistant.message" (get % "type"))
                                              (sdk/await!! (sdk/history s :raw))))
                                ["data" "content"])]))
            implicit (resume opts)
            explicit (resume (assoc opts :config-directory (System/getenv "HOME")))
            metadata-id #(some-> (sdk/await!! (sdk/session-metadata c %)) :session-id)
            rejected #(try (%) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
            alias (with-open [full (sdk/resume-session!! c id opts)]
                    [(rejected #(sdk/delete-session!! c prefix)) (metadata-id id)
                     (some #(= "assistant.message" (get % "type")) (sdk/await!! (sdk/history full :raw)))])
            duplicate (with-open [exact (sdk/open-session!! c (assoc opts :session-id prefix))]
                        (let [result (try (with-open [_ (sdk/resume-session!! c prefix opts)] :unexpected)
                                          (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :session-id])))]
                          (sdk/await!! (sdk/send-and-wait! exact {:prompt prompt :timeout-ms 30000}))
                          result))
            provider (sdk/with-client [fs {:mode :empty :base-directory (System/getenv "HOME")
                                           :github {:use-logged-in-user? false}
                                           :session-fs {:initial-cwd "/" :session-state-path "/state" :conventions "posix"}}]
                       (with-open [s (sdk/open-session!! fs (assoc opts :session-id prefix :working-directory "/"
                                                                   :state {:create-session-fs-provider
                                                                           (memory-fs/provider (atom {"/" nil}))}))]
                         [(sdk/session-id s) (rejected #(sdk/delete-session!! fs prefix)) (metadata-id prefix)]))
            exact (with-open [short (sdk/resume-session!! c prefix opts)]
                    [(sdk/session-id short)
                     (sdk/with-client [other {:mode :empty :base-directory (System/getenv "HOME")
                                              :github {:use-logged-in-user? false}}]
                       (rejected #(sdk/delete-session!! other prefix)))
                     (metadata-id prefix) (metadata-id id)])
            deleted (with-open [s (sdk/resume-session!! c id opts)]
                      (sdk/delete-session!! c (sdk/session-id s))
                      [(metadata-id id) (:type (ex-data (:error (a/<!! (sdk/history s)))))
                       (sdk/with-session [new c (assoc opts :session-id id)] (sdk/session-id new))])]
        (spit "result.edn" (pr-str [id implicit explicit alias duplicate provider exact deleted]))))
    (finally (shutdown-agents))))

(deftest ^:native resume-resolves-a-uuid-prefix-to-the-persisted-session
  (let [{[original implicit explicit alias duplicate provider exact deleted] :event requests :requests}
        (offline/exercise-turn! ["-m" "github.copilot-sdk.native-client-test"]
                                (constantly [[{:role "assistant" :content offline/answer}] "stop"]))
        short (subs original 0 8)]
    (is (= (count requests) 2))
    (is (= implicit explicit [original offline/answer]))
    (is (= alias [:ambiguous-delete-id original true]))
    (is (= duplicate {:type :session-already-open :session-id short}))
    (is (= provider [short :session-fs-delete-unsupported short]))
    (is (= exact [short :ambiguous-delete-id short original]))
    (is (= deleted [nil :session-closed original]))))
