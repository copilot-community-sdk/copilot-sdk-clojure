(ns github.copilot-sdk.integration.client-test
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

(deftest test-client-connection
  (testing "Client connects to mock server"
    (is (= :connected (sdk/state *test-client*)))
    (is (some? (:connection @(:state *test-client*))))))

(deftest test-auto-restart-deprecated-connection-close
  (testing "auto-restart no longer triggers on connection close (deprecated)"
    (let [starts (atom 0)
          stops (atom 0)
          real-maybe-reconnect (var-get (var client/maybe-reconnect!))
          reconnect-observed (promise)]
      (log/info "Warnings expected in this test: connection close no longer triggers auto-restart.")
      (with-redefs-fn
        {(var client/maybe-reconnect!)
         (fn [c reason]
           (let [result (real-maybe-reconnect c reason)]
             (deliver reconnect-observed reason)
             result))}
        #(with-redefs [client/stop! (fn [c]
                                      (swap! stops inc)
                                      (swap! (:state c) assoc :status :disconnected)
                                      [])
                       client/start! (fn [c]
                                       (swap! starts inc)
                                       (swap! (:state c) assoc :status :connected)
                                       nil)]
           (mock/stop-mock-server! *mock-server*)
           (await-value! reconnect-observed "connection-close handling" 1000)
           (is (zero? @stops) "auto-restart is deprecated; stop! should not be called")
           (is (zero? @starts) "auto-restart is deprecated; start! should not be called"))))))

(deftest test-auto-restart-deprecated-process-exit
  (testing "auto-restart no longer triggers on process exit (deprecated)"
    (let [starts (atom 0)
          stops (atom 0)
          exit-ch (chan 1)
          real-maybe-reconnect (var-get (var client/maybe-reconnect!))
          reconnect-observed (promise)
          watch-exit (var client/watch-process-exit!)]
      (log/info "Warnings expected in this test: simulated process exit no longer triggers auto-restart.")
      (with-redefs-fn
        {(var client/maybe-reconnect!)
         (fn [c reason]
           (let [result (real-maybe-reconnect c reason)]
             (deliver reconnect-observed reason)
             result))}
        #(with-redefs [client/stop! (fn [c]
                                      (swap! stops inc)
                                      (swap! (:state c) assoc :status :disconnected)
                                      [])
                       client/start! (fn [c]
                                       (swap! starts inc)
                                       (swap! (:state c) assoc :status :connected)
                                       nil)]
           (watch-exit *test-client* {:exit-chan exit-ch})
           (>!! exit-ch {:exit-code 123})
           (close! exit-ch)
           (await-value! reconnect-observed "process-exit handling" 1000)
           (is (zero? @stops) "auto-restart is deprecated; stop! should not be called")
           (is (zero? @starts) "auto-restart is deprecated; start! should not be called"))))))

(deftest test-auto-restart-suppressed-when-stopping
  (testing "auto-restart is suppressed while stopping"
    (let [starts (atom 0)
          stops (atom 0)
          real-maybe-reconnect (var-get (var client/maybe-reconnect!))
          reconnect-observed (promise)]
      (swap! (:state *test-client*) assoc :stopping? true)
      (try
        (with-redefs-fn
          {(var client/maybe-reconnect!)
           (fn [c reason]
             (let [result (real-maybe-reconnect c reason)]
               (deliver reconnect-observed reason)
               result))}
          #(with-redefs [client/stop! (fn [_] (swap! stops inc) [])
                         client/start! (fn [_] (swap! starts inc) nil)]
             (mock/stop-mock-server! *mock-server*)
             (await-value! reconnect-observed "stopping connection-close handling" 1000)
             (is (zero? @stops))
             (is (zero? @starts))))
        (finally
          (swap! (:state *test-client*) assoc :stopping? false))))))

(deftest test-stderr-capture-and-forwarding
  (testing "start-stderr-forwarder! captures stderr lines"
    (let [stderr-content "error line 1\nerror line 2\nwarning: something\n"
          stderr-stream (java.io.ByteArrayInputStream.
                         (.getBytes stderr-content "UTF-8"))
          exit-ch (chan 1)
          fake-mp (github.copilot-sdk.process/map->ManagedProcess
                   {:process nil :stdin nil :stdout nil
                    :stderr stderr-stream :exit-chan exit-ch})
          start-forwarder (var client/start-stderr-forwarder!)
          get-stderr (var client/get-stderr-output)
          client (sdk/client {:auto-start? false})]
      (let [stderr-buffer (start-forwarder client fake-mp)]
        (await-atom! stderr-buffer #(= 3 (count %)) "stderr drain" 1000))
      (let [output (get-stderr client)]
        (is (some? output) "stderr output should be captured")
        (is (clojure.string/includes? output "error line 1"))
        (is (clojure.string/includes? output "error line 2"))
        (is (clojure.string/includes? output "warning: something")))
      ;; Verify buffer atom contains individual lines
      (let [buf @(:stderr-buffer @(:state client))]
        (is (= 3 (count buf)))
        (is (= "error line 1" (first buf))))))

  (testing "get-stderr-output returns nil when no stderr captured"
    (let [client (sdk/client {:auto-start? false})
          get-stderr (var client/get-stderr-output)]
      (is (nil? (get-stderr client))))))

(deftest test-early-process-exit-detected-during-startup
  (testing "verify-protocol-version! detects early process exit with stderr"
    (let [exit-ch (chan 1)
          ;; Inject a fake process and pre-populated stderr buffer
          _ (swap! (:state *test-client*) assoc
                   :process {:exit-chan exit-ch}
                   :stderr-buffer (atom ["fatal: config file not found"
                                         "copilot: exiting"]))
          ;; Signal process exit before the ping can complete
          _ (>!! exit-ch {:exit-code 1})
          _ (close! exit-ch)
          verify-version (var client/verify-protocol-version!)]
      (try
        (verify-version *test-client*)
        (is false "Should have thrown on early process exit")
        (catch clojure.lang.ExceptionInfo e
          (is (clojure.string/includes? (ex-message e) "CLI server exited with code 1"))
          (is (clojure.string/includes? (ex-message e) "fatal: config file not found"))
          (is (= 1 (:exit-code (ex-data e))))
          (is (some? (:stderr (ex-data e)))))))))

(deftest test-ping
  (testing "Ping returns protocol version"
    (let [result (sdk/ping *test-client*)]
      (is (= 3 (:protocol-version result)))
      ;; Upstream PR #1340 / CLI 1.0.51 changed timestamp from epoch number
      ;; to ISO 8601 string (`timestamp: string, format: date-time`).
      (is (string? (:timestamp result)))
      (is (some? (java.time.Instant/parse (:timestamp result)))
          ":timestamp parses as ISO 8601 instant")))
  (testing "::specs/timestamp accepts both ISO string (CLI ≥ 1.0.51) and epoch-millis number (older CLIs)"
    (is (s/valid? :github.copilot-sdk.specs/timestamp "2026-05-21T08:00:00.000Z"))
    (is (s/valid? :github.copilot-sdk.specs/timestamp (System/currentTimeMillis))
        "System/currentTimeMillis-sized long validates as epoch-ms")
    (is (s/valid? :github.copilot-sdk.specs/timestamp 1700000000000)
        "representative epoch-ms long validates")
    (is (not (s/valid? :github.copilot-sdk.specs/timestamp -1))
        "epoch-ms must be non-negative")
    (is (not (s/valid? :github.copilot-sdk.specs/timestamp 1.5))
        "epoch-ms must be an integer, not arbitrary number")))

(deftest test-get-status
  (testing "Get CLI status returns version and protocol"
    (let [result (sdk/get-status *test-client*)]
      (is (string? (:version result)))
      (is (= 3 (:protocol-version result))))))

(deftest test-get-auth-status
  (testing "Get auth status returns authentication info"
    (let [result (sdk/get-auth-status *test-client*)]
      (is (boolean? (:authenticated? result)))
      (when (:authenticated? result)
        (is (keyword? (:auth-type result)))
        (is (string? (:login result)))))))

(deftest test-list-models
  (testing "List models returns available models"
    (let [models (sdk/list-models *test-client*)]
      (is (vector? models))
      (is (pos? (count models)))
      (let [model (first models)]
        (is (string? (:id model)))
        (is (string? (:name model)))
        (is (string? (:vendor model)))
        (is (number? (:max-input-tokens model)))
        (is (number? (:max-output-tokens model)))))))

(deftest test-list-models-with-on-list-models-handler
  (let [call-count (atom 0)
        fake-models [{:id "test-model" :name "Test Model" :vendor "test"
                      :family "test" :version "1" :max-input-tokens 4096
                      :max-output-tokens 1024 :preview? false}]
        handler (fn []
                  (swap! call-count inc)
                  fake-models)
        c (sdk/client {:auto-start? false :on-list-models handler})]
    (testing "returns handler result without requiring start!"
      (let [models (sdk/list-models c)]
        (is (vector? models))
        (is (= 1 (count models)))
        (is (= "test-model" (:id (first models))))))
    (testing "caches result (handler called only once)"
      (let [_m1 (sdk/list-models c)
            _m2 (sdk/list-models c)]
        (is (= 1 @call-count))))))

(deftest test-list-models-uses-canonical-model-capabilities-shape
  (mock/set-request-hook!
   *mock-server*
   (fn [method _params]
     (when (= "models.list" method)
       {:github.copilot-sdk.mock-server/merge-response
        {:models [{:id "capable-model"
                   :name "Capable Model"
                   :capabilities
                   {:supports {:vision true
                               :reasoningEffort false
                               :adaptive_thinking "required"}
                    :limits {:max_prompt_tokens 120000
                             :max_output_tokens 16000
                             :max_context_window_tokens 136000
                             :vision {:supported_media_types ["image/png"]
                                      :max_prompt_images 5
                                      :max_prompt_image_size 1048576}}}}]}})))
  (let [capabilities (:model-capabilities (first (sdk/list-models *test-client*)))]
    (is (= {:vision true
            :reasoning-effort false
            :adaptive-thinking :required}
           (:supports capabilities)))
    (is (= {:max-prompt-tokens 120000
            :max-output-tokens 16000
            :max-context-window-tokens 136000
            :vision {:supported-media-types ["image/png"]
                     :max-prompt-images 5
                     :max-prompt-image-size 1048576}}
           (:limits capabilities)))
    (is (not (contains? capabilities :model-supports)))
    (is (not (contains? capabilities :model-limits)))))
