(ns multi-agent
  "Run independent research turns in parallel, then pass their findings to an analyst and a writer."
  (:require [basic-chat]
            [clojure.string :as str]
            [copilot.sdk :as copilot]))

(def default-topics
  ["functional programming benefits" "immutable data structures" "concurrent programming challenges"])

(defn- agent-options [options name prompt]
  (assoc options :tools {:available-tools []}
         :agents {:definitions [{:name name :prompt prompt :tools []}] :selected name}))

(defn- finish-research [topics sessions]
  (let [replies (mapv (fn [session topic]
                        (println "Research:" topic)
                        (copilot/start-turn! session {:prompt (str "Research: " topic) :timeout-ms 60000}))
                      sessions topics)]
    (mapv (fn [topic reply]
            (let [finding (get-in (copilot/await!! reply) [:data :content])]
              (when (str/blank? finding)
                (throw (ex-info "Researcher returned no finding" {:topic topic})))
              (println "Finding for" topic ":" finding)
              [topic finding]))
          topics replies)))

(defn- research-batch [client options topics]
  ;; Hold the scopes open until every result is collected or one of the turns fails.
  (letfn [(open-sessions [remaining sessions]
            (if (seq remaining)
              (copilot/with-session [session client options]
                (open-sessions (next remaining) (conj sessions session)))
              (finish-research topics sessions)))]
    (open-sessions topics [])))

(defn run [client options & topics]
  (let [researcher (agent-options options "researcher" "Report one concise finding on the given topic.")
        analyst (agent-options options "analyst" "Identify patterns across the findings in two sentences.")
        writer (agent-options options "writer" "Write a short executive summary of the analysis.")
        findings (into [] (mapcat #(research-batch client researcher %))
                       (partition-all 3 (or (seq topics) default-topics)))
        report (str/join "\n" (map (fn [[topic finding]] (str topic ": " finding)) findings))
        analysis (copilot/with-session [session client analyst]
                   (basic-chat/ask session (str "Analyze these findings:\n" report)))
        summary (copilot/with-session [session client writer]
                  (basic-chat/ask session (str "Summarize this analysis:\n" analysis)))]
    {:findings findings :analysis analysis :summary summary}))
