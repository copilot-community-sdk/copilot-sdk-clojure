(ns github.copilot-sdk.integration.shutdown-test
  "Focused integration tests using the mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.tools :as tools]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support
             :refer [*mock-server*
                     *test-client*
                     await-value!
                     await-atom!
                     await-event-type!
                     observe-take-attempts
                     with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]))

(use-fixtures :each with-mock-server)

(deftest test-graceful-runtime-shutdown
  (testing "stop! sends runtime.shutdown for SDK-owned (non-external) process"
    (let [seen (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (swap! seen conj method)))]
      ;; Inject a placeholder process so the SDK-owned guard fires. A bare map
      ;; has no :process key, so proc/destroy! is a no-op.
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (sdk/stop! *test-client*)
      (is (some #{"runtime.shutdown"} @seen)
          "runtime.shutdown RPC is sent during graceful stop")))

  (testing "stop! does NOT send runtime.shutdown when no process is owned"
    (let [seen (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method _params]
                                      (swap! seen conj method)))]
      ;; No :process injected — fixture connect-with-streams! spawns nothing.
      (sdk/stop! *test-client*)
      (is (not (some #{"runtime.shutdown"} @seen))
          "runtime.shutdown is skipped when the client does not own a process"))))

(deftest graceful-shutdown-waits-for-natural-exit
  (testing "stop! waits for the child to exit on its own after a successful
            runtime.shutdown and skips the kill when it does"
    (let [waited? (atom false)
          killed? (atom false)]
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (with-redefs [proc/wait-for-exit! (fn [_ _] (reset! waited? true) true)
                    proc/destroy! (fn [_] (reset! killed? true) [])]
        (sdk/stop! *test-client*))
      (is @waited? "stop! waits for natural exit after runtime.shutdown succeeds")
      (is (not @killed?)
          "stop! does not SIGTERM the child when it exits gracefully")))

  (testing "stop! force-kills the child when it does not exit within the window"
    (let [killed? (atom false)]
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (with-redefs [proc/wait-for-exit! (fn [_ _] false)
                    proc/destroy! (fn [_] (reset! killed? true) [])]
        (sdk/stop! *test-client*))
      (is @killed?
          "stop! kills the child if it does not exit within the graceful window")))

  (testing "stop! kills immediately (no graceful wait) when runtime.shutdown fails"
    (let [waited? (atom false)
          killed? (atom false)]
      (swap! (:state *test-client*) assoc :process {:placeholder true})
      (with-redefs [protocol/send-request!
                    (fn [_conn method _params & _]
                      (when (= method "runtime.shutdown")
                        (throw (ex-info "boom" {})))
                      nil)
                    proc/wait-for-exit! (fn [_ _] (reset! waited? true) true)
                    proc/destroy! (fn [_] (reset! killed? true) [])]
        (sdk/stop! *test-client*))
      (is (not @waited?)
          "stop! does not wait for natural exit when runtime.shutdown failed")
      (is @killed?
          "stop! force-kills the child immediately when runtime.shutdown failed"))))
