(ns byok-provider
  "Use an explicit model catalog without signing in to GitHub Copilot."
  (:require [basic-chat]
            [clojure.string :as str]
            [copilot.sdk :as copilot]))

(def default-prompt "What is 2+2? Answer in one sentence.")

(defn- required [env k]
  (let [value (get env k)]
    (when (str/blank? value) (throw (ex-info (str "Set " k " for this demo.") {})))
    value))

(defn session-options [env]
  (let [model (required env "COPILOT_DEMO_MODEL")
        url (required env "COPILOT_DEMO_BASE_URL")
        provider (cond
                   (= url "https://api.openai.com/v1")
                   {:name "openai" :provider-type :openai :wire-api :completions
                    :api-key (required env "COPILOT_DEMO_OPENAI_KEY")}

                   (= url "https://api.anthropic.com")
                   {:name "anthropic" :provider-type :anthropic
                    :api-key (required env "COPILOT_DEMO_ANTHROPIC_KEY")}

                   (re-matches #"https://[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.openai\.azure\.com" url)
                   {:name "azure" :provider-type :azure :azure {:api-version "2024-10-21"}
                    :api-key (required env "COPILOT_DEMO_AZURE_KEY")}

                   (re-matches #"http://127\.0\.0\.1:[0-9]{1,5}/v1" url)
                   {:name "local" :provider-type :openai :wire-api :completions}

                   :else (throw (ex-info "Unsupported COPILOT_DEMO_BASE_URL. Use the exact OpenAI or Anthropic API URL, an HTTPS Azure OpenAI resource host, or http://127.0.0.1:PORT/v1." {})))
        provider-name (:name provider)]
    {:model (str provider-name "/chat")
     :inference {:providers [(assoc provider :base-url url)]
                 :models [{:provider provider-name :id "chat" :model-id model :wire-model model}]}
     :tools {:available-tools []} :events {:streaming? true}
     :enable-config-discovery? false :github {:capi {:enabled? false}}}))

(defn run
  "Ask the model at the explicitly selected endpoint; local Ollama needs no key."
  ([state-dir prompt] (run state-dir prompt basic-chat/run))
  ([state-dir prompt demo]
   (let [options (assoc (session-options (System/getenv)) :working-directory state-dir)]
     (copilot/with-client [client {:mode :empty :base-directory state-dir
                                   :github {:use-logged-in-user? false}}]
       (demo client options prompt)))))
