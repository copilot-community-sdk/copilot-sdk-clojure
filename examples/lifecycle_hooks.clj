(ns lifecycle-hooks
  "Lifecycle hooks: register callbacks for session start/end, tool use, prompts, and errors."
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(def defaults
  {:prompt "Use the glob tool to list all .clj files in the examples directory. Just list the filenames."})

(defn run
  [{:keys [prompt] :or {prompt (:prompt defaults)}}]
  (let [fired-hooks (atom [])
        record!     (fn [hook-name data]
                      (swap! fired-hooks conj {:hook hook-name :data data}))]
    (copilot/with-client-session
      [session {:on-permission-request copilot/approve-all
                :model "claude-haiku-4.5"
                :hooks {:on-session-start
                        (fn [data _ctx]
                          (println "🚀 Hook: session-start")
                          (record! :on-session-start data))

                        :on-session-end
                        (fn [data _ctx]
                          (println "🏁 Hook: session-end")
                          (record! :on-session-end data))

                        :on-pre-tool-use
                        (fn [data _ctx]
                          (println "🔧 Hook: pre-tool-use —" (:toolName data))
                          (record! :on-pre-tool-use data)
                          {:approved true})

                        :on-post-tool-use
                        (fn [data _ctx]
                          (println "✅ Hook: post-tool-use —" (:toolName data))
                          (record! :on-post-tool-use data))

                        :on-user-prompt-submitted
                        (fn [data _ctx]
                          (println "💬 Hook: user-prompt-submitted")
                          (record! :on-user-prompt-submitted data))

                        :on-error-occurred
                        (fn [data _ctx]
                          (println "❌ Hook: error-occurred")
                          (record! :on-error-occurred data))}}]

      (println "\nPrompt:" prompt "\n")
      (println "🤖:" (h/query prompt :session session))

      (println "\n--- Hook summary ---")
      (let [events @fired-hooks
            freqs  (frequencies (map :hook events))]
        (println "Total hooks fired:" (count events))
        (doseq [[hook cnt] (sort-by (comp str key) freqs)]
          (println (str "  " hook " × " cnt)))))))
