(ns structured-output
  "Turn a meeting note into a summary and a list of action items."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [github.copilot-sdk :as copilot]))

(def note-schema
  {:type "object"
   :properties {:summary {:type "string" :description "A one-sentence summary of the note."}
                :actions {:type "array" :items {:type "string"}
                          :description "Action items stated in the note. Use [] if there are none."}}
   :required ["summary" "actions"]
   :additionalProperties false})

(defn- nonblank-text? [value]
  (and (string? value) (not (str/blank? value))))

(defn- parse-note [content]
  (let [{:keys [summary actions] :as note}
        (json/read-str content :key-fn keyword :extra-data-fn json/on-extra-throw)]
    (when-not (and (map? note) (= #{:summary :actions} (set (keys note)))
                   (nonblank-text? summary) (vector? actions) (every? nonblank-text? actions))
      (throw (ex-info "Expected a summary string and an array of action items" {})))
    note))

(defn run [client options prompt]
  (printf "Extracting action items from this note (preview): %.500s%n" prompt)
  (copilot/with-session
    [session client (assoc options :tools {:available-tools []}
                           :system-message {:mode :append
                                            :content "Summarize the meeting note. Extract only agreed actions. Do not invent tasks."})]
    (let [reply (copilot/await!! (copilot/send-and-wait! session {:prompt prompt
                                                                  :response-schema note-schema
                                                                  :timeout-ms 60000}))
          {:keys [summary actions] :as note} (parse-note (get-in reply [:data :content]))]
      (printf "Summary: %.500s%nAction items (%d; up to 5 shown):%n" summary (count actions))
      (doseq [action (take 5 actions)]
        (printf "  - %.240s%n" action))
      note)))
