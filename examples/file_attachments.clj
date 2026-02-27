(ns file-attachments
  "Demonstrates sending file attachments with a prompt.
   Attaches the project's deps.edn file and asks the model to analyze it."
  (:require [github.copilot-sdk :as copilot]))

;; See examples/README.md for usage

(def defaults
  {:prompt "Summarize the dependencies in the attached file in 2-3 sentences."
   :file-path "deps.edn"})

(defn run
  [{:keys [prompt file-path]
    :or {prompt (:prompt defaults) file-path (:file-path defaults)}}]
  (let [abs-path (.getAbsolutePath (java.io.File. file-path))]
    (copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                          :model "claude-haiku-4.5"}]
      (println "📎 Attaching:" abs-path)
      (println "Q:" prompt)
      (let [response (copilot/send-and-wait!
                       session
                       {:prompt prompt
                        :attachments [{:type :file :path abs-path}]})]
        (println "🤖:" (get-in response [:data :content]))))))
