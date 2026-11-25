(ns github.copilot-sdk.native-client-test
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.memory-fs-fixture :as memory-fs]
            [github.copilot-sdk.native-callbacks :as callbacks]
            [github.copilot-sdk.native-client :as client]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.native-offline-fixture :as offline]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.reverse :as reverse]
            [github.copilot-sdk.util :as util])
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
                                "session.ui.elicitation" (if (= "dismiss" (:message params))
                                                           {:action "cancel"}
                                                           {:action (:message params)
                                                            :content {"ticketCode" ["a"] "ticket_code" false
                                                                      "extra" {"vendor/a.b" 1 "fieldOne" false
                                                                               "field_one" 2}}})
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
      (let [form #(sdk/await!! (sdk/elicit! session {:message % :requested-schema {:type "object" :properties {}}}))]
        (doseq [action ["accept" "decline" "cancel"]]
          (is (= (form action)
                 {:action (keyword action)
                  :content {"ticketCode" ["a"] "ticket_code" false
                            "extra" {"vendor/a.b" 1 "fieldOne" false "field_one" 2}}})))
        (is (= (form "dismiss") {:action :cancel}))))))

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
          (is (= (-> (client/session-metadata c s) a/<!! :error ex-data :type) :invalid-session-id))
          (is (= (:message (client/await!! (client/ping c "still-open"))) "pong: still-open"))
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

(deftest interrupted-startup-joins-its-reverse-callback-before-native-release
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        connect (promise)
        ack (a/promise-chan)
        caller (promise)
        worker (promise)
        unblock (promise)
        stopped (promise)
        released (promise)
        release-error (ex-info "Native release failed" {})]
    (try
      (rpc/set-request-handler! conn (fn [method _] (when (= "connect" method) (deliver connect true) ack)))
      (with-redefs [host/open! (fn [_] {:in <-client :out ->client :configure-ui! (constantly nil)
                                        :run-ui-action! (constantly nil)
                                        :close! #(do (deliver released [(.isInterrupted (Thread/currentThread))
                                                                        (realized? stopped)])
                                                     (throw release-error))})]
        (let [opening (future
                        (deliver caller (Thread/currentThread))
                        (try
                          (sdk/open-client!!
                           {:mode :coding-agent
                            :run-on-ui-thread
                            (fn [_] (deliver worker true)
                              (try (bounded unblock) (finally (deliver stopped true))))})
                          (catch Throwable t [t (.isInterrupted (Thread/currentThread))])))]
          (bounded connect)
          (rpc/send-request conn "uiThread.run" {:action-id 1})
          (bounded worker)
          (.interrupt ^Thread (bounded caller))
          (let [[failure interrupted?] (bounded opening)]
            (is (instance? InterruptedException failure))
            (is interrupted?)
            (is (= [release-error] (vec (.getSuppressed failure))))
            (is (= [false true] (bounded released))))))
      (finally
        (deliver unblock true)
        (a/close! ack)
        (rpc/disconnect conn)))))

(deftest with-client-unwinds-an-interrupted-body-without-interrupting-cleanup
  (let [methods (atom [])
        release-flag (promise)
        body-error (InterruptedException. "Body interrupted")]
    (with-redefs [host/open! (fn [_]
                               (update (fixture-host 4 methods (atom 0)) :close!
                                       (fn [release!]
                                         #(do (deliver release-flag (.isInterrupted (Thread/currentThread)))
                                              (release!)))))]
      (let [[error interrupted?]
            (bounded (future
                       (try
                         (sdk/with-client [c {:mode :coding-agent}]
                           (sdk/open-session!! c {:session-id "live"})
                           (.interrupt (Thread/currentThread))
                           (throw body-error))
                         (catch Throwable t [t (.isInterrupted (Thread/currentThread))]))))]
        (is (identical? body-error error))
        (is interrupted?)
        (is (empty? (.getSuppressed body-error)))
        (is (false? (bounded release-flag)))
        (is (= ["connect" "session.create" "session.detach" "runtime.shutdown"] @methods))))))

(deftest with-session-preserves-an-interrupted-bodys-outcome
  (let [methods (atom [])
        body-error (InterruptedException. "Body interrupted")
        partial-result {:partial "received"}]
    (with-redefs [host/open! (fn [_] (fixture-host 4 methods (atom 0)))]
      (with-open [c (sdk/open-client!! {:mode :coding-agent})]
        (doseq [throw? [true false]]
          (let [[outcome interrupted?]
                (bounded
                 (future
                   [(try
                      (sdk/with-session [_ c {:session-id "again"}]
                        (.interrupt (Thread/currentThread))
                        (if throw? (throw body-error) partial-result))
                      (catch Throwable t t))
                    (.isInterrupted (Thread/currentThread))]))]
            (is (identical? (if throw? body-error partial-result) outcome))
            (is interrupted?)))
        (is (empty? (.getSuppressed body-error)))
        (is (= ["connect" "session.create" "session.detach" "session.create" "session.detach"] @methods))))))

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

(deftest queued-form-uses-the-current-host-capability
  (let [headers (atom {}) gate (a/promise-chan) forms (atom [])]
    (with-runtime {:observability {:on-get-trace-context #(deref headers)}}
      (fn [method params]
        (case method
          "session.create" (a/go {:result {:session-id "forms" :capabilities {:ui {:elicitation true}}}})
          "session.send" (a/go {:result {:message-id "sent"}})
          "session.ui.elicitation" (do (swap! forms conj (:message params)) (a/go {:result {:action "decline"}}))
          nil))
      (fn [c peer]
        (let [s (sdk/open-session!! c {:session-id "forms"})
              events (sdk/subscribe s)
              ask #(sdk/elicit! s {:message % :requested-schema {:type "object"}})
              capability! (fn [enabled?]
                            (rpc/send-notification peer "session.event"
                                                   {:session-id "forms" :event {:type "capabilities.changed"
                                                                                :data {:ui {:elicitation enabled?}}}})
                            (is (= (:type (take!! events)) :copilot/capabilities.changed))
                            (is (= (get-in (sdk/session-info s) [:capabilities :ui :elicitation]) enabled?)))]
          (try
            (reset! headers gate)
            (let [head (sdk/send! s "head") queued (ask "queued")]
              (capability! false)
              (a/close! gate)
              (is (= (take!! head) {:ok "sent"}))
              (is (= (ex-message (:error (take!! queued))) "Host does not support elicitation"))
              (is (empty? @forms)))
            (capability! true)
            (is (= (take!! (ask "restored")) {:ok {:action :decline}}))
            (is (= @forms ["restored"]))
            (finally (a/close! gate) (a/close! events))))))))

(deftest interrupting-await-withdraws-the-take-not-the-operation
  (let [response (a/promise-chan)
        parked (promise)
        outcome (promise)]
    (with-runtime {}
      (fn [method _] (when (= "ping" method) response))
      (fn [c _]
        (let [result (sdk/ping c)
              observe-take (reify ap/ReadPort
                             (take! [_ handler]
                               (let [v (ap/take! result handler)]
                                 (deliver parked true)
                                 v)))
              waiter (Thread. #(try (deliver outcome (sdk/await!! observe-take))
                                    (catch Exception e (deliver outcome e))))]
          (try
            (.start waiter)
            (bounded parked)
            (.interrupt waiter)
            (is (instance? InterruptedException (bounded outcome)))
            (a/put! response {:result {:message "original reply"}})
            (is (= (bounded (future (sdk/await!! result))) {:message "original reply"}))
            (finally (a/close! response) (.interrupt waiter) (.join waiter 3000))))))))

(deftest interrupted-manual-response-keeps-its-acknowledgment
  (let [requests (a/chan 5)]
    (with-runtime {}
      (fn [method params]
        (when (= method "session.tools.handlePendingToolCall")
          (let [ack (a/promise-chan)]
            (a/offer! requests [(:request-id params) ack])
            ack)))
      (fn [c _]
        (with-open [s (sdk/open-session!! c {:session-id "manual"})]
          (doseq [[id wire expected] [["accepted" {:result {:success true}} {:success true}]
                                      ["rejected" {:error {:code -32602 :message "Invalid result"}} -32602]]]
            (let [finished (promise)
                  worker (Thread. (fn []
                                    (let [outcome (try (sdk/respond!! s :tool {:request-id id :result "answer"})
                                                       (catch clojure.lang.ExceptionInfo e (get-in (ex-data e) [:error :code]))
                                                       (catch InterruptedException _ :interrupted))]
                                      (deliver finished [outcome (.isInterrupted (Thread/currentThread))]))))]
              (.start worker)
              (let [[sent ack] (take!! requests)]
                (try
                  (is (= sent id))
                  (.interrupt worker)
                  (a/put! ack wire)
                  (is (= (bounded finished) [expected true]))
                  (finally (a/close! ack) (.interrupt worker) (.join worker 3000))))))
          (let [correction (future (sdk/respond!! s :tool {:request-id "rejected" :result "corrected"}))
                [id ack] (take!! requests)]
            (is (= id "rejected"))
            (a/put! ack {:result {:success true}})
            (is (= (bounded correction) {:success true}))))))))

(deftest eof-retires-the-handle-and-drains-accepted-observations
  (let [entered (promise)
        release (promise)
        seen (a/chan 2)
        decisions (atom 0)
        provider (memory-fs/provider (atom {"/" nil}))]
    (with-runtime {:session-fs {:initial-cwd "/" :session-state-path "/state" :conventions "posix"}}
      (fn [m _] (when (= "sessionFs.setProvider" m) (a/go {:result {:success true}})))
      (fn [c peer]
        (let [s (sdk/open-session!! c {:session-id "s" :state {:create-session-fs-provider provider}
                                       :github {:token-provider (constantly {:kind :cancelled})}
                                       :permissions {:on-request (fn [_ _] (swap! decisions inc) {:kind :reject})}
                                       :events {:on-event (fn [{:keys [id]}]
                                                            (when (= id "first") (deliver entered true) @release)
                                                            (a/offer! seen id))}})
              stream (sdk/subscribe s)
              owner (.-callbacks c)
              ^OutputStream out (:output-stream peer)
              emit #(peer/write-framed! out {:jsonrpc "2.0" :method "session.event"
                                             :params {:sessionId "s" :event %}})]
          (try
            (emit {:id "first" :type "session.info" :data {}})
            (bounded entered)
            ;; The reader accepts the request and reaches EOF before the
            ;; router can submit either its decision or its observation.
            (locking (:sessions owner)
              (emit {:id "last" :type "permission.requested"
                     :data {:requestId "late" :permissionRequest {:kind "read"}}})
              (.close out)
              (let [^Thread reader (:read-thread (.-connection c))]
                (.join reader 3000)
                (is (not (.isAlive reader)))))
            (is (= (mapv :id [(take!! stream) (take!! stream)]) ["first" "last"]))
            (is (= (-> (take!! stream) :error ex-data :type) :connection-closed))
            (deliver release true)
            (is (= [(take!! seen) (take!! seen)] ["first" "last"]))
            (is (nil? (sdk/close!! s)))
            (is (= (:status @(.-lifecycle s)) :closed))
            (is (not (contains? @(.-lifecycle s) :reverse-token)))
            (is (empty? @(.-sessions c)))
            (is (every? empty? (vals (select-keys @(:registry (.-reverseOwner c)) [:sessions :cleanup :credentials]))))
            (is (zero? @decisions))
            (is (= (try (sdk/close!! c) (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
                   :connection-closed))
            (is (nil? (sdk/close!! c)))
            (finally (deliver release true))))))))

(deftest explicit-session-close-cancels-observation-on-either-side-of-eof-retirement
  (doseq [draining? [false true]]
    (let [entered (promise)
          release (promise)
          stopped (promise)]
      (with-runtime {}
        (constantly nil)
        (fn [c peer]
          (let [s (sdk/open-session!! c {:session-id "s"
                                         :events {:on-event (fn [_]
                                                              (deliver entered true)
                                                              (deliver stopped (try @release
                                                                                    (catch InterruptedException _ :interrupted))))}})
                stream (sdk/subscribe s)
                ^OutputStream out (:output-stream peer)
                ^Thread reader (:read-thread (.-connection c))]
            (try
              (peer/write-framed! out {:jsonrpc "2.0" :method "session.event"
                                       :params {:sessionId "s" :event {:id "first" :type "session.info" :data {}}}})
              (bounded entered)
              (is (= (:id (take!! stream)) "first"))
              (locking c
                (.close out)
                (.join reader 3000)
                (is (not (.isAlive reader)))
                ;; The router also takes the client lock to retire the handle.
                (when-not draining? (is (nil? (sdk/close!! s)))))
              (when draining?
                (is (= (-> (take!! stream) :error ex-data :type) :connection-closed))
                (is (nil? (sdk/close!! s))))
              (is (not (contains? @(.-sessions c) "s")))
              (is (= (try (sdk/subscribe s) (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
                     :session-closed))
              (deliver release :not-interrupted)
              (is (= (bounded stopped) :interrupted))
              (finally (deliver release :not-interrupted)))))))))

(deftest closing-client-does-not-admit-model-discovery
  (doseq [custom? [false true]]
    (let [detaching (promise)
          ack (a/promise-chan)
          calls (atom [])
          opts (when custom? {:models {:on-list #(do (swap! calls conj :callback) [])}})]
      (with-runtime opts
        (fn [method _]
          (case method
            "session.detach" (do (deliver detaching true) ack)
            "models.list" (do (swap! calls conj :rpc) (a/go {:result {:models []}}))
            nil))
        (fn [c _]
          (sdk/open-session!! c {:session-id "model-owner"})
          (let [closed (future (sdk/close!! c))]
            (try
              (bounded detaching)
              (is (= (-> (sdk/list-models c) take!! :error ex-data :type) :client-closed))
              (is (empty? @calls))
              (finally (a/put! ack {:result {:success true}}) (bounded closed)))))))))

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
         (let [asked (atom [])
               handler (fn [_ {:keys [session-id]}]
                         (swap! asked conj session-id)
                         {:answer session-id :was-freeform? true})
               opts {:interaction {:on-user-input-request handler}
                     :hooks {:handlers {:on-session-end (fn [_ _] {:session-summary "cleaned up"})}}}]
           (with-open [s (sdk/open-session!! c (assoc opts :session-id "failed"))
                       other (sdk/open-session!! c (assoc opts :session-id "healthy"))]
             (let [events (sdk/subscribe s)
                   ask (fn [id] (take!! (rpc/send-request conn "userInput.request" {:session-id id :question "Color?"})))
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
                 (is (= (get-in (ask "failed") [:error :code]) -32001))
                 (is (= (get-in (ask "healthy") [:result :answer]) "healthy"))
                 (is (= @asked ["healthy"]))
                 (is (= (-> (rpc/send-request conn "hooks.invoke" {:session-id "failed" :hook-type "sessionEnd" :input {}})
                            take!! (get-in [:result :output :session-summary])) "cleaned up"))
                 (is (nil? (sdk/await!! (sdk/abort! other))))
                 (finally (a/close! events))))))))))

(deftest manual-callback-failure-ends-forms-turns-and-admission-without-another-event
  (let [received (atom [])
        entered (a/chan 2)
        held (a/promise-chan)
        preparing (promise) resume (promise)
        convert util/clj->wire
        form {:message "Pending form" :requested-schema {:type "object" :properties {}}}]
    (with-runtime {}
      (fn [method params]
        (swap! received conj method)
        (case method
          "session.create" (a/go {:result {:session-id (:session-id params) :capabilities {:ui {:elicitation true}}}})
          ("session.ui.elicitation" "session.sendAndWait") (do (a/offer! entered method) held)
          "session.tools.handlePendingToolCall" (a/go {:result {:success true}})
          "session.send" (a/go {:result {:message-id "sent"}})
          nil))
      (fn [c _]
        (with-open [s (sdk/open-session!! c {:session-id "s"})]
          (let [events (sdk/subscribe s)
                turn (sdk/send-and-wait! s "pending turn")
                answer (sdk/elicit! s form)]
            (try
              (is (= (set (repeatedly 2 #(take!! entered))) #{"session.sendAndWait" "session.ui.elicitation"}))
              (with-redefs-fn
                {#'callbacks/claim-limit 1
                 #'util/clj->wire (fn [v]
                                    (when (= "preparing" (get v "prompt"))
                                      (deliver preparing true) (bounded resume))
                                    (convert v))}
                (fn []
                  (is (true? (:success (sdk/respond!! s :tool {:request-id "first" :result "ok"}))))
                  (let [sending (future (sdk/send! s "preparing"))
                        _ (bounded preparing)
                        draining (promise) drained (promise) drain! reverse/drain!
                        cause (with-redefs [reverse/drain! (fn [owner reg]
                                                             (deliver draining true) (bounded drained)
                                                             (drain! owner reg))]
                                (let [f (future (try (sdk/respond!! s :tool {:request-id "second" :result "never"})
                                                     nil (catch clojure.lang.ExceptionInfo e e)))]
                                  (try
                                    (bounded draining)
                                    ;; Until ordinary reverse work is revoked, this
                                    ;; handle must not yet advertise terminal failure.
                                    (a/close! (sdk/subscribe s))
                                    (finally (deliver drained true)))
                                  (bounded f)))]
                    (deliver resume true)
                    (is (= (:type (ex-data cause)) :callback-claim-limit))
                    (doseq [out [(bounded sending) (sdk/send! s "after failure") (sdk/elicit! s form)
                                 events turn answer]]
                      (is (identical? (:error (take!! out)) cause)))
                    (is (not-any? #{"session.send"} @received))
                    (is (= (count (filter #{"session.ui.elicitation"} @received)) 1)))))
              (finally (deliver resume true) (a/close! held) (a/close! events)))))))))

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
          "session.list" (a/go {:result {:sessions [{:session-id "saved"}]}})
          "session.delete" (do (a/offer! wire method) deleting)
          "session.detach" (do (a/offer! wire method) detached)
          "runtime.shutdown" (do (a/offer! wire method) (a/go {:error {:code -32000 :message "Shutdown refused"}}))
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
            (let [failure (bounded delete)
                  shutdown-failure (bounded close)]
              (is (= (ex-message failure) "Deletion refused"))
              (is (= (ex-message shutdown-failure) "Deletion refused"))
              (is (empty? (.getSuppressed failure)))
              (is (= (mapv ex-message (.getSuppressed shutdown-failure)) ["Shutdown refused"])))
            (is (= (take!! wire) "runtime.shutdown"))))))))

(deftest graceful-close-finishes-a-delete-already-checking-the-store
  (doseq [listed? [true false]]
    (let [lookup (promise) inventory (a/promise-chan) calls (a/chan 6)]
      (with-runtime {}
        (fn [method {:keys [session-id]}]
          (case method
            "session.list" (do (deliver lookup true) inventory)
            ("session.detach" "session.delete" "runtime.shutdown")
            (do (a/offer! calls [method session-id]) (a/go {:result {:success true}}))
            nil))
        (fn [c _]
          (sdk/open-session!! c {:session-id "other"})
          (sdk/open-session!! c {:session-id "saved"})
          (let [delete (future (try (sdk/delete-session!! c "saved")
                                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
            (try
              (bounded lookup)
              (let [closing (future (sdk/close!! c))]
                (is (= (take!! calls) ["session.detach" "other"]))
                (a/put! inventory {:result {:sessions (if listed? [{:session-id "saved"}] [])}})
                (is (= (bounded delete) (when-not listed? :session-delete-unconfirmed)))
                (is (nil? (bounded closing)))
                (is (= [(a/poll! calls) (a/poll! calls)]
                       [[(if listed? "session.delete" "session.detach") "saved"] ["runtime.shutdown" nil]])))
              (finally (a/close! inventory)))))))))

(deftest shutdown-detaches-after-refused-deletes-share-one-interruptible-wait
  (doseq [stop [:deadline :interrupt]]
    (let [queries (a/chan 2) calls (a/chan 8)
          inventories (atom []) detach-ack (a/promise-chan)
          deadline (a/chan) clock a/timeout armed (atom true) closer (promise)]
      (with-runtime {}
        (fn [m {:keys [session-id]}]
          (case m
            "session.list" (let [reply (a/promise-chan)]
                             (swap! inventories conj reply)
                             (a/offer! queries reply) reply)
            "session.detach" (do (a/offer! calls [m session-id])
                                 (if (= session-id "ordinary") (a/go {:result {:success true}}) detach-ack))
            "runtime.shutdown" (do (a/offer! calls [m]) (a/go {:result {}}))
            nil))
        (fn [c _]
          (doseq [id ["first" "second" "ordinary"]] (sdk/open-session!! c {:session-id id}))
          (let [start-delete (fn [id]
                               (let [task (future (try (sdk/delete-session!! c id)
                                                       (catch Exception e (:type (ex-data e)))))]
                                 [(take!! queries) task]))
                [a first-delete] (start-delete "first")
                [b second-delete] (start-delete "second")]
            (try
              (with-redefs [a/timeout (fn [^long ms]
                                        (if (and (= ms 10000) (compare-and-set! armed true false))
                                          deadline (clock ms)))]
                (let [closing (future (deliver closer (Thread/currentThread))
                                      (let [error (try (sdk/close!! c) nil (catch Exception e e))]
                                        [error (.isInterrupted (Thread/currentThread))]))]
                  (is (= (take!! calls) ["session.detach" "ordinary"]))
                  ;; The second query may finish before the first. Neither
                  ;; detach may wait on the other query or the other's ACK.
                  (a/put! b {:result {:sessions []}})
                  (is (= (take!! calls) ["session.detach" "second"]))
                  (a/put! a {:result {:sessions []}})
                  (is (= (take!! calls) ["session.detach" "first"]))
                  (is (= (mapv bounded [first-delete second-delete]) (repeat 2 :session-delete-unconfirmed)))
                  (if (= stop :deadline) (a/close! deadline) (.interrupt ^Thread (bounded closer)))
                  (is (= (take!! calls) ["runtime.shutdown"]))
                  (let [[failure interrupted?] (bounded closing)]
                    (if (= stop :deadline)
                      (is (= (ex-message failure) "Session detach deadline expired"))
                      (is (instance? InterruptedException failure)))
                    (is (= interrupted? (= stop :interrupt))))))
              (finally
                (doseq [query @inventories] (a/put! query {:result {:sessions []}}))
                (a/put! detach-ack {:result {:success true}})
                (a/close! deadline)))))))))

(deftest session-ownership-requires-the-original-case
  (let [id "facade00-1234-4321-abcd-0123456789ab"
        other-case (str/upper-case id)
        named "Build-Owner"
        calls (atom [])
        refusal (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))]
    (with-runtime {}
      (fn [method {:keys [session-id]}]
        (when session-id (swap! calls conj [method session-id]))
        (case method
          "session.list" (a/go {:result {:sessions [{:session-id id} {:session-id named}]}})
          "session.delete" (a/go {:result {:success true}})
          nil))
      (fn [c _]
        (with-open [original (sdk/open-session!! c {:session-id id})
                    custom (sdk/open-session!! c {:session-id named})]
          (doseq [[alternate owner] [[other-case original] ["build-owner" custom]]]
            (is (= (refusal #(sdk/delete-session!! c alternate)) :session-id-case-conflict))
            (is (= (refusal #(sdk/open-session!! c {:session-id alternate})) :session-id-case-conflict))
            (is (= (refusal #(sdk/resume-session!! c alternate {})) :session-id-case-conflict))
            (is (nil? (sdk/delete-session!! c (sdk/session-id owner))))
            (is (= (:type (ex-data (:error (take!! (sdk/abort! owner))))) :session-closed))))
        (is (= @calls [["session.create" id] ["session.create" named]
                       ["session.delete" id] ["session.delete" named]]))))))

(deftest delete-requires-an-exact-default-store-listing
  (let [records (atom ["Weekly-Notes"])
        calls (atom [])]
    (with-runtime {}
      (fn [method {:keys [session-id]}]
        (case method
          "session.list" (do (swap! calls conj [:list])
                             (a/go {:result {:sessions (mapv #(hash-map :session-id %) @records)}}))
          "session.delete" (do (swap! calls conj [:delete session-id]) (a/go {:result {:success true}}))
          nil))
      (fn [c _]
        (doseq [[actual requested expected]
                [[["Weekly-Notes"] "weekly-notes" :session-id-case-conflict]
                 [[] "Weekly-Notes" :session-delete-unconfirmed]
                 [["Weekly-Notes" "weekly-notes"] "Weekly-Notes" :session-id-case-conflict]
                 [["Weekly-Notes"] "Weekly-Notes" :ok]]]
          (reset! records actual)
          (is (= (try (sdk/delete-session!! c requested) :ok
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))) expected)))
        (reset! records ["Weekly-Notes"])
        (with-open [alias (sdk/open-session!! c {:session-id "weekly-notes"})]
          (is (= (try (sdk/delete-session!! c (sdk/session-id alias)) nil
                      (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))) :session-id-case-conflict)))
        (is (= @calls [[:list] [:list] [:list] [:list] [:delete "Weekly-Notes"] [:list]]))))))

(deftest id-only-delete-cannot-select-another-handles-storage-root
  (let [deletions (atom [])]
    (with-runtime {:base-directory "/default-fixture-store"}
      (fn [method {:keys [session-id]}]
        (case method
          "session.list" (a/go {:result {:sessions [{:session-id "default"}]}})
          "session.delete" (do (swap! deletions conj session-id) (a/go {:result {:success true}}))
          "session.log" (a/go {:result {}})
          nil))
      (fn [c _]
        (doseq [open [#(sdk/open-session!! c {:session-id "weekly" :config-directory "/other-fixture-store"})
                      #(sdk/resume-session!! c "weekly" {:config-directory "/other-fixture-store"})]]
          (with-open [s (open)]
            (is (= (try (sdk/delete-session!! c "weekly") nil
                        (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
                   :session-delete-store-mismatch))
            (is (nil? (:error (take!! (sdk/log! s "still open" {})))))))
        (with-open [s (sdk/open-session!! c {:session-id "default" :config-directory "/default-fixture-store"})]
          (is (nil? (sdk/delete-session!! c (sdk/session-id s)))))
        (is (= @deletions ["default"]))))))

(deftest interrupted-delete-inventory-never-submits-or-fences-a-deletion
  (let [listing (promise) release (a/promise-chan) deletions (atom 0)]
    (with-runtime {}
      (fn [method _]
        (case method
          "session.list" (do (deliver listing true) release)
          "session.delete" (do (swap! deletions inc) (a/go {:result {:success true}}))
          nil))
      (fn [c _]
        (let [caller (promise)
              deleting (future (deliver caller (Thread/currentThread))
                               (try (sdk/delete-session!! c "saved") :unexpected
                                    (catch InterruptedException _ :interrupted)))]
          (try
            (bounded listing)
            (.interrupt ^Thread (bounded caller))
            (is (= (bounded deleting) :interrupted))
            (a/put! release {:result {:sessions [{:session-id "saved"}]}})
            (with-open [s (sdk/open-session!! c {:session-id "saved"})]
              (is (= (sdk/session-id s) "saved")))
            (is (zero? @deletions))
            (finally (a/close! release))))))))

(deftest runtime-release-refusals-keep-their-reason
  (let [reason "Storage is read-only"
        calls (atom [])]
    (with-runtime {}
      (fn [method _]
        (case method
          "session.list" (a/go {:result {:sessions [{:session-id "s"}]}})
          ("session.detach" "session.delete") (do (swap! calls conj method) (a/go {:result {:success false :error reason}}))
          nil))
      (fn [c _]
        (let [s (sdk/open-session!! c {:session-id "s"})]
          (doseq [release [#(sdk/close!! s) #(sdk/delete-session!! c "s") #(sdk/close!! c)]]
            (is (= (try (release) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))
                   {:session-id "s" :reason reason})))
          (is (= @calls ["session.detach" "session.delete" "session.detach"])))))))

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
              "session.list" (a/go {:result {:sessions [{:session-id id}]}})
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
                            (not (:session-fs client-opts)) (conj ["session.list" nil] ["session.delete" requested]))))))))))

(deftest interruption-does-not-reopen-a-detached-or-deleted-handle
  (doseq [method ["session.detach" "session.delete"]]
    (let [accepted (promise)
          reply (a/promise-chan)]
      (with-runtime {}
        (fn [m _] (cond
                    (= m "session.list") (a/go {:result {:sessions [{:session-id "original"}]}})
                    (= m method) (do (deliver accepted true) reply)))
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

(deftest a-session-detach-joiner-owns-its-interrupt
  (doseq [during-wait? [false true]]
    (let [accepted (promise)
          ack (a/promise-chan)]
      (with-runtime {}
        (fn [method _] (when (= method "session.detach") (deliver accepted true) ack))
        (fn [c _]
          (let [s (sdk/open-session!! c {})
                owner (future (sdk/close!! s))
                _ (bounded accepted)
                waiting (promise)
                timer a/timeout
                joiner (with-redefs [a/timeout (fn [^long ms]
                                                 (when (= ms 65000) (deliver waiting (Thread/currentThread)))
                                                 (timer ms))]
                         (let [f (future
                                   (when-not during-wait? (.interrupt (Thread/currentThread)))
                                   [(try (sdk/close!! s) (catch InterruptedException _ :interrupted))
                                    (.isInterrupted (Thread/currentThread))])]
                           (bounded waiting)
                           f))]
            (try
              (when during-wait?
                (.interrupt ^Thread (bounded waiting))
                (is (= [:interrupted true] (bounded joiner))))
              (a/put! ack {:result {:success true}})
              (is (nil? (bounded owner)))
              (when-not during-wait? (is (= [nil true] (bounded joiner))))
              (finally (a/close! ack)))))))))

(deftest unanswered-detach-or-delete-fences-the-session-id
  (doseq [[method id open?] [["session.detach" "uncertain" true] ["session.delete" "uncertain" true]
                             ["session.delete" "not-open" false]]]
    (let [accepted (promise)
          reply (a/promise-chan)
          deadline (a/chan)
          timer a/timeout]
      (with-runtime {}
        (fn [m _]
          (cond
            (= m "session.list") (a/go {:result {:sessions [{:session-id id}]}})
            (= m method) (do (deliver accepted true) reply)))
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

(deftest uncertain-delete-retires-local-subscriptions-before-client-close-returns
  (let [sent (promise) ack (a/promise-chan) deadline (a/chan)
        at-fence (promise) resume (promise) shutdown (promise) clock a/timeout]
    (with-runtime {}
      (fn [method _]
        (case method
          "session.list" (a/go {:result {:sessions [{:session-id "saved"}]}})
          "session.delete" (do (deliver sent true) ack)
          "runtime.shutdown" (do (deliver shutdown true) (a/go {:result {}}))
          nil))
      (fn [c _]
        (let [s (sdk/open-session!! c {:session-id "saved"})
              stream (sdk/subscribe s)
              registry (.-sessions c)]
          ;; Pause the deletion thread at its registry transition. If it still
          ;; owns the client monitor, resuming is necessary for close to enter.
          (add-watch registry ::fence
                     (fn [_ _ old new]
                       (when (and (identical? s (get old "saved")) (= ::client/unconfirmed-cleanup (get new "saved")))
                         (deliver at-fence (Thread/holdsLock c))
                         (bounded resume))))
          (try
            (let [delete (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) deadline (clock ms)))]
                           (let [d (future (try (sdk/delete-session!! c "saved") (catch Exception e e)))]
                             (bounded sent) (a/close! deadline) (bounded at-fence) d))
                  close (future (sdk/close!! c))]
              (when (bounded at-fence) (deliver resume true))
              (bounded shutdown)
              (is (nil? (bounded close)))
              (is (ap/closed? stream))
              (is (= (-> (a/poll! stream) :error ex-data :type) :session-closed))
              (deliver resume true)
              (is (= (ex-message (bounded delete)) "Request timeout")))
            (finally (deliver resume true) (a/close! ack) (remove-watch registry ::fence))))))))

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

(deftest subscriptions-do-not-use-the-clients-event-observer-slots
  (with-runtime {}
    (constantly nil)
    (fn [c conn]
      (let [observed {:events {:on-event (constantly nil)}}]
        (dotimes [i 16] (sdk/open-session!! c (assoc observed :session-id (str "observed-" i))))
        (is (= (try (sdk/open-session!! c (assoc observed :session-id "extra"))
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
               :observer-capacity))
        (with-open [s (sdk/open-session!! c {:session-id "extra"})]
          (let [stream (sdk/subscribe s)]
            (try
              (rpc/send-notification conn "session.event"
                                     {:session-id "extra" :event {:id "after-subscribe" :type "session.info" :data {}}})
              (is (= (:id (take!! stream)) "after-subscribe"))
              (finally (a/close! stream)))))))))

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
            id (sdk/with-session [s c (assoc opts :name "Offline prefix regression"
                                             :session-id "a1234567-fade-4123-abcd-0123456789ab")]
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
                    [(rejected #(sdk/delete-session!! c prefix))
                     (rejected #(sdk/delete-session!! c (str/upper-case id))) (metadata-id id)
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
    (is (= alias [:ambiguous-delete-id :session-id-case-conflict original true]))
    (is (= duplicate {:type :session-already-open :session-id short}))
    (is (= provider [short :session-fs-delete-unsupported short]))
    (is (= exact [short :ambiguous-delete-id short original]))
    (is (= deleted [nil :session-closed original]))))
