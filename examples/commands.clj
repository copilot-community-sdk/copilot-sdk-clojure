(ns commands
  "Handle a slash command dispatched by a host. Sending its spelling as a model prompt does not invoke it."
  (:require [copilot.sdk :as copilot]))

(defn run
  "invoke-command!! is supplied by the host. It dispatches the command on the
  given session and waits for its completion. The headless SDK does not supply a UI."
  [client options invoke-command!!]
  (let [shown (atom [])
        guide {:name "guide" :description "List the demo commands."
               :handler (fn [{:keys [command-name]}]
                          (println "Commands: /status, /guide")
                          (swap! shown conj command-name))}
        status {:name "status" :description "Show the current session."
                :handler (fn [{:keys [session-id]}]
                           (println "Session:" session-id)
                           (swap! shown conj session-id))}]
    (copilot/with-session
      [session client (assoc options :tools {:available-tools []} :interaction {:commands [guide status]})]
      (invoke-command!! session "/guide")
      (invoke-command!! session "/status")
      (when-not (= @shown ["guide" (copilot/session-id session)])
        (throw (ex-info "The host did not dispatch both commands to this session" {})))
      :dispatched)))
