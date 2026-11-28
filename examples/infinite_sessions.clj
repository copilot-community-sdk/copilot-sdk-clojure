(ns infinite-sessions
  "Keep a conversation open across turns and look for compaction events in its history."
  (:require [basic-chat]
            [copilot.sdk :as copilot]))

(def default-prompts
  ["What is the capital of France?" "What is the capital of Japan?" "What is the capital of Brazil?"])

(defn run [client options & prompts]
  (let [policy {:enabled? true :background-compaction-threshold 0.80 :buffer-exhaustion-threshold 0.95}
        config (-> options
                   (assoc :tools {:available-tools []}
                          :system-message {:mode :replace :content "Answer concisely in one sentence."})
                   (assoc-in [:state :infinite-sessions] policy))]
    (println "Compaction settings:" policy)
    (copilot/with-session [session client config]
      (let [answers (mapv #(basic-chat/ask session %) (or (seq prompts) default-prompts))
            history (copilot/await!! (copilot/history session))
            events (mapv :type (filter #(#{:copilot/session.compaction_start :copilot/session.compaction_complete}
                                         (:type %)) history))]
        (if (seq events)
          (println "Compaction events in history:" events)
          (println "No compaction events in history after these turns."))
        {:answers answers :compaction-events events}))))
