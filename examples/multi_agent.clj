(ns multi-agent
  "Multi-agent pipeline: 3 researchers (parallel) → analyst → writer"
  (:require [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.helpers :as h]
            [clojure.core.async :as async :refer [go <! <!!]]))

;; See examples/README.md for usage

(def defaults
  {:topics ["functional programming benefits"
            "immutable data structures"
            "concurrent programming challenges"]})

(def researcher-prompt "You are a research assistant. Be concise: 2-3 bullet points.")
(def analyst-prompt "You are an analyst. Identify patterns and insights. Be concise: 2-3 sentences.")
(def writer-prompt "You are a writer. Create clear, engaging prose.")

(defn research-topic
  "Start async research on a topic. Returns channel yielding {:topic :findings}."
  [client topic]
  (let [session (copilot/create-session client {:system-message {:mode :append :content researcher-prompt}})]
    (go {:topic topic
         :findings (<! (copilot/<send! session {:prompt (str "Research: " topic)}))})))

(defn run
  [{:keys [topics] :or {topics (:topics defaults)}}]
  (copilot/with-client [client {}]
    ;; Phase 1: Launch parallel research (non-blocking)
    (println "📚 Research Phase (parallel):")
    (let [start (System/currentTimeMillis)
          result-chs (mapv #(research-topic client %) topics)
          merged (async/merge result-chs)
          results (repeatedly (count topics)
                              #(let [{:keys [topic findings] :as r} (<!! merged)]
                                 (println (str "  • " topic ": " (subs findings 0 (min 100 (count findings))) "..."))
                                 r))]
      (doall results)
      (println (str "  (completed in " (- (System/currentTimeMillis) start) "ms)"))

      ;; Phase 2: Analysis
      (println "\n🔍 Analysis Phase:")
      (let [research-summary (->> results
                                  (map #(str "• " (:topic %) ": " (:findings %)))
                                  (clojure.string/join "\n\n"))
            analysis (h/query-with-client client
                                          (str "Analyze these findings:\n\n" research-summary)
                                          :session {:system-prompt analyst-prompt})]
        (println (str "  - " (subs analysis 0 (min 200 (count analysis))) "..."))

        ;; Phase 3: Synthesis
        (println "\n✍️ Synthesis Phase:")
        (let [summary (h/query-with-client client
                                           (str "Write a 3-4 sentence executive summary:\n\n"
                                                "RESEARCH:\n" research-summary "\n\n"
                                                "ANALYSIS:\n" analysis)
                                           :session {:system-prompt writer-prompt})]
          (println "\n==================================================")
          (println "📋 FINAL SUMMARY:")
          (println "==================================================")
          (println summary))))))
