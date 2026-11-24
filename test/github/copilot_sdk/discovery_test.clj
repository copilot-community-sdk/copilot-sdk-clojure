(ns github.copilot-sdk.discovery-test
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.discovery :as discovery]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-client :as client]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc])
  (:import [java.io EOFException PipedInputStream PipedOutputStream]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- outcome [ch]
  (let [[v port] (a/alts!! [ch (a/timeout 3000)])]
    (when-not (= port ch) (throw (ex-info "Test timed out" {})))
    v))

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

(defn- with-traced-peer [f]
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
                         (when result
                           (peer/write-framed! to-client {:jsonrpc "2.0" :id id :result result}))
                         (recur)))
                     (catch EOFException _)))]
      (try
        (with-redefs [host/open! (fn [_] {:in input :out output :configure-ui! (constantly nil) :close! #(.close output)})]
          (with-open [c (client/open-client! {:mode :coding-agent :observability {:on-get-trace-context trace}
                                              :models {:on-list (constantly [])}})
                      s (client/open-session! c {:session-id "traced"})]
            (f c s gate frames)))
        (finally
          (a/close! gate)
          (.close output)
          (try (is (nil? (deref server 3000 ::timeout)))
               (finally (future-cancel server))))))))

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
  (let [timeout a/timeout]
    (with-redefs [a/timeout (fn [^long ms] (if (= ms 43210) deadline (timeout ms)))]
      (client/send-and-wait! s {:prompt prompt :timeout-ms 43210}))))

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
          (is (= (pending) #{second-id})))
        (client/close-session! s)
        (is (= (:type (ex-data (:error (outcome closed)))) :session-closed))
        (is (empty? (pending)))
        (a/offer! gate {:traceparent "late"})
        (client/await!! (client/ping c "barrier"))
        (is (= (mapv #(get-in % [:params :prompt]) (filter #(= "session.sendAndWait" (:method %)) @frames))
               ["hold" "hold"]))))))

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
          (let [{:keys [cancel! reply]} (#'client/submit-turn s "abandoned")]
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
            (is (= (some-> (outcome (client/list-models c)) :ok) []))
            (is (= (outcome (binding [*parent* "healthy"] (client/send! healthy "healthy"))) {:ok "healthy"}))
            (is (= (get-in (last @frames) [:params :traceparent]) "healthy"))
            (is (not (a/offer! hold {:traceparent "late"})))
            (is (not-any? #(= "session.sendAndWait" (:method %)) @frames))))))))
