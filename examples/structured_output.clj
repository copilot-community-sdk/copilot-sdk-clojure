(ns structured-output
  "Demonstrates validated structured output with a Clojure parser."
  (:require [github.copilot-sdk :as copilot]))

;; See examples/README.md for usage

(def response-schema
  {"type" "object"
   "properties"
   {"answer" {"type" "string"}
    "confidence" {"type" "number"}}
   "required" ["answer" "confidence"]
   "additionalProperties" false})

(defn- parse-response
  [value]
  (let [answer (get value "answer")
        confidence (get value "confidence")]
    (when-not (and (map? value)
                   (string? answer)
                   (number? confidence))
      (throw
       (ex-info
        "Structured response did not match the expected result"
        {:response value})))
    {:answer answer
     :confidence confidence}))

(defn run
  [{:keys [model prompt]
    :or {prompt
         "What is the capital of France? Return a concise answer and confidence from 0 through 1."
         model "gpt-5.4"}}]
  (copilot/with-client-session
    [session {:on-permission-request copilot/approve-all
              :model model
              :available-tools []}]
    (let [result
          (copilot/send-and-wait!
           session
           {:prompt prompt}
           {:to-json-schema (constantly response-schema)
            :parse parse-response}
           120000)]
      (println "Answer:" (:answer result))
      (println "Confidence:" (:confidence result)))))
