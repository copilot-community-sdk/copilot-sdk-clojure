(ns live-examples
  "Run a demo against GitHub Copilot using the native runtime's signed-in account."
  (:require [basic-chat]
            [clojure.string :as str]
            [github.copilot-sdk :as copilot]
            [lifecycle-hooks]
            [session-events]
            [session-resume]
            [structured-output]
            [tool-integration]))

(def ^:private demos
  {"basic-chat" [basic-chat/run "What makes a rainbow? Answer in two sentences."]
   "session-events" [session-events/run "In three short sentences, explain how a compass works."]
   "session-resume" [session-resume/run "Suggest one way to remember a new colleague's name."]
   "tool-integration" [tool-integration/run "Use ticket_status to look up NW-17. Report its status."]
   "structured-output" [structured-output/run "The beta opens Friday. Kim will send invitations. Lee will update the guide."]
   "lifecycle-hooks" [lifecycle-hooks/run "Why do bridges have expansion joints?"]})

(defn -main [state-dir demo & prompts]
  (try
    (let [[run default-prompt] (get demos demo)
          model (not-empty (System/getenv "COPILOT_DEMO_MODEL"))]
      (when-not run
        (throw (ex-info "Unknown live demo" {:available (sort (keys demos))})))
      (when (or (some str/blank? prompts)
                (and (not= demo "basic-chat") (next prompts)))
        (throw (ex-info "Use one nonblank prompt; basic-chat also accepts follow-up prompts" {})))
      (println "Live demo:" demo "| GitHub Copilot model:" (or model "account default"))
      (copilot/with-client [client {:mode :empty :base-directory state-dir
                                    :github {:use-logged-in-user? true}}]
        (when-not (:authenticated? (copilot/await!! (copilot/auth-status client)))
          (throw (ex-info "No signed-in GitHub account available to the native runtime" {})))
        (let [options (cond-> {:tools {:available-tools []} :working-directory state-dir
                               :enable-config-discovery? false :github {:telemetry-enabled? false}}
                        model (assoc :model model))]
          (apply run client options (or (seq prompts) [default-prompt])))))
    (finally (shutdown-agents))))
