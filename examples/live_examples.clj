(ns live-examples
  "Run one demo or the portable suite against a signed-in Copilot account."
  (:require [basic-chat]
            [byok-provider]
            [clojure.string :as str]
            [config-skill-output]
            [elicitation-provider]
            [empty-mode]
            [file-attachments]
            [copilot.sdk :as copilot]
            [infinite-sessions]
            [lifecycle-hooks]
            [manual-tool-resume]
            [mcp-local-server]
            [metadata-api]
            [multi-agent]
            [permission-bash]
            [reasoning-effort]
            [session-events]
            [session-resume]
            [structured-output]
            [tool-integration]
            [user-input]))

(def demos
  (sorted-map
   "ask-user-failure" [user-input/run-cancel user-input/default-prompt]
   "basic-chat" [basic-chat/run "What makes a rainbow? Answer in two sentences."
                 "Name one condition from your answer that I could look for outside."]
   "config-skill-output" [config-skill-output/run config-skill-output/default-prompt]
   "elicitation-provider" [elicitation-provider/run]
   "file-attachments" [file-attachments/run "Read the attached manifest with read_attachment, then name its dependency."]
   "file-selection" [file-attachments/selection "Which herb grows in the attached garden note?"]
   "infinite-sessions" (into [infinite-sessions/run] infinite-sessions/default-prompts)
   "lifecycle-hooks" [lifecycle-hooks/run lifecycle-hooks/default-prompt]
   "manual-tool-resume" [manual-tool-resume/run manual-tool-resume/default-prompt]
   "metadata-api" [metadata-api/run]
   "model-switch" [metadata-api/switch-model "What project name did I give you? Answer with the name only."]
   "multi-agent" (into [multi-agent/run] multi-agent/default-topics)
   "permission-policy" [tool-integration/run-denied "Call lookup_language for private. If denied, report that and stop."]
   "query" [basic-chat/once "What is the capital of Japan? Answer in one sentence."]
   "query-multi" [basic-chat/independent "What is 2+2?" "What is the capital of Japan?" "Who wrote Hamlet?"]
   "query-streaming" [session-events/callback-stream "Explain immutability in two short sentences."]
   "query-async" [session-events/run "Tell me a short joke."]
   "reasoning-effort" [reasoning-effort/run "What number comes next in 2, 6, 12, 20? Explain the pattern briefly."]
   "session-events" [session-events/run "In three short sentences, explain how a compass works."]
   "session-resume" [session-resume/run "What was the code word? Reply with just the word."]
   "structured-answer" [structured-output/answer "What is the capital of France? Return the answer with a confidence from 0 to 1."]
   "structured-output" [structured-output/run "The beta opens Friday. Kim will send invitations. Lee will update the guide."]
   "tool-integration" [tool-integration/run "clojure" "python" "rust" "unlisted"]
   "user-input" [user-input/run user-input/default-prompt]
   "user-input-simple" [user-input/run-simple user-input/simple-prompt]))

(def ^:private optional-demos
  {"byok-provider" [byok-provider/run byok-provider/default-prompt]
   "empty-mode" [empty-mode/run empty-mode/default-prompt]
   "mcp-local-server" [mcp-local-server/run mcp-local-server/default-prompt]
   "mcp-local-server-with-custom-tools" [mcp-local-server/run-with-custom-tools mcp-local-server/summary-prompt]})

(def named-demos {"permission-bash" [permission-bash/run]
                  "structured-output-primitive" [structured-output/run-primitive (second (get demos "structured-output"))]
                  "user-input-stdin" [user-input/run-stdin user-input/default-prompt]})

(defn selected [demo prompts]
  (if (nil? demo)
    demos
    (let [all (merge demos named-demos optional-demos)
          [run & defaults :as entry] (get all demo)]
      (when-not entry (throw (ex-info "Unknown live demo" {:demo demo :available (keys all)})))
      (when (and (seq prompts)
                 (or (empty? defaults) (some str/blank? prompts)
                     (and (not (#{"basic-chat" "query-multi" "tool-integration" "infinite-sessions" "multi-agent"} demo))
                          (next prompts))))
        (throw (ex-info "Unexpected demo arguments" {:demo demo :default-prompts defaults})))
      [[demo (cons run (or (seq prompts) defaults))]])))

(defn -main [state-dir & [demo & prompts]]
  (try
    (let [runs (selected demo prompts)
          model (not-empty (System/getenv "COPILOT_DEMO_MODEL"))
          client-options {:mode :empty :base-directory state-dir :github {:use-logged-in-user? true}}]
      (if (contains? optional-demos demo)
        (let [[_ [run & args]] (first runs)]
          (println "Live demo:" demo)
          (apply run state-dir args))
        (do
          (println "Live demos:" (count runs) "| GitHub Copilot model:" (or model "account default"))
          (copilot/with-client [client client-options]
            (when-not (:authenticated? (copilot/await!! (copilot/auth-status client)))
              (throw (ex-info "No account available in isolated demo mode. Sign in with GitHub CLI (gh auth login) using a Copilot-enabled account, or omit --live." {})))
            (let [options (cond-> {:tools {:available-tools []} :working-directory state-dir
                                   :enable-config-discovery? false :github {:telemetry-enabled? false}}
                            model (assoc :model model))]
              (doseq [[name [run & args]] runs]
                (println "\nLive demo:" name)
                (apply run (if (#{"manual-tool-resume" "query"} name) client-options client) options args)))))))
    (finally (shutdown-agents))))
