(ns fixtures.optional-smoke
  "Application run by optional_smoke.py with an isolated home and local model."
  (:require [byok-provider :as byok]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]
            [empty-mode]
            [mcp-local-server :as mcp]))

(deftest explicit-provider-selection
  (let [env {"COPILOT_DEMO_MODEL" "my-deployment"
             "COPILOT_DEMO_OPENAI_KEY" "test-openai" "COPILOT_DEMO_AZURE_KEY" "test-azure"
             "COPILOT_DEMO_ANTHROPIC_KEY" "test-anthropic"}
        options #(byok/session-options (assoc %1 "COPILOT_DEMO_BASE_URL" %2))]
    (doseq [[url name type key-var extra]
            [["https://api.openai.com/v1" "openai" :openai "COPILOT_DEMO_OPENAI_KEY" {:wire-api :completions}]
             ["https://test-resource.openai.azure.com" "azure" :azure "COPILOT_DEMO_AZURE_KEY" {:azure {:api-version "2024-10-21"}}]
             ["https://api.anthropic.com" "anthropic" :anthropic "COPILOT_DEMO_ANTHROPIC_KEY" {}]
             ["http://127.0.0.1:11434/v1" "local" :openai nil {:wire-api :completions}]]]
      (let [config (options env url)]
        (is (= (get-in config [:inference :providers])
               [(cond-> (merge {:name name :provider-type type :base-url url} extra)
                  key-var (assoc :api-key (env key-var)))]))
        (is (= (:model config) (str name "/chat")))
        (is (= (get-in config [:inference :models])
               [{:id "chat" :provider name :model-id "my-deployment" :wire-model "my-deployment"}])))
      (when key-var (is (thrown? Exception (options (dissoc env key-var) url)))))
    (doseq [url ["https://api.openai.com@example.invalid/v1" "http://api.anthropic.com"
                 "https://test.openai.azure.com.example.invalid" "https://test.openai.azure.com/other"
                 "http://127.0.0.1:11434/v1?key=fake"]]
      (is (thrown? Exception (options env url))))))

(defn -main []
  (try
    (let [{:keys [error fail]} (run-tests 'fixtures.optional-smoke)]
      (when-not (zero? (+ error fail)) (throw (ex-info "Provider configuration checks failed." {}))))
    (doseq [[demo run prompt expected] [["byok-provider" byok/run byok/default-prompt "2 + 2 is 4."]
                                        ["empty-mode" empty-mode/run empty-mode/default-prompt "2 + 2 is 4."]
                                        ["mcp-local-server" mcp/run mcp/default-prompt "garden.md\nweather.csv"]
                                        ["mcp-local-server-with-custom-tools" mcp/run-with-custom-tools mcp/summary-prompt
                                         "2 files: garden.md, weather.csv."]]]
      (let [dir (io/file (System/getenv "HOME") demo)]
        (when-not (.mkdir dir) (throw (ex-info "Could not create demo state." {})))
        (when-not (= expected (run (.getAbsolutePath dir) prompt))
          (throw (ex-info "Unexpected model reply." {:demo demo})))
        (println "PASS:" demo)))
    (finally (shutdown-agents))))
