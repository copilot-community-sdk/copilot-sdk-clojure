(ns github.copilot-sdk.discovery-test
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.callback-scope :as scope]
            [github.copilot-sdk.discovery :as discovery]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-client :as client]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.turn :as turn]
            [github.copilot-sdk.util :as util])
  (:import [java.io EOFException PipedInputStream PipedOutputStream]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- outcome [ch]
  (let [[v port] (a/alts!! [ch (a/timeout 3000)])]
    (when-not (= port ch) (throw (ex-info "Test timed out" {})))
    v))

(defn- after-provider-release [request]
  ;; A cancellation latch fires before the worker returns to the executor.
  (let [deadline (+ (System/nanoTime) 3000000000)]
    (loop []
      (let [result (outcome (request))]
        (if (and (= :provider-capacity (:type (ex-data (:error result)))) (< (System/nanoTime) deadline))
          (recur)
          result)))))

(defn- with-owner [opts f]
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        owner (discovery/open! conn opts)]
    (try (f owner conn ->client <-client)
         (finally
           (discovery/close! owner)
           (rpc/disconnect conn)
           (.close ->client)
           (.close <-client)))))

(deftest rpc-models-cache
  (with-owner
    {}
    (fn [owner conn to-client from-client]
      (let [first-call (discovery/models owner)
            concurrent (discovery/models owner)
            response (fn [body]
                       (let [read-task (future (peer/read-framed from-client))
                             request (try (deref read-task 3000 nil)
                                          (finally (future-cancel read-task)))]
                         (is (= (select-keys request [:method :params]) {:method "models.list" :params {}}))
                         (peer/write-framed! to-client (assoc body :jsonrpc "2.0" :id (:id request)))))
            metadata {"HTTP/Status" [{"sameKey" true "same_key" false "n" 9007199254740993}]}
            wire [{:id "embed" :name "Embedding" :metadata metadata}
                  {:id "partial" :name "Partial" :capabilities {:supports {:toolCalls false}
                                                                :limits {:max_output_tokens 32}}}
                  {:id "full" :name "Full" :capabilities {:limits {:max_context_window_tokens 1234}}}]
            expected [{:id "embed" :name "Embedding" :metadata metadata
                       :capabilities {:supports {} :limits {:max-context-window-tokens 0}}}
                      {:id "partial" :name "Partial" :capabilities {:supports {:tool-calls false}
                                                                    :limits {:max-output-tokens 32
                                                                             :max-context-window-tokens 0}}}
                      {:id "full" :name "Full" :capabilities {:supports {}
                                                              :limits {:max-context-window-tokens 1234}}}]]
        (response {:error {:code -32000 :message "Try again"}})
        (is (= (ex-message (:error (outcome first-call))) "Try again"))
        (is (= (ex-message (:error (outcome concurrent))) "Try again"))
        (let [retry (discovery/models owner)
              another (discovery/models owner)]
          (response {:result {:models wire}})
          (doseq [ch [retry another (discovery/models owner)]]
            (is (= (outcome ch) {:ok expected}))))
        (rpc/end-input! conn)
        (is (= (:type (ex-data (:error (outcome (discovery/models owner))))) :connection-closed))))))

(def ^:dynamic *parent* nil)

(deftest concurrent-model-callers-each-get-the-shared-result
  (let [gate (a/promise-chan)
        calls (atom 0)]
    (with-owner {:models {:on-list #(do (swap! calls inc) gate)}}
      (fn [owner _ _ _]
        (let [results (vec (repeatedly 1100 #(discovery/models owner)))]
          (a/close! (discovery/models owner))
          (a/>!! gate [])
          (is (= (frequencies (map outcome results)) {{:ok []} (count results)}))
          (is (= @calls 1)))))))

(deftest discovery-workers-do-not-inherit-a-previous-request-span
  (let [local (InheritableThreadLocal.)]
    (with-owner {:observability {:on-get-trace-context (fn []
                                                         (cond-> {:traceparent *parent*}
                                                           (.get local) (assoc :tracestate (.get local))))}}
      (fn [owner _ _ _]
        (try
          (doseq [parent ["first" "second"]]
            (.set local parent)
            (binding [*parent* (.get local)]
              (is (= (discovery/trace-context!! owner) {"traceparent" parent}))))
          (finally (.remove local)))))))

(deftest callback-models-and-trace
  (let [started (promise)
        listed (a/promise-chan)
        calls (atom 0)
        trace (atom nil)
        callback (fn [] (swap! calls inc) (deliver started true) listed)
        model {:id "mine" :name "Mine" :capabilities {}}]
    (with-owner
      {:models {:on-list callback}
       :observability {:on-get-trace-context #(if @trace (@trace) {:traceparent *parent*})}}
      (fn [owner _ _ _]
        (let [one (discovery/models owner)]
          (is (true? (deref started 3000 false)))
          (let [two (discovery/models owner)]
            (a/close! (discovery/models owner))
            (a/>!! listed [model])
            (is (= (mapv outcome [one two (discovery/models owner)]) (repeat 3 {:ok [model]})))
            (is (= @calls 1))))
        (testing "use the calling context, not cached headers or the worker's bindings"
          (doseq [parent ["alpha" "beta"]]
            (is (= (binding [*parent* parent] (discovery/trace-context!! owner)) {"traceparent" parent}))))
        (testing "tracing is best effort and cannot add unrelated request fields"
          (doseq [provider [(fn [] (throw (ex-info "No span" {})))
                            (constantly nil)
                            (constantly {:session-id "other"})]]
            (reset! trace provider)
            (is (= (discovery/trace-context!! owner) {})))
          (reset! trace #(a/go {:tracestate "vendor=1"}))
          (is (= (discovery/trace-context!! owner) {"tracestate" "vendor=1"})))))))

(deftest close-releases-the-application-discovery-providers
  (let [list-provider (constantly [])
        trace-provider (constantly {})]
    (with-owner {:models {:on-list list-provider}
                 :observability {:on-get-trace-context trace-provider}}
      (fn [owner _ _ _]
        (is (= @(:providers owner) {:on-list list-provider :on-trace trace-provider}))
        (discovery/close! owner)
        (is (nil? @(:providers owner)))
        (is (= (-> (discovery/models owner) outcome :error ex-data :type) :connection-closed))
        (is (= (discovery/trace-context!! owner) {}))))))

(deftest disconnect-cancels-user-work
  (let [started (promise)
        interrupted (promise)]
    (with-owner
      {:models {:on-list (fn []
                           (deliver started true)
                           (try @(promise)
                                (catch InterruptedException e
                                  (deliver interrupted true)
                                  (throw e))))}}
      (fn [owner conn _ _]
        (let [result (discovery/models owner)]
          (is (true? (deref started 3000 false)))
          (rpc/end-input! conn)
          (is (= (:type (ex-data (:error (outcome result)))) :connection-closed))
          (is (true? (deref interrupted 3000 false))))))))

(deftest interrupting-a-synchronous-trace-lookup-retires-its-provider
  (let [entered (CountDownLatch. 4)
        interrupted (CountDownLatch. 4)
        release (promise)]
    (with-owner
      {:models {:on-list (constantly [])}
       :observability {:on-get-trace-context (fn [] (.countDown entered)
                                               (try @release (catch InterruptedException _ (.countDown interrupted) {})))}}
      (fn [owner _ _ _]
        (let [threads (repeatedly 4 promise)
              lookups (mapv (fn [thread]
                              (future (deliver thread (Thread/currentThread))
                                      (try (discovery/trace-context!! owner)
                                           (catch InterruptedException _ (.isInterrupted (Thread/currentThread))))))
                            threads)]
          (try
            (is (.await entered 3 TimeUnit/SECONDS))
            (doseq [thread threads] (.interrupt ^Thread (deref thread 3000 nil)))
            (is (= (mapv #(deref % 3000 ::timeout) lookups) [true true true true]))
            (is (.await interrupted 3 TimeUnit/SECONDS))
            (is (= (after-provider-release #(discovery/models owner)) {:ok []}))
            (finally (deliver release {}))))))))

(deftest mutable-callback-result-is-not-cached
  (let [model {:id "custom" :name "Custom" :capabilities {}}
        value (atom [(assoc model :metadata {"nested" [(StringBuilder. "changes")]})])]
    (with-owner
      {:models {:on-list #(deref value)}}
      (fn [owner _ _ _]
        (is (= (:type (ex-data (:error (outcome (discovery/models owner))))) :invalid-models))
        (reset! value [model])
        (is (= (outcome (discovery/models owner)) {:ok [model]}))))))

(deftest trace-overload-and-close
  (let [entered (java.util.concurrent.CountDownLatch. 4)
        hold (a/chan)]
    (with-owner
      {:observability {:on-get-trace-context (fn [] (.countDown entered) hold)}}
      (fn [owner _ _ _]
        (let [pending (vec (repeatedly 4 #(discovery/trace-context owner)))]
          (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
          (is (= (outcome (discovery/trace-context owner)) {}))
          (discovery/close! owner)
          (is (= (mapv outcome pending) (repeat 4 {})))
          (is (not (a/offer! hold {:traceparent "abandoned"}))))))))

(defn- with-session-peer [trace? f & [on-list]]
  (with-open [input (PipedInputStream. 65536)
              to-client (PipedOutputStream. input)
              from-client (PipedInputStream. 65536)
              output (PipedOutputStream. from-client)]
    (let [frames (atom [])
          gate (a/promise-chan)
          trace #(cond (= *parent* :gated) gate
                       (fn? *parent*) (*parent*)
                       :else {:traceparent *parent*})
          server (future
                   (try
                     (loop []
                       (let [{:keys [method params id] :as frame} (peer/read-framed from-client)
                             result (case method
                                      "connect" {:protocolVersion 4}
                                      ("session.create" "session.resume") {:sessionId (:sessionId params)}
                                      "session.send" {:messageId (:prompt params)}
                                      "session.sendAndWait" (when-not (= "hold" (:prompt params))
                                                              {:messageId (:prompt params) :aborted false})
                                      "session.getMessages" {:events []}
                                      {:success true})]
                         (swap! frames conj frame)
                         (when (and id result)
                           (peer/write-framed! to-client {:jsonrpc "2.0" :id id :result result}))
                         (recur)))
                     (catch EOFException _)))]
      (try
        (with-redefs [host/open! (fn [_] {:in input :out output :configure-ui! (constantly nil) :close! #(.close output)})]
          (with-open [c (client/open-client! (cond-> {:mode :coding-agent :models {:on-list (or on-list (constantly []))}}
                                               trace? (assoc :observability {:on-get-trace-context trace})))
                      s (client/open-session! c {:session-id "traced"})]
            (f c s gate frames)))
        (finally
          (a/close! gate)
          (.close output)
          (try (is (nil? (deref server 3000 ::timeout)))
               (finally (future-cancel server))))))))

(defn- with-traced-peer [f] (with-session-peer true f))

(deftest opening-reserves-the-id-before-asking-for-trace-context
  (with-traced-peer
    (fn [c _ _ frames]
      (doseq [create? [true false] same? [true false]]
        (let [id (str "outer-" create? "-" same?)
              other (if same? id (str id "-other"))
              start (count @frames)
              result (fn [f] (try (sdk/session-id (f)) (catch Exception e (:type (ex-data e)))))
              nested (promise)
              provider #(do (deliver nested (binding [*parent* "nested"]
                                              (result (fn [] (sdk/resume-session!! c other {})))))
                            {:traceparent "outer"})
              outer (binding [*parent* provider]
                      (result #(if create? (sdk/open-session!! c {:session-id id}) (sdk/resume-session!! c id {}))))
              outer-frame [(if create? "session.create" "session.resume") id "outer"]]
          (is (= outer id))
          (is (= (deref nested 3000 ::timeout) (if same? :session-already-open other)))
          (is (= (mapv (juxt :method #(get-in % [:params :sessionId]) #(get-in % [:params :traceparent]))
                       (drop start @frames))
                 (if same? [outer-frame] [["session.resume" other "nested"] outer-frame]))))))))

(deftest close-cancels-tracing-before-waiting-for-an-unsubmitted-open
  (doseq [config [{:session-id "reserved"} {:cloud {}}]]
    (with-traced-peer
      (fn [c _ _ frames]
        (let [entered (promise) interrupted (promise) release (promise)
              opening (future
                        (binding [*parent* #(try (deliver entered true) @release
                                                 (finally (deliver interrupted true)))]
                          (try (sdk/open-session!! c config) (catch Exception e (:type (ex-data e))))))]
          (try
            (is (true? (deref entered 3000 false)))
            (is (nil? (deref (future (sdk/close!! c)) 3000 ::timeout)))
            (is (true? (deref interrupted 3000 false)))
            (is (contains? #{:session-closed :client-closed} (deref opening 3000 ::timeout)))
            (is (= (mapv :method @frames) ["connect" "session.create" "session.detach" "runtime.shutdown"]))
            (finally (deliver release {}))))))))

(deftest discovery-workers-cannot-shut-down-their-own-client
  (doseq [lookup [:models :trace] close! [sdk/close!! sdk/force-close!!]]
    (with-session-peer
      true
      (fn [c s _ frames]
        (let [refused (promise)
              provider (fn []
                         (deliver refused (try (close! c) :closed (catch Exception e (:type (ex-data e)))))
                         (if (= lookup :models) [] {:traceparent "parent"}))]
          (binding [*parent* provider]
            (if (= lookup :models)
              (is (= (outcome (sdk/list-models c)) {:ok []}))
              (is (= (outcome (sdk/send! s "traced")) {:ok "traced"}))))
          (is (= (deref refused 3000 ::timeout) :callback-client-close))
          (is (= (outcome (sdk/send! s "healthy")) {:ok "healthy"}))
          (is (= (mapv :method @frames)
                 (cond-> ["connect" "session.create" "session.send"] (= lookup :trace) (conj "session.send"))))
          (when (= lookup :trace)
            (is (= (get-in @frames [2 :params :traceparent]) "parent")))))
      #(*parent*))))

(deftest discovery-can-close-another-client-and-hand-off-its-own-shutdown
  (with-session-peer
    false
    (fn [other _ _ other-frames]
      (with-traced-peer
        (fn [c s _ _]
          (let [finished (promise)
                cancelled (promise)
                release (promise)
                provider (fn []
                           (sdk/close!! other)
                           (future (deliver finished (try (sdk/close!! c) (catch Exception e e))))
                           (try @release (catch InterruptedException _ (deliver cancelled true) {})))]
            (try
              (let [send (binding [*parent* provider] (sdk/send! s "retired"))]
                (is (true? (deref cancelled 3000 false)))
                (is (nil? (deref finished 3000 ::timeout)))
                (is (= (-> send outcome :error ex-data :type) :session-closed))
                (is (= (mapv :method @other-frames) ["connect" "session.create" "session.detach" "runtime.shutdown"])))
              (finally (deliver release {})))))))))

(deftest discovery-retains-the-calling-lifecycle-scope
  (with-owner
    {:observability {:on-get-trace-context #(*parent*)}}
    (fn [owner conn _ _]
      (let [session (Object.)
            check (fn [op] (try (or (scope/check! conn op session) :allowed)
                                (catch Exception e (:type (ex-data e)))))]
        (doseq [[kind detach shutdown] [[:decision :callback-session-close :callback-client-close]
                                        [:reverse :callback-session-close :reverse-callback-client-close]
                                        [:observer :allowed :callback-client-close]
                                        [:factory :session-fs-factory-reentry :session-fs-factory-reentry]
                                        [:filesystem :callback-session-close :reverse-callback-client-close]
                                        [:end-hook :ending :session-end-hook-reentry]]]
          (scope/invoke conn session kind
                        #(binding [*parent* (fn []
                                              {:traceparent (name (check :detach)) :tracestate (name (check :shutdown))})]
                           (is (= (discovery/trace-context!! owner)
                                  {"traceparent" (name detach) "tracestate" (name shutdown)})))))
        ;; An app future, unlike an SDK provider call on the callback worker,
        ;; hands off the work. Its provider must not inherit a physical self-join.
        (scope/invoke conn session :decision
                      #(binding [*parent* (fn [] {:traceparent (name (check :detach))})]
                         (is (= (deref (future (discovery/trace-context!! owner)) 3000 :timeout)
                                {"traceparent" "allowed"}))))
        (let [continuation (promise)]
          (scope/invoke conn session :decision
                        #(binding [*parent* (fn [] (deliver continuation (bound-fn [] (check :detach))) {})]
                           (discovery/trace-context!! owner)
                           (is (= ((deref continuation 3000 (constantly :timeout))) :callback-session-close))))
          (is (= ((deref continuation 3000 (constantly :timeout))) :allowed)))))))

(deftest expired-turn-does-not-send-after-acquiring-the-client-lock
  (with-session-peer
    false
    (fn [c s _ frames]
      (let [armed (promise)
            alarm (a/chan)
            start! @#'turn/start-deadline
            [expired healthy]
            (locking c
              (let [expired (future
                              (with-redefs [turn/start-deadline (fn [ms]
                                                                  (if (= ms 43210)
                                                                    (do (deliver armed true) [alarm (constantly nil)])
                                                                    (start! ms)))]
                                (client/send-and-wait! s {:prompt "hold" :timeout-ms 43210})))
                    healthy (future (client/send-and-wait! s "healthy"))]
                (is (true? (deref armed 3000 false)))
                (a/close! alarm)
                [expired healthy]))]
        (is (= (-> (deref expired 3000 nil) outcome :error ex-data) {:type :turn-timeout :timeout-ms 43210}))
        (is (= (outcome (deref healthy 3000 nil)) {:ok nil}))
        (client/await!! (client/ping c "barrier"))
        (is (= (mapv (juxt :method #(get-in % [:params :prompt])) (drop 2 @frames))
               [["session.sendAndWait" "healthy"] ["ping" nil]]))))))

(deftest expired-traced-head-does-not-send-before-the-timeout-worker-runs
  (with-traced-peer
    (fn [c s gate frames]
      (let [entered (promise)
            delayed-alarm (a/chan)
            timeout a/timeout
            timeout-ms 100
            expired (with-redefs [turn/start-deadline (fn [_] [delayed-alarm (constantly nil)])]
                      (binding [*parent* #(do (deliver entered true) gate)]
                        (client/send-and-wait! s {:prompt "expired" :timeout-ms timeout-ms})))
            healthy (client/send-and-wait! s "healthy")]
        (try
          (is (true? (deref entered 3000 false)))
          ;; Release trace only after the admission budget, without notifying the waiter.
          (a/<!! (timeout timeout-ms))
          (a/offer! gate {:traceparent "expired"})
          (is (= (-> expired outcome :error ex-data) {:type :turn-timeout :timeout-ms timeout-ms}))
          (is (= (outcome healthy) {:ok nil}))
          (client/await!! (client/ping c "barrier"))
          (is (= (mapv (juxt :method #(get-in % [:params :prompt])) (drop 2 @frames))
                 [["session.sendAndWait" "healthy"] ["ping" nil]]))
          (finally (a/close! delayed-alarm)))))))

(deftest traced-requests-admit-before-return-without-waiting-for-a-reply
  (with-traced-peer
    (fn [c s gate frames]
      (let [one (binding [*parent* :gated] (client/send-and-wait! s "first"))
            two (binding [*parent* "two"] (client/send! s "second"))
            abort (client/abort! s)
            history (client/history s false)]
        ;; A client request is a wire barrier independent of this session.
        (client/await!! (client/ping c "barrier"))
        (is (= (mapv :method @frames) ["connect" "session.create" "ping"]))
        (a/offer! gate {:traceparent "one"})
        (is (= (mapv outcome [one two abort history]) [{:ok nil} {:ok "second"} {:ok nil} {:ok []}]))
        (is (= (mapv (juxt :method #(get-in % [:params :traceparent])) (drop 3 @frames))
               [["session.sendAndWait" "one"] ["session.send" "two"]
                ["session.abort" nil] ["session.getMessages" nil]]))))))

(deftest a-trace-provider-cannot-queue-work-behind-its-own-send
  (with-traced-peer
    (fn [c session _ frames]
      (with-open [other (sdk/open-session!! c {:session-id "other"})]
        (let [nested (promise)
              trace (fn []
                      (let [history (sdk/await!! (sdk/history other))
                            read-own (fn [] (try (sdk/await!! (sdk/history session)) (catch Exception e (:type (ex-data e)))))]
                        (deliver nested [history (read-own) (deref (future (read-own)) 3000 ::timeout)])
                        {:traceparent "outer"}))
              sent (binding [*parent* trace] (sdk/send! session "hello"))]
          (is (= (deref nested 3000 ::timeout) [[] :trace-provider-reentry :trace-provider-reentry]))
          (is (= (outcome sent) {:ok "hello"}))
          (is (= (mapv (juxt :method #(get-in % [:params :sessionId]) #(get-in % [:params :traceparent]))
                       (filter #(contains? #{"session.send" "session.getMessages"} (:method %)) @frames))
                 [["session.getMessages" "other" nil] ["session.send" "traced" "outer"]])))))))

(deftest trace-lookups-start-at-admission-and-do-not-restart-at-the-head
  (with-traced-peer
    (fn [c s head frames]
      (let [head-started (promise)
            first-send (binding [*parent* #(do (deliver head-started true) head)] (client/send! s "0"))
            entered (CountDownLatch. 3)
            expired (CountDownLatch. 3)
            deadline (a/chan)
            timeout a/timeout
            unexpected (atom 0)
            release (promise)]
        (is (true? (deref head-started 3000 false)))
        (let [queued (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) deadline (timeout ms)))]
                       (binding [*parent* #(try (.countDown entered) @release (finally (.countDown expired)))]
                         (mapv #(client/send! s (str %)) [1 2 3])))]
          ;; The head stays gated. All other available workers and their
          ;; deadlines must be active before any of these sends reaches it.
          (is (.await entered 2 TimeUnit/SECONDS))
          (let [excess (binding [*parent* #(do (swap! unexpected inc) {:traceparent "late"})]
                         (mapv #(client/send! s (str %)) [4 5]))
                abort (client/abort! s)]
            (a/close! deadline)
            (is (.await expired 2 TimeUnit/SECONDS))
            (deliver release {})
            (client/await!! (client/ping c "barrier"))
            (is (= (mapv :method @frames) ["connect" "session.create" "ping"]))
            (a/offer! head {:traceparent "first"})
            (is (= (mapv outcome (concat [first-send] queued excess [abort]))
                   (conj (mapv #(hash-map :ok (str %)) (range 6)) {:ok nil})))
            (is (= (mapv (juxt :method #(get-in % [:params :prompt]) #(get-in % [:params :traceparent]))
                         (drop 3 @frames))
                   (conj (mapv #(vector "session.send" (str %) (when (zero? %) "first")) (range 6))
                         ["session.abort" nil nil])))
            (is (zero? @unexpected))))))))

(defn- turn-with-deadline [s prompt deadline]
  (with-redefs [turn/start-deadline (fn [_] [deadline (constantly nil)])]
    (client/send-and-wait! s {:prompt prompt :timeout-ms 43210})))

(deftest turn-expiring-during-wire-preparation-never-reaches-the-peer
  (doseq [trace? [false true]]
    (testing (if trace? "trace reservation" "direct submission")
      (with-session-peer
        trace?
        (fn [c s _ frames]
          (let [deadline (a/chan)
                convert util/clj->wire]
            (with-redefs [util/clj->wire (fn [params]
                                           (when (= "hold" (get params "prompt"))
                                             (a/close! deadline))
                                           (convert params))]
              (let [expired (turn-with-deadline s "hold" deadline)
                    healthy (client/send-and-wait! s "healthy")]
                (is (= (-> expired outcome :error ex-data) {:type :turn-timeout :timeout-ms 43210}))
                (is (= (outcome healthy) {:ok nil}))
                (client/await!! (client/ping c "barrier"))
                (is (= (mapv (juxt :method #(get-in % [:params :prompt])) (drop 2 @frames))
                       [["session.sendAndWait" "healthy"] ["ping" nil]]))))))))))

(deftest timeout-retires-only-its-own-reservation-or-wire-request
  (with-traced-peer
    (fn [c s gate frames]
      (let [d1 (a/chan)
            skipped (binding [*parent* :gated] (turn-with-deadline s "skipped" d1))
            d2 (a/chan)
            timed (turn-with-deadline s "hold" d2)
            closed (client/send-and-wait! s "hold")
            barrier (client/abort! s)
            pending #(set (keys (get-in @(:state-atom (.-connection c)) [:connection :pending-requests])))]
        (a/close! d1)
        (is (= (:type (ex-data (:error (outcome skipped)))) :turn-timeout))
        ;; The other turns reach the wire without waiting for the gate or either RPC reply.
        (is (= (outcome barrier) {:ok nil}))
        (let [[first-id second-id :as ids] (mapv :id (filter #(= "session.sendAndWait" (:method %)) @frames))]
          (is (= (count ids) 2))
          (is (= (pending) #{first-id second-id}))
          (a/close! d2)
          (is (= (:type (ex-data (:error (outcome timed)))) :turn-timeout))
          (is (= (pending) #{second-id}))
          (client/close-session! s)
          (is (= (:type (ex-data (:error (outcome closed)))) :session-closed))
          (is (empty? (pending)))
          (a/offer! gate {:traceparent "late"})
          (client/await!! (client/ping c "barrier"))
          (is (= (mapv :method @frames)
                 ["connect" "session.create" "session.sendAndWait" "session.sendAndWait" "session.abort"
                  "$/cancelRequest" "session.detach" "$/cancelRequest" "ping"]))
          (is (= (filter #(= "$/cancelRequest" (:method %)) @frames)
                 (map (fn [id] {:jsonrpc "2.0" :method "$/cancelRequest" :params {:id id}}) ids)))
          (is (= (mapv #(get-in % [:params :prompt]) (filter #(= "session.sendAndWait" (:method %)) @frames))
                 ["hold" "hold"])))))))

(deftest closing-a-turn-result-withdraws-only-that-local-wait
  (with-traced-peer
    (fn [c s gate frames]
      (let [queued (binding [*parent* :gated] (sdk/send-and-wait! s "abandoned"))]
        (a/close! queued)
        (a/offer! gate {:traceparent "late"})
        (is (= (outcome (sdk/history s)) {:ok []}))
        (is (not-any? #(= "abandoned" (get-in % [:params :prompt])) @frames))
        (is (nil? (outcome queued))))
      (let [held (vec (repeatedly 64 #(sdk/send-and-wait! s "hold")))]
        (sdk/await!! (sdk/history s))
        (let [ids (into #{} (comp (filter #(= "hold" (get-in % [:params :prompt]))) (map :id)) @frames)]
          (is (= (count ids) 64))
          (doseq [result held] (a/close! result))
          (is (= (outcome (sdk/send-and-wait! s "healthy")) {:ok nil}))
          (sdk/await!! (sdk/ping c "barrier"))
          (is (= (frequencies (keep #(when (= "$/cancelRequest" (:method %)) (get-in % [:params :id])) @frames))
                 (zipmap ids (repeat 1))))
          (is (not-any? #(= "session.abort" (:method %)) @frames)))))))

(deftest session-close-rejects-queued-generation-before-headers-arrive
  (with-traced-peer
    (fn [c s gate frames]
      (let [stale (binding [*parent* :gated] (client/send! s "stale"))
            other (client/history s false)]
        (client/close-session! s)
        (with-open [replacement (client/resume-session! c (client/session-id s) {})]
          (is (= (mapv #(ex-data (:error (outcome %))) [stale other])
                 (repeat 2 {:type :session-closed :session-id "traced"})))
          (a/offer! gate {:traceparent "late"})
          (is (= (outcome (client/send! replacement "current")) {:ok "current"}))
          (is (= (mapv :method @frames)
                 ["connect" "session.create" "session.detach" "session.resume" "session.send"])))))))

(deftest traced-session-admission-rejects-pressure-before-starting-more-requests
  (with-traced-peer
    (fn [_ s gate frames]
      (let [first-send (binding [*parent* :gated] (client/send! s "first"))
            waiting (vec (repeatedly 63 #(client/history s false)))]
        (is (= (:type (ex-data (:error (outcome (client/abort! s))))) :admission-capacity))
        (a/offer! gate {:traceparent "ready"})
        (is (= (outcome first-send) {:ok "first"}))
        (is (= (mapv outcome waiting) (repeat 63 {:ok []})))
        (is (= (outcome (client/abort! s)) {:ok nil}))
        (is (= (frequencies (map :method @frames)) {"connect" 1 "session.create" 1 "session.send" 1
                                                    "session.getMessages" 63 "session.abort" 1}))))))

(deftest cancelled-reservations-reclaim-capacity-without-releasing-the-head
  (with-traced-peer
    (fn [c s gate frames]
      (let [entered (promise)
            first-send (binding [*parent* #(do (deliver entered true) gate)] (client/send! s "head"))]
        (is (true? (deref entered 3000 false)))
        (dotimes [_ 3]
          (let [deadline (a/chan)
                active (CountDownLatch. 3)
                exited (CountDownLatch. 3)
                waiting (binding [*parent* #(try (.countDown active) @(promise)
                                                 (finally (.countDown exited)))]
                          (vec (repeatedly 63 #(turn-with-deadline s "expired" deadline))))]
            (is (.await active 2 TimeUnit/SECONDS))
            (a/close! deadline)
            (is (= (frequencies (map #(-> % outcome :error ex-data :type) waiting)) {:turn-timeout 63}))
            (is (.await exited 2 TimeUnit/SECONDS)))
          ;; Turn timeouts do not read the abandoned reply. Verify its own go
          ;; block terminates without waiting for the head, not just its accounting.
          (let [{:keys [cancel! reply]} (#'client/submit-turn s "abandoned" {})]
            (cancel!)
            (is (nil? (outcome reply)))))
        (let [abort (client/abort! s)
              last-send (binding [*parent* "last"] (client/send! s "tail"))]
          (client/await!! (client/ping c "barrier"))
          (is (= (mapv :method @frames) ["connect" "session.create" "ping"]))
          (a/offer! gate {:traceparent "first"})
          (is (= (mapv outcome [first-send abort last-send])
                 [{:ok "head"} {:ok nil} {:ok "tail"}]))
          (is (= (mapv (juxt :method #(get-in % [:params :traceparent])) (drop 3 @frames))
                 [["session.send" "first"] ["session.abort" nil] ["session.send" "last"]])))))))

(deftest retiring-active-heads-releases-their-trace-providers
  (doseq [retire [:deadline :detach]]
    (testing (name retire)
      (with-traced-peer
        (fn [c healthy _ frames]
          (let [sessions (mapv #(client/open-session! c {:session-id (str "held-" %)}) (range 4))
                deadline (a/chan)
                entered (CountDownLatch. 4)
                exited (CountDownLatch. 3)
                hold (a/chan)
                parked (reify ap/ReadPort
                         (take! [_ handler]
                           (let [v (ap/take! hold handler)]
                             (.countDown entered)
                             v)))
                turns (mapv (fn [i s]
                              (binding [*parent* (if (zero? i)
                                                   (constantly parked)
                                                   #(try (.countDown entered) @(promise)
                                                         (finally (.countDown exited))))]
                                (turn-with-deadline s "retire" deadline)))
                            (range) sessions)]
            (is (.await entered 3 TimeUnit/SECONDS))
            (case retire
              :deadline (a/close! deadline)
              :detach (run! client/close-session! sessions))
            (is (= (mapv #(-> % outcome :error ex-data :type) turns)
                   (repeat 4 (if (= retire :deadline) :turn-timeout :session-closed))))
            (is (.await exited 2 TimeUnit/SECONDS))
            (is (= (after-provider-release #(client/list-models c)) {:ok []}))
            (is (= (outcome (binding [*parent* "healthy"] (client/send! healthy "healthy"))) {:ok "healthy"}))
            (is (= (get-in (last @frames) [:params :traceparent]) "healthy"))
            (is (not (a/offer! hold {:traceparent "late"})))
            (is (not-any? #(= "session.sendAndWait" (:method %)) @frames))))))))
