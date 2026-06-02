(ns github.copilot-sdk.process-test
  "Unit tests for github.copilot-sdk.process — focused on the pure helpers
   that compute the env-var contract for the spawned CLI process. We test
   the helper directly rather than spawning a real process."
  (:require [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.specs :as specs]
            [clojure.spec.alpha :as s]))

(deftest cli-env-overrides-defaults
  (testing "by default only NODE_DEBUG is in :defaults (removed; user :env can re-add)"
    (let [{:keys [defaults overrides]} (proc/cli-env-overrides {})]
      (is (contains? defaults "NODE_DEBUG"))
      (is (nil? (get defaults "NODE_DEBUG"))
          "NODE_DEBUG must be a default removal (nil value)")
      (is (= 1 (count defaults)))
      (is (= {} overrides) "no overrides without options"))))

(deftest cli-env-overrides-github-token
  (testing ":github-token sets COPILOT_SDK_AUTH_TOKEN as a strict override (PR #237)"
    (let [{:keys [overrides]} (proc/cli-env-overrides {:github-token "tok-1"})]
      (is (= "tok-1" (get overrides "COPILOT_SDK_AUTH_TOKEN"))))))

(deftest cli-env-overrides-copilot-home
  (testing ":copilot-home sets COPILOT_HOME as a strict override (upstream PR #1191)"
    (is (= "/tmp/my-home"
           (get-in (proc/cli-env-overrides {:copilot-home "/tmp/my-home"})
                   [:overrides "COPILOT_HOME"]))))
  (testing "no override when :copilot-home is absent"
    (is (not (contains? (:overrides (proc/cli-env-overrides {}))
                        "COPILOT_HOME")))))

(deftest cli-env-overrides-tcp-connection-token
  (testing ":tcp-connection-token sets COPILOT_CONNECTION_TOKEN as a strict override (upstream PR #1176)"
    (is (= "abc-123"
           (get-in (proc/cli-env-overrides {:tcp-connection-token "abc-123"})
                   [:overrides "COPILOT_CONNECTION_TOKEN"]))))
  (testing "no override when :tcp-connection-token is absent"
    (is (not (contains? (:overrides (proc/cli-env-overrides {}))
                        "COPILOT_CONNECTION_TOKEN")))))

(deftest cli-env-overrides-telemetry
  (testing "telemetry options map onto OTEL_* / COPILOT_OTEL_* override vars (PR #785)"
    (let [{:keys [overrides]} (proc/cli-env-overrides
                               {:telemetry {:otlp-endpoint "http://localhost:4318"
                                            :file-path "/tmp/otel.json"
                                            :exporter-type "otlp"
                                            :source-name "my-app"
                                            :capture-content? true}})]
      (is (= "true" (get overrides "COPILOT_OTEL_ENABLED")))
      (is (= "http://localhost:4318" (get overrides "OTEL_EXPORTER_OTLP_ENDPOINT")))
      (is (= "/tmp/otel.json" (get overrides "COPILOT_OTEL_FILE_EXPORTER_PATH")))
      (is (= "otlp" (get overrides "COPILOT_OTEL_EXPORTER_TYPE")))
      (is (= "my-app" (get overrides "COPILOT_OTEL_SOURCE_NAME")))
      (is (= "true" (get overrides "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"))))))

(deftest cli-env-defaults-can-be-overridden-by-user-env
  (testing "NODE_DEBUG is a default — user :env should be able to re-enable it"
    ;; This locks in the precedence contract: defaults are applied BEFORE user :env
    ;; in spawn-cli, so a user-provided NODE_DEBUG value survives.
    (let [{:keys [defaults overrides]} (proc/cli-env-overrides {})]
      (is (contains? defaults "NODE_DEBUG"))
      (is (not (contains? overrides "NODE_DEBUG"))
          "NODE_DEBUG must NOT be a strict override — user :env wins"))))

;; -----------------------------------------------------------------------------
;; Spec coverage — make sure the new option keys are part of ::client-options.
;; -----------------------------------------------------------------------------

(deftest copilot-home-accepted-by-client-options-spec
  (is (s/valid? ::specs/client-options {:copilot-home "/tmp/x"}))
  (testing "must be non-blank"
    (is (not (s/valid? ::specs/client-options {:copilot-home ""})))
    (is (not (s/valid? ::specs/client-options {:copilot-home "   "})))))

(deftest tcp-connection-token-accepted-by-client-options-spec
  (is (s/valid? ::specs/client-options {:tcp-connection-token "abc"}))
  (testing "must be non-blank"
    (is (not (s/valid? ::specs/client-options {:tcp-connection-token ""})))
    (is (not (s/valid? ::specs/client-options {:tcp-connection-token "   "})))))

(deftest remote-accepted-by-client-options-spec
  (testing ":remote? accepted by ::client-options (upstream PR #1192)"
    (is (s/valid? ::specs/client-options {:remote? true}))
    (is (s/valid? ::specs/client-options {:remote? false}))
    (testing "must be boolean"
      (is (not (s/valid? ::specs/client-options {:remote? "yes"}))))))

(deftest build-cli-args-remote-flag
  (testing ":remote? true appends --remote to the spawned CLI args (upstream PR #1192)"
    (let [build-cli-args @#'proc/build-cli-args
          args (build-cli-args {:use-stdio? true :remote? true})]
      (is (some #{"--remote"} args)
          "--remote must be present when :remote? is true")))
  (testing ":remote? false (or unset) does NOT append --remote"
    (let [build-cli-args @#'proc/build-cli-args]
      (is (not (some #{"--remote"} (build-cli-args {:use-stdio? true})))
          "--remote must NOT be present by default")
      (is (not (some #{"--remote"} (build-cli-args {:use-stdio? true :remote? false})))
          "--remote must NOT be present when :remote? is explicitly false"))))

;; -----------------------------------------------------------------------------
;; Client mode (upstream PR #1428)
;; -----------------------------------------------------------------------------

(deftest cli-env-overrides-empty-mode-disables-keytar
  (testing ":mode :empty sets COPILOT_DISABLE_KEYTAR=1 as a strict override"
    (let [{:keys [overrides]} (proc/cli-env-overrides {:mode :empty})]
      (is (= "1" (get overrides "COPILOT_DISABLE_KEYTAR"))
          "COPILOT_DISABLE_KEYTAR must be 1 in :empty mode")))
  (testing ":mode :copilot-cli does NOT set COPILOT_DISABLE_KEYTAR"
    (let [{:keys [overrides]} (proc/cli-env-overrides {:mode :copilot-cli})]
      (is (not (contains? overrides "COPILOT_DISABLE_KEYTAR"))
          "KEYTAR override must be absent in :copilot-cli mode")))
  (testing "absent :mode does NOT set COPILOT_DISABLE_KEYTAR"
    (let [{:keys [overrides]} (proc/cli-env-overrides {})]
      (is (not (contains? overrides "COPILOT_DISABLE_KEYTAR"))
          "KEYTAR override must be absent when :mode is unset"))))

(deftest cli-env-overrides-empty-mode-user-env-cannot-override-keytar
  (testing "KEYTAR override is in :overrides slot, so user :env cannot win"
    ;; Lock in the precedence contract documented on cli-env-overrides:
    ;; values returned in :overrides are applied AFTER user :env in spawn-cli,
    ;; so an attempt to set COPILOT_DISABLE_KEYTAR=0 via :env would be clobbered.
    (let [{:keys [defaults overrides]} (proc/cli-env-overrides {:mode :empty})]
      (is (= "1" (get overrides "COPILOT_DISABLE_KEYTAR")))
      (is (not (contains? defaults "COPILOT_DISABLE_KEYTAR"))
          "KEYTAR must NOT be a default — defaults can be overridden by :env"))))
