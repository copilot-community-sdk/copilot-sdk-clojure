(ns reasoning-effort
  "Demonstrates setting the reasoning effort level for a session.
   The :reasoning-effort option accepts \"low\", \"medium\", \"high\", or \"xhigh\"
   and controls how much reasoning the model applies to its responses."
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(def defaults
  {:prompt "What is the capital of France? Answer in one sentence."
   :effort "low"})

(defn run
  [{:keys [prompt effort] :or {prompt (:prompt defaults) effort (:effort defaults)}}]
  (println "Reasoning effort:" effort)
  (copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                         :model "gpt-5.2"
                                         :reasoning-effort effort
                                         :available-tools []}]
    (println "Q:" prompt)
    (println "🤖:" (h/query prompt :session session))))
