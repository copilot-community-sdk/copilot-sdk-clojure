(ns github.copilot-sdk.teardown-test
  "Logging, interrupt, and error-preserving cleanup on native transport teardown."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as ctl]
            [clojure.tools.logging.impl :as impl]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.teardown :as teardown])
  (:import [java.io IOException]
           [java.nio.channels ClosedChannelException ReadableByteChannel WritableByteChannel]))

(deftest throwable-reaches-the-logging-backend-as-a-throwable
  (testing "a leading Throwable is handed to the backend as a throwable, not
            stringified into the message"
    (let [boom (ex-info "boom" {:resource :read-channel})]
      (log-test/with-log
        (log/error boom "failed to close read channel")
        (let [entries (vec (log-test/the-log))]
          (is (= 1 (count entries)))
          (let [entry (first entries)]
            (is (identical? boom (:throwable entry))
                "the backend must receive the Throwable itself")
            (is (= "failed to close read channel" (:message entry))
                "the message must not absorb the Throwable")))))))

(deftest non-throwable-logging-is-unchanged
  (testing "messages without a leading Throwable keep their existing rendering
            and stay attributed to the calling namespace"
    (log-test/with-log
      (log/debug "Writing message: " "id=7")
      (let [entry (first (log-test/the-log))]
        (is (nil? (:throwable entry)))
        (is (= "Writing message: id=7" (:message entry)))
        (is (= (find-ns 'github.copilot-sdk.teardown-test) (:logger-ns entry))
            "the log event must be attributed to the caller, not the facade")))))

(defn- disabled-logger-factory
  "A real LoggerFactory whose loggers report every level as disabled."
  []
  (let [logger (reify impl/Logger
                 (enabled? [_ _level] false)
                 (write! [_ _level _throwable _message] nil))]
    (reify impl/LoggerFactory
      (name [_] "disabled-test-factory")
      (get-logger [_ _logger-ns] logger))))

(deftest disabled-logging-evaluates-no-arguments
  (testing "a disabled level evaluates neither the leading nor the trailing
            arguments, so expensive arguments cost nothing"
    (let [leading-calls (atom 0)
          trailing-calls (atom 0)]
      (binding [ctl/*logger-factory* (disabled-logger-factory)]
        (log/debug (do (swap! leading-calls inc) "leading")
                   (do (swap! trailing-calls inc) "trailing")))
      (is (zero? @leading-calls) "the leading argument must stay unevaluated")
      (is (zero? @trailing-calls) "trailing arguments must stay unevaluated"))))

(deftest enabled-logging-evaluates-each-argument-exactly-once
  (testing "an enabled level evaluates every supplied argument exactly once"
    (let [leading-calls (atom 0)
          trailing-calls (atom 0)]
      (log-test/with-log
        (log/warn (do (swap! leading-calls inc) "leading ")
                  (do (swap! trailing-calls inc) "trailing"))
        (is (= "leading trailing" (:message (first (log-test/the-log))))))
      (is (= 1 @leading-calls) "the leading argument must be evaluated once")
      (is (= 1 @trailing-calls) "trailing arguments must be evaluated once"))))

(deftest throwable-argument-is-evaluated-exactly-once
  (testing "a leading Throwable expression is evaluated once and still reaches
            the backend as a throwable"
    (let [boom (ex-info "boom" {})
          calls (atom 0)]
      (log-test/with-log
        (log/error (do (swap! calls inc) boom) "cleanup failed")
        (let [entry (first (log-test/the-log))]
          (is (identical? boom (:throwable entry)))
          (is (= "cleanup failed" (:message entry)))
          (is (= (find-ns 'github.copilot-sdk.teardown-test) (:logger-ns entry)))))
      (is (= 1 @calls) "the Throwable argument must be evaluated once"))))

(deftest cleanup-failure-is-suppressed-and-logged
  (let [primary (ex-info "primary failure" {:phase :body})
        cleanup (ex-info "cleanup failure" {:phase :cleanup})]
    (log-test/with-log
      (is (identical?
           primary
           (teardown/cleanup-preserving!
            primary
            #(throw cleanup))))
      (is (= [cleanup] (vec (.getSuppressed primary))))
      (let [entry (first (log-test/the-log))]
        (is (identical? cleanup (:throwable entry)))
        (is (= "Cleanup failed while preserving the primary failure"
               (:message entry)))))))

(deftest cleanup-preserves-preexisting-interrupt-status
  (let [cleaned? (atom false)
        thread (Thread/currentThread)]
    (.interrupt thread)
    (try
      (let [result (teardown/cleanup-preserving!
                    nil
                    #(reset! cleaned? true))
            interrupted? (.isInterrupted thread)]
        (Thread/interrupted)
        (is (true? result))
        (is interrupted?))
      (is @cleaned?)
      (finally
        (Thread/interrupted)))))

(deftest primary-interruption-remains-primary-and-restores-interrupt-status
  (let [primary (InterruptedException. "body interrupted")
        cleaned? (atom false)
        caught (try
                 (teardown/with-cleanup #(reset! cleaned? true) (throw primary))
                 nil
                 (catch Throwable failure
                   failure))
        interrupted? (.isInterrupted (Thread/currentThread))]
    (try
      (Thread/interrupted)
      (is (identical? primary caught))
      (is @cleaned?)
      (is interrupted?)
      (finally
        (Thread/interrupted)))))

(deftest cleanup-interruption-is-suppressed-under-a-primary-failure
  (let [primary (ex-info "primary" {})
        cleanup (InterruptedException. "cleanup interrupted")]
    (try
      (let [caught (try
                     (teardown/with-cleanup #(throw cleanup) (throw primary))
                     nil
                     (catch Throwable failure
                       failure))
            interrupted? (.isInterrupted (Thread/currentThread))]
        (Thread/interrupted)
        (is (identical? primary caught))
        (is (= [cleanup] (vec (.getSuppressed primary))))
        (is interrupted?))
      (finally
        (Thread/interrupted)))))

(deftest cleanup-only-failure-is-rethrown
  (let [cleanup (ex-info "cleanup only" {})
        caught (try
                 (teardown/with-cleanup #(throw cleanup) :ok)
                 nil
                 (catch Throwable failure
                   failure))]
    (is (identical? cleanup caught))))

;; -----------------------------------------------------------------------------
;; IDI-002 - expected vs unexpected teardown outcomes
;; -----------------------------------------------------------------------------

(defn- recording-read-channel
  [closed on-close]
  (reify ReadableByteChannel
    (read [_ _buf] -1)
    (isOpen [_] true)
    (close [_]
      (swap! closed conj :read-channel)
      (when on-close (throw (on-close))))))

(defn- recording-write-channel
  [closed]
  (reify WritableByteChannel
    (write [_ buf] (.remaining buf))
    (isOpen [_] true)
    (close [_] (swap! closed conj :write-channel))))

(defn- fault-injecting-conn
  "Build a Connection whose only live resources are the two NIO channels, so a
   teardown failure can be injected without a real peer."
  [closed on-read-close]
  (protocol/map->Connection
   {:read-channel (recording-read-channel closed on-read-close)
    :write-channel (recording-write-channel closed)
    :state-atom (atom {:connection (protocol/initial-connection-state)})
    :notification-queue (java.util.concurrent.LinkedBlockingQueue. 1)
    :incoming-ch (async/chan 1)
    :outgoing-ch (async/chan 1)}))

(deftest expected-close-failure-stays-quiet
  (testing "an already-closed channel is a normal teardown outcome"
    (let [closed (atom [])
          conn (fault-injecting-conn closed #(ClosedChannelException.))]
      (log-test/with-log
        (let [failures (protocol/disconnect conn)]
          (is (= [] failures) "an expected close must not be reported")
          (is (empty? (filter (comp #{:warn :error} :level) (log-test/the-log)))
              "an expected close must not be logged above debug"))))))

(deftest unexpected-close-failure-is-reported-with-resource-identity
  (testing "a close that genuinely fails is reported, and teardown still
            completes the steps that follow it"
    (let [closed (atom [])
          conn (fault-injecting-conn closed #(IOException. "boom"))
          failures (protocol/disconnect conn)]
      (is (= 1 (count failures)))
      (let [failure (first failures)]
        (is (= {:operation :close :resource :read-channel}
               (select-keys (ex-data failure) [:operation :resource])))
        (is (= "boom" (ex-message (ex-cause failure)))))
      (is (= [:read-channel :write-channel] @closed)
          "a failed step must not short-circuit the remaining teardown"))))

(deftest disconnect-is-idempotent
  (testing "a second disconnect reports nothing new"
    (let [closed (atom [])
          conn (fault-injecting-conn closed nil)]
      (is (= [] (protocol/disconnect conn)))
      (is (= [] (protocol/disconnect conn))))))
