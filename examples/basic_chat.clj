(ns basic-chat
  "Ask one question, or follow up in the same conversation."
  (:require [clojure.string :as str]
            [github.copilot-sdk :as copilot]))

(defn run [client options prompt & follow-ups]
  (copilot/with-session [session client options]
    (loop [[question & more] (cons prompt follow-ups)]
      (println "You:" question)
      (let [reply (copilot/await!! (copilot/send-and-wait! session {:prompt question :timeout-ms 60000}))
            answer (get-in reply [:data :content])]
        (when (str/blank? answer)
          (throw (ex-info "The model returned no chat answer" {})))
        (println "Assistant:" answer)
        (if (seq more) (recur more) answer)))))
