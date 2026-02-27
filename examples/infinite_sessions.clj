(ns infinite-sessions
  "Example: Infinite sessions with context compaction.

   Demonstrates how to enable infinite sessions so the SDK automatically
   compacts older messages when the context window fills up, allowing
   arbitrarily long conversations."
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(def defaults
  {:prompts ["What is the capital of France?"
             "What is the capital of Japan?"
             "What is the capital of Brazil?"]})

(defn run
  [{:keys [prompts] :or {prompts (:prompts defaults)}}]
  (copilot/with-client-session
      [session {:on-permission-request copilot/approve-all
                :model "claude-haiku-4.5"
                :available-tools []
                :system-message {:mode "replace"
                                :content "Answer concisely in one sentence."}
                :infinite-sessions {:enabled true
                                    :background-compaction-threshold 0.80
                                    :buffer-exhaustion-threshold 0.95}}]
    (doseq [prompt prompts]
      (println "Q:" prompt)
      (println "🤖:" (h/query prompt :session session))
      (println))
    (println "✅ All prompts completed with infinite sessions enabled.")))
