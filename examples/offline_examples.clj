(ns offline-examples
  "Run the examples with the repository's local model and isolated application JVM."
  (:require [basic-chat]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [github.copilot-sdk :as copilot]
            [github.copilot-sdk.native-offline-fixture :as fixture]
            [lifecycle-hooks]
            [session-events]
            [session-filesystem]
            [session-resume]
            [structured-output]
            [tool-integration]))

(defn- text-model [_]
  [[{:role "assistant" :content "Native "} {:content "turn complete."}] "stop"])

(defn- chat-model [requests]
  (case (count requests)
    1 (text-model requests)
    2 (let [messages (:messages (peek requests))]
        (when-not (and (some #(and (= "assistant" (:role %)) (= fixture/answer (:content %))) messages)
                       (str/includes? (:content (peek messages)) "What was your previous answer?"))
          (throw (ex-info "The chat follow-up lost its conversation history" {})))
        [[{:role "assistant" :content fixture/answer}] "stop"])))

(def ^:private extracted-note {:summary "The beta opens Friday." :actions ["Kim will send invitations."]})

(defn- note-model [requests]
  (let [{:keys [type json_schema]} (:response_format (peek requests))]
    (when-not (and (= type "json_schema") (true? (:strict json_schema))
                   (= (:schema json_schema) structured-output/note-schema))
      (throw (ex-info "The model did not receive the note schema" {})))
    [[{:role "assistant" :content (json/write-str extracted-note)}] "stop"]))

(defn- hook-model [requests]
  (when-not (str/includes? (json/write-str (:messages (peek requests))) lifecycle-hooks/audience-brief)
    (throw (ex-info "The prompt hook did not supply the audience brief to the model" {})))
  (text-model requests))

(defn- ticket-model [requests]
  (case (count requests)
    1 [[{:role "assistant"
         :tool_calls [{:index 0 :id "read-ticket" :type "function"
                       :function {:name "ticket_status" :arguments (json/write-str {:ticket "NW-17"})}}]}]
       "tool_calls"]
    2 (let [results (filter #(= (:role %) "tool") (:messages (peek requests)))]
        (when-not (= (mapv #(select-keys % [:tool_call_id :content]) results)
                     [{:tool_call_id "read-ticket" :content "ready"}])
          (throw (ex-info "The model did not receive the authorized ticket result" {})))
        [[{:role "assistant" :content "Ticket NW-17 is ready."}] "stop"])
    (throw (ex-info "Unexpected model request" {:count (count requests)}))))

(def ^:private recipes
  (array-map "basic-chat" [basic-chat/run chat-model 2 fixture/answer]
             "session-events" [session-events/run text-model 1 {:streamed fixture/answer :final fixture/answer}]
             "session-filesystem" [session-filesystem/run text-model 0 {:restored? true :cleaned? true}]
             "session-resume" [session-resume/run text-model 1 {:content fixture/answer :found? true :deleted? true}]
             "structured-output" [structured-output/run note-model 1 extracted-note]
             "lifecycle-hooks" [lifecycle-hooks/run hook-model 1 {:answer fixture/answer :prompt-hook-calls 1}]
             "tool-integration" [tool-integration/run ticket-model 2 "Ticket NW-17 is ready."]))

(defn- exercise! [recipe]
  (println "\nOffline smoke:" recipe)
  (let [[_ model request-count expected] (get recipes recipe)
        {:keys [event requests]}
        (fixture/exercise-turn! ["-m" "offline-examples" "--fixture-child" recipe] model)]
    (when-not (and (= event expected) (= request-count (count requests)))
      (throw (ex-info "Unexpected offline example result"
                      {:recipe recipe :expected expected :actual event :requests (count requests)})))
    (println "PASS:" recipe)))

(defn- run-child! [recipe base-url prompt]
  (let [[run] (get recipes recipe)
        options {:model "fixture/chat" :tools {:available-tools []} :events {:streaming? true}
                 :github {:capi {:enabled? false}}
                 :inference {:providers [{:name "fixture" :provider-type :openai :base-url base-url}]
                             :models [{:provider "fixture" :id "chat" :model-id "gpt-4o"
                                       :wire-model "probe-deployment"}]}}
        result (if (= recipe "session-filesystem")
                 (run options)
                 (copilot/with-client [client {:mode :empty :base-directory (System/getenv "HOME")
                                               :github {:use-logged-in-user? false}}]
                   (case recipe
                     "basic-chat" (run client options prompt "What was your previous answer?")
                     "structured-output" (run client options "The beta opens Friday. Kim will send invitations.")
                     "lifecycle-hooks" (run client options "Why do bridges have expansion joints?")
                     "tool-integration" (run client options "Check ticket NW-17 with ticket_status.")
                     (run client options prompt))))]
    (spit "result.edn" (pr-str result))))

(defn -main [& args]
  (try
    (cond
      (empty? args) (run! exercise! (keys recipes))
      (and (= 1 (count args)) (contains? recipes (first args))) (exercise! (first args))
      (and (= 4 (count args)) (= "--fixture-child" (first args))) (apply run-child! (rest args))
      :else (throw (ex-info "Expected an example name or no arguments" {:recipes (keys recipes)})))
    (finally (shutdown-agents))))
