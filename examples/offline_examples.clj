(ns offline-examples
  "Run the examples with the repository's local model and isolated application JVM."
  (:require [basic-chat]
            [clojure.data.json :as json]
            [github.copilot-sdk :as copilot]
            [github.copilot-sdk.native-offline-fixture :as fixture]
            [session-events]
            [session-filesystem]
            [session-resume]
            [tool-integration]))

(defn- text-model [_]
  [[{:role "assistant" :content "Native "} {:content "turn complete."}] "stop"])

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
  (array-map "basic-chat" [basic-chat/run text-model 1 fixture/answer]
             "session-events" [session-events/run text-model 1 {:streamed fixture/answer :final fixture/answer}]
             "session-filesystem" [session-filesystem/run text-model 0 {:restored? true :cleaned? true}]
             "session-resume" [session-resume/run text-model 1 {:content fixture/answer :found? true :deleted? true}]
             "tool-integration" [tool-integration/run ticket-model 2 "Ticket NW-17 is ready."]))

(defn- exercise! [recipe]
  (let [[_ model request-count expected] (get recipes recipe)
        {:keys [event requests]}
        (fixture/exercise-turn! ["-m" "offline-examples" "--fixture-child" recipe] model)]
    (when-not (and (= event expected) (= request-count (count requests)))
      (throw (ex-info "Unexpected offline example result"
                      {:recipe recipe :expected expected :actual event :requests (count requests)})))
    (println recipe "=>" (pr-str event))))

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
                   (run client options prompt)))]
    (spit "result.edn" (pr-str result))))

(defn -main [& args]
  (try
    (cond
      (empty? args) (run! exercise! (keys recipes))
      (and (= 1 (count args)) (contains? recipes (first args))) (exercise! (first args))
      (and (= 4 (count args)) (= "--fixture-child" (first args))) (apply run-child! (rest args))
      :else (throw (ex-info "Expected an example name or no arguments" {:recipes (keys recipes)})))
    (finally (shutdown-agents))))
