(ns structured-output
  "Turn a meeting note into a summary and a list of action items."
  (:require [clojure.string :as str]
            [copilot.sdk :as copilot]
            [copilot.sdk.helpers :as h]))

(def note-schema
  {:type "object"
   :properties {:summary {:type "string" :description "A one-sentence summary of the note."}
                :actions {:type "array" :items {:type "string"}
                          :description "Action items stated in the note. Use [] if there are none."}}
   :required ["summary" "actions"]
   :additionalProperties false})

(defn- nonblank-text? [value]
  (and (string? value) (not (str/blank? value))))

(defn- validate-note [{:strs [summary actions] :as note}]
  (when-not (and (map? note) (= #{"summary" "actions"} (set (keys note)))
                 (nonblank-text? summary) (vector? actions) (every? nonblank-text? actions))
    (throw (ex-info "Expected a summary string and an array of action items" {})))
  note)

(defn answer [client options prompt]
  (let [schema {:type "object" :properties {:answer {:type "string"}
                                            :confidence {:type "number" :description "A confidence estimate from 0 to 1."}}
                :required ["answer" "confidence"] :additionalProperties false}]
    (copilot/with-session [session client options]
      (println "You:" prompt)
      (let [{:strs [answer confidence] :as result} (h/query-json!! session {:prompt prompt :response-schema schema})]
        (when-not (and (map? result) (= #{"answer" "confidence"} (set (keys result))) (nonblank-text? answer)
                       (number? confidence) (<= 0 confidence 1))
          (throw (ex-info "Expected a text answer and confidence between zero and one" {})))
        (println "Answer:" answer "| reported confidence:" (str confidence))
        result))))

(defn- extract-note [query client options prompt]
  (printf "Extracting action items from this note (preview): %.500s%n" prompt)
  (copilot/with-session
    [session client (assoc options :tools {:available-tools []}
                           :system-message {:mode :append
                                            :content "Summarize the meeting note. Extract only agreed actions. Do not invent tasks."})]
    (let [{:strs [summary actions] :as note} (validate-note (query session {:prompt prompt :response-schema note-schema}))]
      (printf "Summary: %.500s%nAction items (%d; up to 5 shown):%n" summary (count actions))
      (doseq [action (take 5 actions)]
        (printf "  - %.240s%n" action))
      note)))

(defn run [client options prompt]
  (extract-note h/query-json!! client options prompt))

(defn run-primitive [client options prompt]
  (letfn [(query [session message]
            (let [reply (copilot/send-and-wait!! session message)]
              (h/decode-json (get-in reply [:data :content]))))]
    (extract-note query client options prompt)))
