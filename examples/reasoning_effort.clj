(ns reasoning-effort
  "Select an advertised reasoning effort for a model that supports it."
  (:require [basic-chat]
            [copilot.sdk :as copilot]))

(defn run [client options prompt]
  (let [requested (:model options)
        requested-effort (not-empty (System/getenv "COPILOT_DEMO_REASONING_EFFORT"))
        models (copilot/await!! (copilot/list-models client))
        model (some #(when (and (or (nil? requested) (= requested (:id %)))
                                (if requested-effort
                                  (some #{requested-effort} (:supported-reasoning-efforts %))
                                  (seq (:supported-reasoning-efforts %)))) %) models)
        levels (:supported-reasoning-efforts model)
        effort (or requested-effort (some #{"low"} levels) (first levels))]
    (when-not model
      (throw (ex-info "No matching model advertises the requested reasoning effort"
                      {:requested-model requested :requested-effort requested-effort})))
    (copilot/with-session [session client options]
      (println "Switching to:" (:id model) "| requested reasoning effort:" effort)
      (copilot/await!! (copilot/set-model! session (:id model) {:reasoning-effort effort}))
      (basic-chat/ask session prompt))))
