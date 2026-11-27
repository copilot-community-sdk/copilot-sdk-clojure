(ns metadata-api
  "Inspect the native runtime and find a session in its private demo store."
  (:require [basic-chat]
            [copilot.sdk :as copilot]))

(defn run [client options]
  (let [{:keys [version protocol-version]} (copilot/await!! (copilot/status client))
        id (copilot/with-session [session client (assoc options :name "Metadata example")]
             (copilot/session-id session))
        listed (copilot/await!! (copilot/list-sessions client {:cwd (:working-directory options)}))
        metadata (copilot/await!! (copilot/session-metadata client id))]
    (when-not (and (= id (:session-id metadata)) (some #(= id (:session-id %)) listed))
      (throw (ex-info "The new demo session was not found in the store" {:session-id id})))
    (println "Runtime:" version "| protocol:" protocol-version)
    (println "Saved session:" id "| last modified:" (str (:modified-time metadata)))
    (copilot/delete-session!! client id)
    :found-and-deleted))

(defn switch-model [client options prompt]
  (let [models (copilot/await!! (copilot/list-models client))
        first-id (or (:model options) (:id (first models)))
        second-id (some #(when (not= first-id (:id %)) (:id %)) models)]
    (when-not (and first-id second-id)
      (throw (ex-info "Model switching needs two available models" {:initial-model first-id})))
    (copilot/with-session [session client (assoc options :model first-id)]
      (println "Initial model:" first-id)
      (basic-chat/ask session "Remember my project name: Maple Lantern. Confirm its name.")
      (copilot/await!! (copilot/set-model! session second-id))
      (println "Switched to:" second-id)
      (basic-chat/ask session prompt))))
