(ns reasoning-effort
  "Select an advertised reasoning effort for a model that supports it."
  (:require [basic-chat]
            [copilot.sdk :as copilot]))

(defn- supported? [model id effort]
  (let [levels (:supported-reasoning-efforts model)]
    (and (or (nil? id) (= id (:id model)))
         (if effort (some #{effort} levels) (seq levels)))))

(defn run [client options prompt]
  (let [requested (:model options)
        requested-effort (not-empty (System/getenv "COPILOT_DEMO_REASONING_EFFORT"))
        models (copilot/await!! (copilot/list-models client))
        model (first (filter #(supported? % requested requested-effort) models))
        levels (:supported-reasoning-efforts model)
        effort (or requested-effort (some #{"low"} levels) (first levels))]
    (when-not model
      (throw (ex-info "No matching model advertises the requested reasoning effort"
                      {:requested-model requested :requested-effort requested-effort})))
    (copilot/with-session [session client options]
      (println "Model:" (:id model) "| supported efforts:" levels "| selected:" effort)
      (copilot/await!! (copilot/set-model! session (:id model) {:reasoning-effort effort}))
      (basic-chat/ask session prompt))))
