(ns user-input
  "Offer a preset, simulate a skipped question, or read one answer from stdin."
  (:require [clojure.string :as str]
            [copilot.sdk :as copilot]))

(def default-prompt
  "Use ask_user once to ask me to choose Blue or Green. Report my answer, or say no answer was available. Do not retry.")

(def simple-prompt
  "Use ask_user once to ask whether I prefer concise or verbose output. Then greet me in that style.")

(defn- ask!! [client options prompt answer!]
  (let [handled (atom nil)
        handle (fn [{:keys [question] :as request} _]
                 (println "Question:" question)
                 (if-let [reply (answer! request)]
                   (do (reset! handled :answered) reply)
                   (do (println "Input: skipped")
                       (reset! handled :skipped)
                       ;; Legacy ask_user has no :cancel result. A failed callback reports no answer.
                       (throw (ex-info "Skipped the demo question" {})))))]
    (copilot/with-session
      [session client (assoc options :tools {:available-tools ["builtin:ask_user"]}
                             :interaction {:ask-user-variant :legacy :on-user-input-request handle})]
      (println "You:" prompt)
      (let [reply (copilot/send-and-wait!! session {:prompt prompt :timeout-ms 60000})
            answer (get-in reply [:data :content])]
        (when-not @handled (throw (ex-info "No ask_user request was handled" {})))
        (when (str/blank? answer) (throw (ex-info "The model returned no answer" {})))
        (println "Assistant:" answer)
        answer))))

(defn run-simple [client options prompt]
  (ask!! client options prompt
         (fn [{:keys [choices allow-freeform?]}]
           (let [answer (or (first choices) (when allow-freeform? "concise"))]
             (when-not answer (throw (ex-info "No permitted preset answer" {})))
             (println "Preset:" answer)
             {:answer answer :was-freeform? (empty? choices)}))))

(defn run-cancel [client options prompt]
  (ask!! client options prompt (constantly nil)))

(defn run [client options prompt]
  (println "When the application answers:")
  (let [answered (run-simple client options prompt)]
    (println "When the application skips the question:")
    {:answered answered :skipped (run-cancel client options prompt)}))

(defn run-stdin
  "Read a literal answer before opening the session. Match an offered choice by
  its text, not its position; otherwise use freeform if allowed. EOF skips."
  ([client options prompt]
   (run-stdin client options prompt read-line))
  ([client options prompt input!]
   (println "Supply one answer for this demo before the turn starts.")
   (println "Enter the text you want to answer (for example, Green), or EOF to skip:")
   (flush)
   (let [line (input!)]
     (ask!! client options prompt
            (fn [{:keys [choices allow-freeform?]}]
              (doseq [[i text] (map-indexed vector choices)] (println (str (inc i) ".") text))
              (if (nil? line)
                (do (println "Input: EOF") nil)
                (let [selected (some #{line} choices)]
                  (cond
                    selected {:answer selected :was-freeform? false}
                    (and allow-freeform? (not (str/blank? line))) {:answer line :was-freeform? true}
                    :else (throw (ex-info "Expected one of the offered choices" {}))))))))))
