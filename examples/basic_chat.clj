(ns basic-chat
  (:require [krukow.copilot-sdk :as copilot]))

(defn -main [& _args]
  (copilot/with-client-session [session {:model "gpt-5.2"}]
    (let [q1 "What is the capital of France? Please answer in one sentence."
          q2 "What is its population approximately?"]

      (println "Q1: " q1)
      (println "🤖:" (-> (copilot/send-and-wait! session {:prompt q1})
                         (get-in [:data :content])))

      (println "Q2: " q2)
      (println "🤖:" (-> (copilot/send-and-wait! session {:prompt q2})
                         (get-in [:data :content]))))))
