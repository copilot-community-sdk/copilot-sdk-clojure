(ns session-resume
  (:require [github.copilot-sdk :as copilot]))

(defn run [client options prompt]
  (let [id (copilot/with-session [session client options]
             (copilot/await!! (copilot/send-and-wait! session {:prompt prompt :timeout-ms 30000}))
             (copilot/session-id session))
        content (with-open [session (copilot/resume-session!! client id options)]
                  (->> (copilot/await!! (copilot/history session))
                       (filter #(= (:type %) :copilot/assistant.message))
                       last :data :content))
        metadata (copilot/await!! (copilot/session-metadata client id))]
    ;; Delete only the session this example created.
    (copilot/delete-session!! client id)
    {:content content :found? (= id (:session-id metadata))
     :deleted? (nil? (copilot/await!! (copilot/session-metadata client id)))}))
