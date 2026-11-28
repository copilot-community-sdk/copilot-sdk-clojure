(ns github.copilot-sdk.lifecycle-example-fixture
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [lifecycle-hooks :as hooks]))

(def ^:private bridge-answer "Steel expands when it warms up. Expansion joints give a bridge room to move without buckling.")

(defn- model [requests]
  (let [{:keys [tools messages]} (peek requests)
        names (mapv #(get-in % [:function :name]) tools)
        last-message (peek messages)
        content (:content last-message)
        note "Steel expands when it gets warm."]
    (case (count requests)
      1 (do (when-not (and (= names ["task"])
                           (= ["reader"] (get-in tools [0 :function :parameters :properties :agent_type :enum]))
                           (str/includes? content hooks/audience-brief))
              (throw (ex-info "Expected the prompt hook and only the reader agent" {})))
            [[{:role "assistant"
               :tool_calls [{:index 0 :id "reader-note" :type "function"
                             :function {:name "task" :arguments (json/write-str {:agent_type "reader" :name "reader"
                                                                                 :description "Explain bridge joints"
                                                                                 :prompt "Why do bridges have expansion joints?"})}}]}]
             "tool_calls"])
      2 (do (when-not (and (empty? names) (str/includes? content hooks/reader-brief))
              (throw (ex-info "Expected the subagent-start context and no child tools" {})))
            [[{:role "assistant" :content note}] "stop"])
      3 (do (when-not (= (select-keys last-message [:role :tool_call_id :content])
                         {:role "tool" :tool_call_id "reader-note" :content note})
              (throw (ex-info "The writer did not receive the reader's note" {})))
            [[{:role "assistant" :content bridge-answer}] "stop"]))))

(def recipe
  [model 3 {:answer bridge-answer
            :hooks #{:session-start :prompt :pre-tool :post-tool :subagent-start :subagent-stop :agent-stop :session-end}}])
