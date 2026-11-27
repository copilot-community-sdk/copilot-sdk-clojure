(ns lifecycle-hooks
  "Use native lifecycle hooks to guide a tool-free reader and observe its work."
  (:require [basic-chat]
            [copilot.sdk :as copilot]))

(def default-prompt "Why do bridges have expansion joints?")
(def audience-brief "Explain this to a new reader. Use plain language and give one everyday example.")
(def reader-brief "Give the writer a short factual explanation, not a finished article.")

(defn- handlers [seen]
  (letfn [(note! [hook detail]
            (swap! seen conj hook)
            (locking *out* (println (str "Hook " (name hook) ": " detail)))
            nil)]
    {:on-session-start (fn [{:keys [source]} _] (note! :session-start source))
     :on-session-end (fn [{:keys [reason]} _] (note! :session-end reason))
     :on-user-prompt-submitted (fn [_ _]
                                 (note! :prompt audience-brief)
                                 {:additional-context audience-brief})
     :on-pre-tool-use (fn [{:keys [tool-name]} _] (note! :pre-tool tool-name))
     :on-post-tool-use (fn [{:keys [tool-name tool-result]} _]
                         (note! :post-tool (str tool-name " => " (:result-type tool-result))))
     :on-subagent-start (fn [{:keys [agent-name]} _]
                          (note! :subagent-start agent-name)
                          {:additional-context reader-brief})
     :on-subagent-stop (fn [{:keys [agent-name]} _] (note! :subagent-stop agent-name))
     :on-agent-stop (fn [{:keys [stop-reason]} _] (note! :agent-stop stop-reason))
     :on-error-occurred (fn [{:keys [error-context error]} _]
                          (note! :error (str error-context ": " error))
                          {:error-handling :abort})}))

(defn run [client options question]
  (let [seen (atom #{})
        reader {:name "reader" :description "Prepare a factual note for the writer."
                :prompt "Give one fact that helps answer the reader's question." :tools []}
        prompt (str "Call the reader agent once with the task tool. Use its note to answer: " question)
        answer (copilot/with-session
                 [session client (assoc options :tools {:available-tools ["builtin:task"]}
                                        :agents {:included-builtin-agents [] :definitions [reader]}
                                        :hooks {:enable-repository-hooks? false :handlers (handlers seen)})]
                 (basic-chat/ask session prompt))]
    ;; Detaching the session can invoke session-end. Take the snapshot after with-session.
    (println "Hooks observed:" (sort @seen))
    (println "Tool and subagent hooks require a tool call and delegation. On a runtime error, the error hook requests an abort.")
    (when-not (every? @seen [:session-start :prompt :pre-tool :post-tool :subagent-start :subagent-stop :agent-stop :session-end])
      (throw (ex-info "The turn did not complete the reader workflow" {:observed @seen})))
    {:answer answer :hooks @seen}))
