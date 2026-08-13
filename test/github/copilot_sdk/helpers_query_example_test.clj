(ns github.copilot-sdk.helpers-query-example-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(load-file "examples/helpers_query.clj")

(defn- example-var
  [sym]
  (ns-resolve 'helpers-query sym))

(deftest streaming-timeout-force-stops-its-owned-client
  (let [calls (atom [])
        client-calls (atom 0)
        events-ch (async/chan)
        state (atom {:status :disconnected
                     :sessions {}
                     :process nil
                     :connection-io nil})
        client {:state state}
        outcome
        (with-redefs [sdk/client (fn [_opts]
                                   (swap! client-calls inc)
                                   client)
                      sdk/start! (fn [_client]
                                   (swap! calls conj :start)
                                   (swap! state assoc :status :connected))
                      sdk/create-session (fn [actual-client _config]
                                           (is (identical? client actual-client))
                                           (swap! calls conj :create-session)
                                           (swap! state assoc-in [:sessions "session"] {})
                                           ::session)
                      sdk/subscribe-events (fn [_session]
                                             (swap! calls conj :subscribe)
                                             events-ch)
                      sdk/send! (fn [_session _message]
                                  (swap! calls conj :send))
                      sdk/force-stop! (fn [actual-client]
                                        (is (identical? client actual-client))
                                        (swap! calls conj :force-stop)
                                        (async/close! events-ch)
                                        (reset! state {:status :disconnected
                                                       :sessions {}
                                                       :process nil
                                                       :connection-io nil}))
                      sdk/disconnect! (fn [_session]
                                        (swap! calls conj :worker-finally)
                                        (swap! state assoc :sessions {}))
                      sdk/stop! (fn [actual-client]
                                  (is (identical? client actual-client))
                                  (swap! calls conj :owner-finally)
                                  (swap! state assoc :status :disconnected)
                                  [])]
          (try
            ((example-var 'run-streaming) {:timeout-ms 10})
            (catch clojure.lang.ExceptionInfo e
              e)))]
    (is (= :example-timeout (:type (ex-data outcome))))
    (is (= 1 @client-calls))
    (is (= [:start :create-session :subscribe :send
            :force-stop :worker-finally :owner-finally]
           @calls))
    (is (true? (async-protocols/closed? events-ch)))
    (is (= {:status :disconnected
            :sessions {}
            :process nil
            :connection-io nil}
           @state))))

(deftest cancellation-failure-still-interrupts-and-confirms-cleanup
  (let [run-bounded! (deref (example-var 'run-bounded!))
        order (atom [])
        latch (CountDownLatch. 1)
        cancel-error (ex-info "cancel failed" {})
        outcome
        (try
          (run-bounded! "controlled" 10
                        #(do
                           (swap! order conj :cancel)
                           (throw cancel-error))
                        #(try
                           (.await latch)
                           (finally
                             (swap! order conj :worker-finally))))
          (catch clojure.lang.ExceptionInfo e
            e))]
    (is (= :example-cancel-failed (:type (ex-data outcome))))
    (is (identical? cancel-error (ex-cause outcome)))
    (is (= [:cancel :worker-finally] @order))))

(deftest incomplete-cleanup-is-loud
  (let [run-bounded! (deref (example-var 'run-bounded!))
        cleanup-timeout-var (example-var 'cleanup-timeout-ms)
        latch (CountDownLatch. 1)
        finished (promise)
        cancel-error (ex-info "cancel failed" {})
        outcome
        (with-redefs-fn
          {cleanup-timeout-var 25}
          #(try
             (run-bounded!
              "controlled"
              10
              (fn [] (throw cancel-error))
              (fn []
                (try
                  (loop []
                    (let [released?
                          (try
                            (.await latch 10 TimeUnit/MILLISECONDS)
                            (catch InterruptedException _
                              false))]
                      (when-not released?
                        (recur))))
                  (finally
                    (deliver finished true)))))
             (catch clojure.lang.ExceptionInfo e
               e)))]
    (is (= :example-cleanup-timeout (:type (ex-data outcome))))
    (is (identical? cancel-error (ex-cause outcome)))
    (.countDown latch)
    (is (true? (deref finished 1000 false)))))
