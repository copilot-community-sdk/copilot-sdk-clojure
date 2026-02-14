(ns metadata-api
  "Demonstrates the metadata API functions introduced in v0.1.24:
   - list-sessions with context filtering
   - list-tools with model-specific overrides
   - get-quota for account usage information
   - get-current-model and switch-model for dynamic model switching"
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(defn run
  [& _]
  (println "=== Copilot Metadata API Demo ===\n")
  
  (copilot/with-client [client {:log-level :warning}]
    ;; 1. List available tools
    (println "1. Available Tools:")
    (let [tools (copilot/list-tools client)]
      (println (str "   Found " (count tools) " tools"))
      (doseq [tool (take 5 tools)]
        (println (str "   - " (:name tool) ": " (:description tool)))))
    
    ;; 2. List tools for a specific model
    (println "\n2. Tools for GPT-5.2:")
    (let [tools (copilot/list-tools client "gpt-5.2")]
      (println (str "   Found " (count tools) " model-specific tools")))
    
    ;; 3. Get quota information
    (println "\n3. Account Quota:")
    (try
      (let [quotas (copilot/get-quota client)]
        (doseq [[quota-type snapshot] quotas]
          (println (str "   " quota-type ":"))
          (println (str "     Entitlement: " (:entitlement-requests snapshot)))
          (println (str "     Used: " (:used-requests snapshot)))
          (println (str "     Remaining: " (:remaining-percentage snapshot) "%"))))
      (catch Exception e
        (println "   Note: Quota information requires authentication")))
    
    ;; 4. List sessions
    (println "\n4. Active Sessions:")
    (let [sessions (copilot/list-sessions client)]
      (println (str "   Found " (count sessions) " session(s)"))
      (when (seq sessions)
        (doseq [session (take 3 sessions)]
          (println (str "   - " (:session-id session)))
          (when-let [ctx (:context session)]
            (println (str "     Repository: " (:repository ctx)))
            (println (str "     Branch: " (:branch ctx)))))))
    
    ;; 5. Model switching within a session
    (println "\n5. Dynamic Model Switching:")
    (copilot/with-session [session client {:model "gpt-5.2"}]
      (let [initial-model (copilot/get-current-model session)]
        (println (str "   Initial model: " initial-model))
        
        ;; Query with first model
        (println "\n   Query 1 (gpt-5.2): 'What is 2+2?'")
        (println (str "   Response: " (h/query "What is 2+2?" :session session)))
        
        ;; Switch to a different model
        (copilot/switch-model! session "claude-sonnet-4.5")
        (let [new-model (copilot/get-current-model session)]
          (println (str "\n   Switched to: " new-model))
          
          ;; Query with new model (maintaining conversation context)
          (println "\n   Query 2 (claude-sonnet-4.5): 'What was my previous question?'")
          (println (str "   Response: " (h/query "What was my previous question?" :session session)))))))
  
  (println "\n=== Demo Complete ==="))

