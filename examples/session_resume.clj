(ns session-resume
  (:require [basic-chat]
            [clojure.string :as str]
            [copilot.sdk :as copilot]))

(defn run [client options prompt]
  (let [code-word "PINEAPPLE"
        id (copilot/with-session [session client (assoc options :name "Code word example")]
             (basic-chat/ask session (str "Remember the code word " code-word ". Repeat it back."))
             (copilot/session-id session))
        answer (copilot/with-resumed-session [session client id (assoc options :allow-transcript-recovery? false)]
                 (println "Resumed session:" id)
                 (when (:transcript-recovery (copilot/session-info session))
                   (throw (ex-info "Clean resume unexpectedly required transcript recovery" {})))
                 (basic-chat/ask session prompt))]
    (copilot/delete-session!! client id)
    (when-not (str/includes? (str/upper-case answer) code-word)
      (throw (ex-info "The resumed session did not recall the code word" {})))
    answer))
