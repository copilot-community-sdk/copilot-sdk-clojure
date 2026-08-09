(ns github.copilot-sdk.force-stop-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.session :as session]))

(defn- await-port
  [ch]
  (let [deadline (async/timeout 500)
        [value port] (async/alts!! [ch deadline])]
    {:value value
     :closed? (= port ch)}))

(deftest force-stop-releases-session-owned-resources-without-rpcs
  (let [client (sdk/client {:auto-start? false})
        first-session (session/create-session client "first-session" {})
        second-session (session/create-session client "second-session" {})
        events-ch (session/subscribe-events first-session)
        event-root (get-in @(:state client) [:session-io "first-session" :event-chan])
        send-lock (get-in @(:state client) [:session-io "second-session" :send-lock])
        cancelled? (atom false)
        cancel-ch (async/chan)
        lock-waiter (promise)
        send-started (promise)
        rpc-methods (atom [])]
    (swap! (:state client)
           assoc-in
           [:sessions "first-session" :factory-executions "run-1" "execution-1"]
           {:cancelled? cancelled?
            :cancel-chan cancel-ch})
    (async/<!! send-lock)
    (async/take! send-lock #(deliver lock-waiter [:released %]))
    (with-redefs [protocol/send-request!
                  (fn [_ method _ & _]
                    (swap! rpc-methods conj method)
                    (deliver send-started true)
                    {:message-id "message-1"})]
      (let [in-flight-send
            (future
              (try
                (session/send-and-wait! first-session {:prompt "wait"} 30000)
                :completed
                (catch Exception error
                  [:failed (ex-message error)])))]
        @send-started
        (sdk/force-stop! client)
        (try
          (testing "event subscriptions and in-flight sends are released"
            (is (:closed? (await-port events-ch)))
            (let [result (deref in-flight-send 500 ::pending)]
              (is (not= ::pending result))
              (is (and (vector? result)
                       (re-find #"Event channel closed" (second result)))))
            (is (= [:released nil] (deref lock-waiter 500 ::pending))))
          (testing "local session teardown cancels factory execution and rejects handles"
            (is @cancelled?)
            (is (:closed? (await-port cancel-ch)))
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Session has been disconnected"
                 (session/send! first-session {:prompt "after force-stop"}))))
          (testing "force stop remains local-only and idempotent"
            (is (not-any? #{"session.destroy" "runtime.shutdown"} @rpc-methods))
            (is (empty? (:sessions @(:state client))))
            (is (empty? (:session-io @(:state client))))
            (is (nil? (sdk/force-stop! client))))
          (finally
            (async/close! event-root)
            (async/close! send-lock)
            (deref in-flight-send 1000 nil)))))))

(deftest force-stop-prevents-factory-registration-after-session-teardown
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "factory-session" {})]
    (sdk/force-stop! client)
    (is (nil? (#'session/register-factory-execution!
               client
               (sdk/session-id copilot-session)
               "run-1"
               "execution-1")))
    (is (empty? (:sessions @(:state client))))))

(deftest force-stop-clears-lifecycle-handlers-before-transport-teardown
  (let [client (sdk/client {:auto-start? false})
        handlers-at-disconnect (atom nil)]
    (swap! (:state client)
           assoc
           :connection-io :connection
           :lifecycle-handlers {:handler {:handler identity}})
    (with-redefs [protocol/disconnect
                  (fn [_]
                    (reset! handlers-at-disconnect
                            (:lifecycle-handlers @(:state client))))]
      (sdk/force-stop! client))
    (is (= {} @handlers-at-disconnect))))

(deftest disconnect-untracked-session-still-notifies-the-runtime
  (let [client (sdk/client {:auto-start? false})
        rpc-calls (atom [])]
    (with-redefs [protocol/send-request!
                  (fn [_ method params & _]
                    (swap! rpc-calls conj [method params]))]
      (is (nil? (session/disconnect! client "runtime-only-session"))))
    (is (= [["session.destroy" {:session-id "runtime-only-session"}]]
           @rpc-calls))
    (is (empty? (:sessions @(:state client))))))

(deftest local-teardown-does-not-recreate-a-session-after-claim
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "race-session" {})
        events-ch (session/subscribe-events copilot-session)
        original-swap-vals! swap-vals!
        claimed? (atom false)]
    (with-redefs [clojure.core/swap-vals!
                  (fn [state transition]
                    (let [[old new] (original-swap-vals! state transition)]
                      (if (compare-and-set! claimed? false true)
                        (do
                          (reset! state (assoc new :sessions {} :session-io {}))
                          [old new])
                        [old new])))]
      (is (= :claimed
             (session/teardown-local! client (sdk/session-id copilot-session)))))
    (is (:closed? (await-port events-ch)))
    (is (empty? (:sessions @(:state client))))
    (is (empty? (:session-io @(:state client))))))

(deftest session-state-writers-do-not-resurrect-a-force-stopped-session
  (let [client (sdk/client {:auto-start? false})
        copilot-session (session/create-session client "notification-session" {})
        session-id (sdk/session-id copilot-session)]
    (sdk/force-stop! client)
    (session/update-capabilities! client session-id {:supports-canvases true})
    (session/upsert-open-canvas!
     client
     session-id
     {:instance-id "canvas-1" :extension-id "extension-1" :canvas-id "canvas-1"})
    (is (empty? (:sessions @(:state client))))
    (is (empty? (:session-io @(:state client))))))

(deftest session-registration-rejects-a-stopping-client
  (let [client (sdk/client {:auto-start? false})]
    (sdk/force-stop! client)
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Client is stopping"
         (session/create-session client "late-session" {})))
    (is (empty? (:sessions @(:state client))))
    (is (empty? (:session-io @(:state client))))))
