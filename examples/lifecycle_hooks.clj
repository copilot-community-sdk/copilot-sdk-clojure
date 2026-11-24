(ns lifecycle-hooks
  "Use a prompt hook to adapt explanations for a new reader."
  (:require [clojure.string :as str]
            [github.copilot-sdk :as copilot]))

(def audience-brief
  "Explain this to a new reader. Use plain language and give one everyday example.")

(defn run [client options prompt]
  (let [calls (atom 0)
        add-brief (fn [_input _invocation]
                    (swap! calls inc)
                    {:additional-context audience-brief})]
    (printf "Reader's question (preview): %.500s%n" prompt)
    (copilot/with-session
      [session client (assoc options :tools {:available-tools []}
                             :hooks {:enable-repository-hooks? false
                                     :handlers {:on-user-prompt-submitted add-brief}})]
      (let [reply (copilot/await!! (copilot/send-and-wait! session {:prompt prompt :timeout-ms 60000}))
            answer (get-in reply [:data :content])]
        (when (zero? @calls)
          (throw (ex-info "The runtime did not invoke the prompt hook" {})))
        (printf "Hook :on-user-prompt-submitted fired %d time(s). Added context: %s%n" @calls audience-brief)
        (when-not (and (string? answer) (not (str/blank? answer)))
          (throw (ex-info "The model did not return an explanation" {})))
        (printf "Explanation (preview): %.2000s%n" answer)
        {:answer answer :prompt-hook-calls @calls}))))
