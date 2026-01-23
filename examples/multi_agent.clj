(ns multi-agent
  "Multi-agent pipeline: 3 researchers (parallel) → analyst → writer"
  (:require [krukow.copilot-sdk :as copilot]
            [clojure.core.async :refer [go <! <!!]]))

;; See examples/README.md for usage

(def defaults
  {:topics ["functional programming benefits"
            "immutable data structures"
            "concurrent programming patterns"]})

(defn run
  [{:keys [topics] :or {topics (:topics defaults)}}]
  (copilot/with-client [client {:model "gpt-5.2"}]
    ;; Phase 1: Parallel research - launch all go blocks
    (println "📚 Researching topics in parallel...")
    (let [research-chs (mapv (fn [topic]
                               (go
                                 (copilot/with-session [s client {:system-message
                                                                  {:mode :append
                                                                   :content "You are a research assistant. Be concise, 2-3 sentences."}}]
                                   {:topic topic
                                    :findings (<! (copilot/<send! s {:prompt (str "Research and summarize: " topic)}))})))
                             topics)
          research-results (mapv <!! research-chs)
          research-summary (->> research-results
                                (map #(str "• " (:topic %) ": " (:findings %)))
                                (clojure.string/join "\n\n"))]

      (println "\n📊 Analyzing findings...")
      ;; Phase 2: Analysis
      (let [analysis (<!! (go
                            (copilot/with-session [s client {:system-message
                                                             {:mode :append
                                                              :content "You are an analyst. Identify patterns and insights."}}]
                              (<! (copilot/<send! s {:prompt (str "Analyze these findings and identify 2-3 key insights:\n\n"
                                                                  research-summary)})))))]

        (println "\n✍️  Writing summary...\n")
        ;; Phase 3: Final synthesis
        (let [summary (<!! (go
                             (copilot/with-session [s client {:system-message
                                                              {:mode :append
                                                               :content "You are a writer. Create clear, engaging prose."}}]
                               (<! (copilot/<send! s {:prompt (str "Write a 3-4 sentence executive summary based on:\n\n"
                                                                   "RESEARCH:\n" research-summary "\n\n"
                                                                   "ANALYSIS:\n" analysis)})))))]
          (println summary))))))
