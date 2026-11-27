(ns github.copilot-sdk.interaction-example-fixture
  "Local model conversations for the interaction and pending-tool examples."
  (:require [clojure.data.json :as json]
            [user-input]))

(defn- call [name arguments]
  [[{:role "assistant"
     :tool_calls [{:index 0 :id "demo-call" :type "function"
                   :function {:name name :arguments (json/write-str arguments)}}]}]
   "tool_calls"])

(defn- answer [messages expected response]
  (when-not (= (mapv (juxt :tool_call_id :content) (filter #(= (:role %) "tool") messages))
               [["demo-call" expected]])
    (throw (ex-info "The model did not receive the expected tool result"
                    {:expected expected :received (mapv :content (filter #(= (:role %) "tool") messages))})))
  [[{:role "assistant" :content response}] "stop"])

(defn- input-model [cases requests]
  (let [{:keys [tools messages]} (peek requests)]
    (when-not (= (mapv #(get-in % [:function :name]) tools) ["ask_user"])
      (throw (ex-info "Only ask_user should be available" {})))
    (let [[args expected reply] (nth cases (quot (dec (count requests)) 2))]
      (if (odd? (count requests)) (call "ask_user" args) (answer messages expected reply)))))

(def ^:private color {:question "Which color?" :choices ["Blue" "Green"]})
(def ^:private blue [color "User selected: Blue" "The chosen color is Blue."])
(def ^:private skipped [color "User responded: The user was unable to respond due to an error"
                        "No answer was available."])
(def ^:private style [{:question "Concise or verbose?" :choices ["concise" "verbose"]}
                      "User selected: concise" "Hello!"])

(defn run-stdin [client options prompt]
  (mapv (fn [line] (user-input/run-stdin client options prompt (constantly line))) ["Green" "Coral" nil]))

(defn- parcel-model [requests]
  (let [{:keys [tools messages]} (peek requests)]
    (when-not (= (mapv #(get-in % [:function :name]) tools) ["parcel_status"])
      (throw (ex-info "Only parcel_status should be available" {})))
    (case (count requests)
      1 (call "parcel_status" {:parcel "NW-17"})
      2 (answer messages "in transit" "Parcel NW-17 is in transit."))))

(def recipes
  {"user-input" [(partial input-model [blue skipped]) 4 {:answered (last blue) :skipped (last skipped)}]
   "user-input-simple" [(partial input-model [style]) 2 (last style)]
   "ask-user-failure" [(partial input-model [skipped]) 2 (last skipped)]
   "user-input-stdin" [(partial input-model [[color "User selected: Green" "Green."]
                                             [color "User responded: Coral" "Coral."]
                                             skipped])
                       6 ["Green." "Coral." (last skipped)]]
   "elicitation-provider" [(fn [_] (throw (ex-info "The application form needs no model" {})))
                           0 {:action :accept :content {"color" "Blue"}}]
   "manual-tool-resume" [parcel-model 2 "Parcel NW-17 is in transit."]})
