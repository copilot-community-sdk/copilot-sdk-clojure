(ns github.copilot-sdk.helper-lifecycle-test
  (:require [clojure.core.async :as async]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.helpers :as h]
            [github.copilot-sdk.mock-server :as mock]))

(def ^:dynamic *mock-server* nil)

(defn- with-mock-server
  [test-fn]
  (let [server (mock/create-mock-server)
        _ (mock/start-mock-server! server)]
    (stest/unstrument)
    (binding [*mock-server* server]
      (try
        (test-fn)
        (finally
          (stest/unstrument)
          (h/shutdown!)
          (mock/stop-mock-server! server))))))

(use-fixtures :each with-mock-server)

(defn- await-closed
  [ch]
  (loop []
    (let [[value port] (async/alts!! [ch (async/timeout 500)])]
      (cond
        (not= port ch) false
        (nil? value) true
        :else (recur)))))

(defn- cleaned-up?
  [copilot-client]
  (let [{:keys [sessions session-io]} @(:state copilot-client)]
    (and (seq sessions)
         (every? :destroyed? (vals sessions))
         (every? #(await-closed (:event-chan %)) (vals session-io)))))

(defn- connect-helper-to-server!
  []
  (let [copilot-client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams *mock-server*)]
    (client/connect-with-streams! copilot-client in out)
    copilot-client))

(defn- call-with-single-helper-client
  [f]
  (let [copilot-client (connect-helper-to-server!)]
    (try
      (with-redefs-fn {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
                       (fn [_client-opts] copilot-client)}
        #(f copilot-client))
      (finally
        (try (sdk/stop! copilot-client) (catch Exception _))))))

(defmacro with-single-helper-client
  [[client-binding] & body]
  `(call-with-single-helper-client
    (fn [~client-binding]
      ~@body)))

(deftest with-query-seq-compiles-and-runs-from-a-separate-namespace
  (with-single-helper-client [copilot-client]
    (let [events (h/with-query-seq [events "hello"]
                   (doall events))]
      (is (some #(= :copilot/session.idle (:type %)) events))
      (is (cleaned-up? copilot-client)))))

(deftest with-query-seq-cleans-up-when-body-returns-early
  (with-single-helper-client [copilot-client]
    (let [event (h/with-query-seq [events "early"]
                  (first events))]
      (is (map? event))
      (is (cleaned-up? copilot-client)))))

(deftest with-query-seq-cleans-up-after-positive-max-events-body-exit
  (with-single-helper-client [copilot-client]
    (let [events (h/with-query-seq [events "bounded" :max-events 1]
                   (doall events))]
      (is (= 1 (count events)))
      (is (cleaned-up? copilot-client)))))

(deftest with-query-seq-cleans-up-when-body-throws
  (with-single-helper-client [copilot-client]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"body failed"
         (h/with-query-seq [events "boom"]
           (first events)
           (throw (ex-info "body failed" {})))))
    (is (cleaned-up? copilot-client))))

(deftest query-seq-setup-failure-disconnects-created-session-once
  (with-single-helper-client [copilot-client]
    (let [disconnects (atom [])]
      (with-redefs [sdk/send!
                    (fn [_session _opts]
                      (throw (ex-info "send failed" {})))
                    sdk/disconnect!
                    (fn [session-or-client & maybe-session-id]
                      (swap! disconnects conj [session-or-client maybe-session-id])
                      nil)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"send failed"
             (h/query-seq! "setup failure")))
        (is (= 1 (count @disconnects)))))))

(deftest query-seq-source-rejects-invalid-max-events-before-setup
  (let [setup-called? (atom false)]
    (with-redefs-fn {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
                     (fn [_client-opts]
                       (reset! setup-called? true)
                       (throw (ex-info "setup should not run" {})))}
      (fn []
        (doseq [max-events [-1 nil "1"]]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #":max-events must be a non-negative integer"
               (h/with-query-seq [events "invalid" :max-events max-events]
                 (doall events)))))))
    (is (false? @setup-called?))))

(deftest query-seq-natural-terminal-cleanup-is-idempotent
  (with-single-helper-client [copilot-client]
    (let [disconnects (atom 0)]
      (with-redefs [sdk/disconnect!
                    (fn [_session]
                      (swap! disconnects inc)
                      nil)]
        (let [events (doall (h/query-seq! "natural"))]
          (is (some #(= :copilot/session.idle (:type %)) events))
          (is (= 1 @disconnects)))))))

(deftest query-seq-source-finish-is-thread-safe
  (with-single-helper-client [_copilot-client]
    (let [source-var (requiring-resolve 'github.copilot-sdk.helpers/query-seq-source)
          disconnects (atom 0)
          disconnect-entered (promise)
          release-disconnect (promise)]
      (with-redefs [sdk/disconnect!
                    (fn [_session]
                      (deliver disconnect-entered true)
                      @release-disconnect
                      (swap! disconnects inc)
                      nil)]
        (let [[_events finish!] (source-var "concurrent finish")
              first-call (future (finish!))]
          (is (true? (deref disconnect-entered 1000 false)))
          (let [second-call (future (finish!))]
            (is (nil? (deref second-call 1000 ::timeout))))
          (deliver release-disconnect true)
          (is (nil? (deref first-call 1000 ::timeout)))
          (is (= 1 @disconnects)))))))

(deftest max-events-zero-is-valid-under-instrumentation
  (with-single-helper-client [copilot-client]
    (let [instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
          unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)]
      (instrument-all!)
      (try
        (let [setup-called? (atom false)]
          (with-redefs-fn {(requiring-resolve 'github.copilot-sdk.helpers/ensure-client!)
                           (fn [_client-opts]
                             (reset! setup-called? true)
                             copilot-client)}
            #(is (thrown? clojure.lang.ExceptionInfo
                          (h/with-query-seq [events "bad" :max-events -1]
                            (doall events)))))
          (is (false? @setup-called?)))
        (is (empty? (h/query-seq! "none" :max-events 0)))
        (is (empty?
             (h/with-query-seq [events "none" :max-events 0]
               (doall events))))
        (finally
          (unstrument-all!))))))
