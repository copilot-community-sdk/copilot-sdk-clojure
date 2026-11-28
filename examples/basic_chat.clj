(ns basic-chat
  "Ask one question, or follow up in the same conversation."
  (:require [clojure.string :as str]
            [copilot.sdk :as copilot]
            [copilot.sdk.helpers :as h]))

(defn- show-answer [answer]
  (when (str/blank? answer) (throw (ex-info "The model returned no chat answer" {})))
  (println "Assistant:" answer)
  answer)

(defn ask [session question]
  (println "You:" (if (string? question) question (:prompt question)))
  (show-answer (h/query!! session question)))

(defn once [client-options session-options prompt]
  (println "You:" prompt)
  (show-answer (h/query-once!! {:client client-options :session session-options} prompt)))

(defn run [client options prompt & follow-ups]
  (copilot/with-session [session client options]
    (loop [[question & more] (cons prompt follow-ups)]
      (let [answer (ask session question)]
        (if (seq more) (recur more) answer)))))

(defn independent [client options & questions]
  ;; Reuse the client, not the conversation. None of the questions share history.
  (mapv (fn [question] (copilot/with-session [session client options] (ask session question))) questions))
