(ns github.copilot-sdk.reverse-rpc-test
  "Reverse-RPC (server-to-client request) execution policy.

   These tests drive the real NIO/JSON-RPC transport over piped streams -- no
   protocol mocking -- and assert the bounded worker contract:

   - arbitrary handler code runs on the connection's own bounded worker pool,
     never on core.async `go` dispatch
   - concurrency never exceeds the configured bound
   - saturation produces an explicit JSON-RPC error, not a silent drop or stall
   - the reader thread stays responsive while every worker is blocked
   - `disconnect` terminates blocked workers
   - notification-queue overflow is counted and warned (ASY-003 evidence)"
  (:require [clojure.core.async :as async :refer [chan put! close! >!!]]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.protocol :as protocol])
  (:import [java.io InputStream OutputStream PipedInputStream PipedOutputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private pipe-buffer (* 256 1024))

;; The worker-pool diagnostics are protocol-internal. Bind the private Vars once
;; here rather than widening the production API for tests.
(def ^:private worker-thread-name-prefix
  (var-get #'protocol/request-worker-thread-name-prefix))

(defn- connection-stats
  [conn]
  (#'protocol/connection-stats conn))

;; -----------------------------------------------------------------------------
;; Framed JSON-RPC helpers (mirror the Content-Length framing the SDK speaks)
;; -----------------------------------------------------------------------------

(defn- write-framed!
  [^OutputStream out msg]
  (let [body (.getBytes ^String (json/write-str msg) StandardCharsets/UTF_8)]
    (.write out (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n")
                           StandardCharsets/UTF_8))
    (.write out body)
    (.flush out)))

(defn- read-header-line
  [^InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.length sb)) (str sb))
          (= b 10) (str sb)
          (= b 13) (recur)
          :else (do (.append sb (char b)) (recur)))))))

(defn- read-framed
  "Read one Content-Length framed JSON message, or nil on EOF."
  [^InputStream in]
  (loop [headers {}]
    (if-let [line (read-header-line in)]
      (if (str/blank? line)
        (let [n (parse-long (get headers "content-length"))
              buf (byte-array n)]
          (loop [off 0]
            (when (< off n)
              (let [r (.read in buf off (- n off))]
                (when (neg? r) (throw (ex-info "EOF mid-message" {:expected n})))
                (recur (+ off r)))))
          (json/read-str (String. buf StandardCharsets/UTF_8) :key-fn keyword))
        (let [[k v] (str/split line #": " 2)]
          (recur (assoc headers (str/lower-case (str/trim k)) (str/trim (or v ""))))))
      nil)))

(defn- collect-by-id!
  "Read `n` framed messages, returning `{id -> message}`. Returns `::timeout`
   when fewer than `n` arrive within `timeout-ms`."
  [in n timeout-ms]
  (let [f (future (into {} (map (juxt :id identity)) (repeatedly n #(read-framed in))))
        result (deref f timeout-ms ::timeout)]
    (when (= ::timeout result)
      (future-cancel f))
    result))

(defn- wait-for
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 5) (recur))
        :else false))))

(defn- open-connection
  "Wire a real protocol connection to piped streams.

   Returns `:conn`, the `:->client` stream the fake server writes requests to,
   and the `:<-client` stream the fake server reads responses from."
  [options]
  (let [->client (PipedOutputStream.)
        client-in (PipedInputStream. ->client pipe-buffer)
        client-out (PipedOutputStream.)
        <-client (PipedInputStream. client-out pipe-buffer)
        state-atom (atom {:connection (protocol/initial-connection-state)
                          :options options})]
    {:conn (protocol/connect client-in client-out state-atom)
     :state-atom state-atom
     :->client ->client
     :<-client <-client}))

(defn- request
  [id method params]
  {:jsonrpc "2.0" :id id :method method :params params})

(defn- delivered
  "A channel already holding `v`, matching the request-handler contract."
  [v]
  (doto (chan 1) (put! v) (close!)))

(defn- blocking-handler
  "Request handler that records entry, blocks until `release` counts down, then
   answers. `entered` counts handlers currently inside the handler body;
   `peak` records the high-water mark; `threads` records the thread names.

   The count is decremented in a `finally` so an interrupted handler -- the
   `disconnect` path -- is observed to unwind."
  [{:keys [entered peak threads release]}]
  (fn [_method _params]
    (when threads (swap! threads conj (.getName (Thread/currentThread))))
    (let [now (swap! entered inc)]
      (when peak (swap! peak max now)))
    (try
      (.await ^CountDownLatch release 10 TimeUnit/SECONDS)
      (finally
        (swap! entered dec)))
    (delivered {:result {}})))

;; -----------------------------------------------------------------------------
;; ASY-004 -- bounded execution policy
;; -----------------------------------------------------------------------------

(deftest test-handler-runs-on-bounded-worker-not-go-dispatch
  (testing "arbitrary handler code executes on the connection's worker pool"
    (let [{:keys [conn ->client]} (open-connection {:request-handler-threads 2
                                                    :request-handler-queue-size 2})
          release (CountDownLatch. 1)
          threads (atom #{})
          entered (atom 0)]
      (try
        (protocol/set-request-handler!
         conn (blocking-handler {:entered entered :threads threads :release release}))
        (write-framed! ->client (request "r1" "hooks.invoke" {}))
        (is (true? (wait-for #(= 1 @entered) 2000))
            "handler should be entered")
        (is (= 1 (count @threads)))
        (is (str/starts-with? (first @threads) worker-thread-name-prefix)
            (str "handler must not run on core.async dispatch; ran on "
                 (pr-str (first @threads))))
        (finally
          (.countDown release)
          (protocol/disconnect conn))))))

(deftest test-concurrency-is-bounded
  (testing "no more handlers run concurrently than :request-handler-threads"
    (let [{:keys [conn ->client]} (open-connection {:request-handler-threads 2
                                                    :request-handler-queue-size 4})
          release (CountDownLatch. 1)
          entered (atom 0)
          peak (atom 0)]
      (try
        (protocol/set-request-handler!
         conn (blocking-handler {:entered entered :peak peak :release release}))
        (dotimes [i 6]
          (write-framed! ->client (request (str "r" i) "hooks.invoke" {})))
        (is (true? (wait-for #(= 2 @entered) 2000))
            "both workers should be busy")
        (is (false? (wait-for #(> @entered 2) 500))
            "concurrency must never exceed the configured bound")
        (is (= 2 @peak))
        (is (= 2 (:active-request-workers (connection-stats conn))))
        (is (pos? (:queued-requests (connection-stats conn)))
            "excess requests queue rather than running")
        (finally
          (.countDown release)
          (protocol/disconnect conn))))))

(deftest test-saturation-returns-explicit-error
  (testing "requests beyond threads+queue get an explicit JSON-RPC failure"
    (let [{:keys [conn ->client <-client]} (open-connection {:request-handler-threads 2
                                                             :request-handler-queue-size 2})
          release (CountDownLatch. 1)
          entered (atom 0)]
      (try
        (protocol/set-request-handler!
         conn (blocking-handler {:entered entered :release release}))
        ;; 2 execute, 2 queue, the 5th has nowhere to go.
        (dotimes [i 5]
          (write-framed! ->client (request (str "r" i) "hooks.invoke" {})))
        (let [responses (collect-by-id! <-client 1 3000)]
          (is (not= ::timeout responses)
              "an over-capacity request must be answered, not dropped or stalled")
          (when (not= ::timeout responses)
            (let [msg (get responses "r4")]
              (is (some? msg) "the over-capacity request is the one rejected")
              (is (= -32000 (get-in msg [:error :code])))
              (is (= "request_handler_saturated" (get-in msg [:error :data :code])))
              (is (= "hooks.invoke" (get-in msg [:error :data :method])))
              (is (= 2 (get-in msg [:error :data :maxConcurrency])))
              (is (= 2 (get-in msg [:error :data :queueSize]))))))
        (is (= 1 (:rejected-requests (connection-stats conn))))
        (testing "accepted requests still complete once handlers unblock"
          (.countDown release)
          (let [responses (collect-by-id! <-client 4 5000)]
            (is (not= ::timeout responses))
            (when (not= ::timeout responses)
              (is (= #{"r0" "r1" "r2" "r3"} (set (keys responses))))
              (is (every? #(contains? % :result) (vals responses))))))
        (finally
          (.countDown release)
          (protocol/disconnect conn))))))

(deftest test-router-stays-responsive-while-workers-are-blocked
  (testing "the reader thread keeps routing while every worker is blocked"
    (let [{:keys [conn ->client]} (open-connection {:request-handler-threads 1
                                                    :request-handler-queue-size 1})
          release (CountDownLatch. 1)
          entered (atom 0)]
      (try
        (protocol/set-request-handler!
         conn (blocking-handler {:entered entered :release release}))
        (dotimes [i 3]
          (write-framed! ->client (request (str "r" i) "hooks.invoke" {})))
        (is (true? (wait-for #(= 1 @entered) 2000)))
        (testing "notifications are still dispatched"
          (write-framed! ->client {:jsonrpc "2.0" :method "session.event"
                                   :params {:sessionId "s1"}})
          (let [notif (async/alt!! (protocol/notifications conn) ([v] v)
                                   (async/timeout 2000) ([_] ::timeout))]
            (is (not= ::timeout notif))
            (is (= "session.event" (:method notif)))))
        (testing "responses to in-flight client requests still resolve"
          (let [resp-ch (protocol/send-request conn "ping" {})
                id (ffirst (get-in @(:state-atom conn) [:connection :pending-requests]))]
            (is (some? id))
            (write-framed! ->client {:jsonrpc "2.0" :id id :result {:pong true}})
            (let [result (async/alt!! resp-ch ([v] v)
                                      (async/timeout 2000) ([_] ::timeout))]
              (is (not= ::timeout result))
              (is (true? (get-in result [:result :pong]))))))
        (finally
          (.countDown release)
          (protocol/disconnect conn))))))

(deftest test-response-and-error-delivery
  (testing "results, handler errors, exceptions, and missing handlers all answer"
    (let [{:keys [conn ->client <-client]} (open-connection {})]
      (try
        (protocol/set-request-handler!
         conn
         (fn [method _params]
           (case method
             "ok" (delivered {:result {:some-key 1}})
             ;; Opaque SQL column names must survive the wire conversion.
             "sessionFs.sqliteQuery" (delivered {:result {:rows [{:user_id 7}]
                                                          :rows-affected 0}})
             "handler-error" (delivered {:error {:code -32001 :message "nope"}})
             "boom" (throw (ex-info "handler blew up" {}))
             (delivered {:error {:code -32601 :message "Unknown method"}}))))
        (doseq [[id method] [["ok" "ok"]
                             ["sql" "sessionFs.sqliteQuery"]
                             ["err" "handler-error"]
                             ["boom" "boom"]]]
          (write-framed! ->client (request id method {})))
        (let [responses (collect-by-id! <-client 4 5000)]
          (is (not= ::timeout responses))
          (when (not= ::timeout responses)
            (is (= 1 (get-in responses ["ok" :result :someKey]))
                "results are converted to wire shape")
            (is (= [{:user_id 7}] (get-in responses ["sql" :result :rows]))
                "opaque SQL column names are preserved verbatim")
            (is (= 0 (get-in responses ["sql" :result :rowsAffected]))
                "sibling SDK fields are still converted")
            (is (= -32001 (get-in responses ["err" :error :code])))
            (is (= -32603 (get-in responses ["boom" :error :code]))
                "a throwing handler becomes an internal error")
            (is (str/includes? (get-in responses ["boom" :error :message])
                               "handler blew up"))))
        (finally
          (protocol/disconnect conn))))))

(deftest test-missing-handler-answers-method-not-found
  (testing "with no registered handler the peer still gets a response"
    (let [{:keys [conn ->client <-client]} (open-connection {})]
      (try
        (write-framed! ->client (request "r1" "hooks.invoke" {}))
        (let [responses (collect-by-id! <-client 1 3000)]
          (is (not= ::timeout responses))
          (when (not= ::timeout responses)
            (is (= -32601 (get-in responses ["r1" :error :code])))))
        (finally
          (protocol/disconnect conn))))))

(deftest test-disconnect-terminates-blocked-workers
  (testing "disconnect shuts the worker pool down instead of leaking threads"
    (let [{:keys [conn ->client]} (open-connection {:request-handler-threads 2
                                                    :request-handler-queue-size 2})
          release (CountDownLatch. 1)
          entered (atom 0)]
      (try
        (protocol/set-request-handler!
         conn (blocking-handler {:entered entered :release release}))
        (dotimes [i 2]
          (write-framed! ->client (request (str "r" i) "hooks.invoke" {})))
        (is (true? (wait-for #(= 2 @entered) 2000)))
        (let [started (System/currentTimeMillis)]
          (protocol/disconnect conn)
          (is (< (- (System/currentTimeMillis) started) 5000)
              "disconnect must not wait on a wedged handler indefinitely"))
        (is (true? (:request-workers-terminated? (connection-stats conn)))
            "worker pool is terminated after disconnect")
        (is (true? (wait-for #(zero? @entered) 2000))
            "blocked handlers are interrupted and unwind")
        (finally
          (.countDown release))))))

(deftest test-disconnect-completes-when-caller-thread-is-interrupted
  (testing "an interrupted caller still gets a full teardown"
    ;; `awaitTermination` declares InterruptedException; letting it escape would
    ;; skip the channel closes and thread joins that follow it in `disconnect`.
    ;; The handler below swallows the `shutdownNow` interrupt, so the pool is
    ;; guaranteed to still be running when `awaitTermination` is reached.
    (let [{:keys [conn ->client]} (open-connection {:request-handler-threads 1
                                                    :request-handler-queue-size 1})
          release (CountDownLatch. 1)
          entered (atom 0)
          read-channel (:read-channel conn)
          write-channel (:write-channel conn)]
      (try
        (protocol/set-request-handler!
         conn
         (fn [_method _params]
           (swap! entered inc)
           (loop []
             (when-not (try (.await release 10 TimeUnit/SECONDS)
                            (catch InterruptedException _ false))
               (recur)))
           (delivered {:result {}})))
        (write-framed! ->client (request "r1" "hooks.invoke" {}))
        (is (true? (wait-for #(= 1 @entered) 2000)))
        (.interrupt (Thread/currentThread))
        (protocol/disconnect conn)
        (is (false? (.isOpen ^java.nio.channels.ReadableByteChannel read-channel))
            "read channel must still be closed")
        (is (false? (.isOpen ^java.nio.channels.WritableByteChannel write-channel))
            "write channel must still be closed")
        (finally
          ;; Teardown's joins may or may not have consumed the flag; make sure
          ;; it never leaks into a later test.
          (Thread/interrupted)
          (.countDown release))))))

;; -----------------------------------------------------------------------------
;; ASY-003 -- notification-queue overflow is observable
;; -----------------------------------------------------------------------------

(deftest test-notification-overflow-is-counted-and-warned
  (testing "a saturated notification queue records an explicit drop count"
    ;; Stall the dispatcher by filling `incoming-ch`, then overrun a
    ;; deliberately tiny notification queue.
    (let [{:keys [conn ->client]} (open-connection {:notification-queue-size 1})
          incoming (protocol/notifications conn)]
      (try
        (dotimes [i 1024]
          (>!! incoming {:method "filler" :params {:i i}}))
        (is (zero? (:dropped-notifications (connection-stats conn))))
        (dotimes [i 8]
          (write-framed! ->client {:jsonrpc "2.0" :method "session.event"
                                   :params {:sessionId (str "s" i)}}))
        (is (true? (wait-for #(pos? (:dropped-notifications (connection-stats conn)))
                             3000))
            "overflowing the configured queue must be counted, not silent")
        (finally
          (protocol/disconnect conn))))))
