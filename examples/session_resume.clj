(ns session-resume
  "Demonstrates session resume: create a session, teach it a secret word,
   then resume the session by ID and verify the model remembers it."
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]))

;; See examples/README.md for usage

(def defaults
  {:secret-word "PINEAPPLE"
   :prompt "What was the secret word I told you? Reply with just the word."})

(defn run
  [{:keys [secret-word prompt]
    :or {secret-word (:secret-word defaults) prompt (:prompt defaults)}}]
  (copilot/with-client [client {}]
    (println "Creating session...")
    (let [session (copilot/create-session
                    client
                    {:on-permission-request copilot/approve-all
                     :model "claude-haiku-4.5"
                     :available-tools []})]
      (println "Sending secret word:" secret-word)
      (let [result (copilot/send-and-wait!
                     session
                     {:prompt (str "Remember this secret word: " secret-word
                                   ". Confirm you have memorized it.")})]
        (println "🤖:" (get-in result [:data :content])))

      (let [session-id (:session-id session)]
        (println "\nResuming session" session-id "...")
        (let [resumed (copilot/resume-session
                        client session-id
                        {:on-permission-request copilot/approve-all
                         :available-tools []})]
          (println "Asking:" prompt)
          (let [result (copilot/send-and-wait! resumed {:prompt prompt})]
            (println "🤖:" (get-in result [:data :content])))
          (copilot/destroy! resumed))))))
