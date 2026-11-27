(ns github.copilot-sdk.config-example-fixture
  "Local model transcripts for configuration, compaction and multi-agent examples."
  (:require [clojure.string :as str]
            [infinite-sessions]
            [multi-agent])
  (:import [java.util.concurrent CyclicBarrier TimeUnit]))

(defn- answer [text] [[{:role "assistant" :content text}] "stop"])

(defn- prompt [request] (:content (peek (:messages request))))

(defn- output-model [requests]
  (let [{:keys [tools messages]} (peek requests)]
    (when-not (= ["big_output"] (mapv #(get-in % [:function :name]) tools))
      (throw (ex-info "Only the demo tool should be available" {})))
    (case (count requests)
      1 [[{:role "assistant" :tool_calls [{:index 0 :id "spill" :type "function"
                                           :function {:name "big_output" :arguments "{}"}}]}] "tool_calls"]
      2 (let [[result & more] (filter #(= "tool" (:role %)) messages)
              text (:content result)]
          (when-not (and (empty? more) (= "spill" (:tool_call_id result))
                         (str/includes? text "Saved to:") (str/includes? text "/output/")
                         (str/includes? text "0123456789") (< (count text) 2000))
            (throw (ex-info "The model did not receive a spill reference and bounded preview" {})))
          (answer "DONE")))))

(def ^:private capitals ["Paris." "Tokyo." "Brasilia."])

(defn- capitals-model [requests]
  (let [i (dec (count requests))
        {:keys [tools messages] :as request} (peek requests)]
    (when-not (and (empty? tools)
                   (str/ends-with? (prompt request) (infinite-sessions/default-prompts i))
                   (= (subvec capitals 0 i) (mapv :content (filter #(= "assistant" (:role %)) messages))))
      (throw (ex-info "The capitals conversation did not retain its previous answers" {:turn (inc i)})))
    (answer (capitals i))))

(def ^:private findings
  (mapv vector multi-agent/default-topics
        ["Pure functions are easier to test." "Immutable values can be shared." "Concurrent writes need coordination."]))
(def ^:private analysis "All three findings concern how programs manage changing state.")
(def ^:private summary "Use immutable data and keep shared writes explicit.")

(defn- research-topic [request]
  (some #(when (str/ends-with? (prompt request) (str "Research: " %)) %) multi-agent/default-topics))

(defn- agent-model [requests]
  (let [n (count requests)
        {:keys [tools messages] :as request} (peek requests)
        instructions (->> messages (filter #(= "system" (:role %))) (map :content) (str/join "\n"))
        topic (research-topic request)
        report (str/join "\n" (map #(str/join ": " %) findings))
        [role input output] (case n
                              (1 2 3) ["Report one concise finding" (str "Research: " topic) (get (into {} findings) topic)]
                              4 ["Identify patterns across the findings" (str "Analyze these findings:\n" report) analysis]
                              5 ["Write a short executive summary" (str "Summarize this analysis:\n" analysis) summary])]
    (when-not (and (empty? tools) (not-any? #(= "assistant" (:role %)) messages)
                   (= (min n 3) (count (distinct (map research-topic (take 3 requests)))))
                   (or (> n 3) (not-any? #(str/includes? (prompt request) %)
                                         (remove #{topic} multi-agent/default-topics)))
                   (or (< n 5) (not-any? #(str/includes? (prompt request) (second %)) findings))
                   (some? output) (str/includes? instructions role) (str/ends-with? (prompt request) input))
      (throw (ex-info "Wrong agent, context, or upstream stage data" {:request n})))
    (answer output)))

(def recipes
  {"config-skill-output" [output-model 2 {:disabled-skill "garden-label" :spill-bytes 110000 :answer "DONE"}]
   "infinite-sessions" [capitals-model 3 {:answers capitals :compaction-events []}]
   "multi-agent" [agent-model 5 {:findings findings :analysis analysis :summary summary}]})

(defn parallel-research
  "Keep each research turn in its native prompt hook until three distinct sessions arrive."
  [client options & topics]
  (let [gate (CyclicBarrier. 3)
        sessions (atom #{})
        hook (fn [{:keys [prompt]} {:keys [session-id]}]
               (when (str/starts-with? prompt "Research: ")
                 (swap! sessions conj session-id)
                 (.await gate 10 TimeUnit/SECONDS))
               nil)
        result (apply multi-agent/run client (assoc-in options [:hooks :handlers :on-user-prompt-submitted] hook) topics)]
    (when-not (and (= 3 (count @sessions)) (not (.isBroken gate)))
      (throw (ex-info "Expected three overlapping research sessions" {})))
    result))
