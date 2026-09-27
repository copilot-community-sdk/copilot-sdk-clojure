(ns github.copilot-sdk.integration.upstream-1-0-89-5-test
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.generated.event-specs :as wire]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]))

(use-fixtures :each with-mock-server)

(def ^:private parent-call-events
  [[:copilot/assistant.turn_start {:turn-id "turn"}
    ::specs/assistant.turn_start-data ::wire/assistant.turn_start-data]
   [:copilot/assistant.turn_end {:turn-id "turn"}
    ::specs/assistant.turn_end-data ::wire/assistant.turn_end-data]
   [:copilot/model.call_failure {:source "subagent"}
    ::specs/model.call_failure-data ::wire/model.call_failure-data]])

(defn- valid? [spec data]
  (and (some? (s/get-spec spec)) (s/valid? spec data)))

(def ^:private workflow-completed
  {:type "workflow_completed" :run-id "run" :workflow-name "review"
   :status "paused" :consumed-subagents 0 :elapsed-ms 0
   :consumed-nano-aiu 0 :attempt 1})

(deftest workflow-notifications-validate-pause-metadata
  (doseq [spec [::specs/system.notification-data ::wire/system.notification-data]
          [pause-info expected] [[::absent true] [{:type "user"} true]
                                 [{:type "checkpoint" :key ""} true]
                                 [nil false] [false false] [{} false]
                                 [{:type "checkpoint"} false]
                                 [{:type "checkpoint" :key nil} false]
                                 [{:type "unknown"} false]]]
    (let [kind (cond-> workflow-completed
                 (not= pause-info ::absent) (assoc :pause-info pause-info))]
      (is (= (valid? spec {:content "Paused" :kind kind}) expected)
          (str spec " " kind)))))

(deftest workflow-run-correlation-preserves-the-legacy-history-field
  (doseq [spec [::specs/subagent.started-data ::wire/subagent.started-data]
          field [:workflow-run-id :factory-run-id]
          [run-id expected] [[::absent true] ["" true] ["run" true]
                             [nil false] [false false] [[] false] [{} false]]]
    (let [data (cond-> {:tool-call-id "task" :agent-name "reviewer"
                        :agent-display-name "Reviewer" :agent-description "Review"
                        :factory-run-id "legacy-run"}
                 (= field :factory-run-id) (dissoc :factory-run-id)
                 (not= run-id ::absent) (assoc field run-id))]
      (is (= (valid? spec data) expected) (str spec " " data)))))

(deftest parent-call-metadata-is-optional-and-non-null
  (doseq [[event-type base idiom wire] parent-call-events
          spec [idiom wire]
          [parent expected] [[::absent true] ["" true] ["task-1" true]
                             [nil false] [false false] [1 false] [[] false] [{} false]]]
    (let [data (cond-> base
                 (not= parent ::absent) (assoc :parent-tool-call-id parent))]
      (is (= (valid? spec data) expected) (str event-type " " spec " " data)))))

(deftest auto-tier-recommendation-is-a-model-change-source
  (doseq [spec [::specs/session.model_change-data ::wire/session.model_change-data]]
    (is (valid? spec {:new-model "model" :source "auto_tier_recommendation"}))
    (is (not (valid? spec {:new-model "model" :source nil})))
    (is (not (valid? spec {:new-model "model" :source "unknown-source"})))))

(deftest metadata-survives-live-and-history-delivery
  (let [session (sdk/create-session *test-client* {})
        session-id (sdk/session-id session)
        events (sdk/subscribe-events session)
        cases
        (concat
         (for [[event-type base spec] parent-call-events
               parent [::absent "" "task-1"]
               :let [data (cond-> (if (:turn-id base)
                                    {:turnId (:turn-id base)}
                                    base)
                            (not= parent ::absent) (assoc :parentToolCallId parent))
                     expected (cond-> base
                                (not= parent ::absent) (assoc :parent-tool-call-id parent))]]
           [event-type data expected spec])
         [[:copilot/session.model_change
           {:newModel "model" :source "auto_tier_recommendation"}
           {:new-model "model" :source "auto_tier_recommendation"}
           ::specs/session.model_change-data]
          [:copilot/subagent.started
           {:toolCallId "task" :agentName "reviewer" :agentDisplayName "Reviewer"
            :agentDescription "Review" :factoryRunId "legacy-run" :workflowRunId "run"}
           {:tool-call-id "task" :agent-name "reviewer" :agent-display-name "Reviewer"
            :agent-description "Review" :factory-run-id "legacy-run" :workflow-run-id "run"}
           ::specs/subagent.started-data]
          [:copilot/system.notification
           {:content "Paused"
            :kind {:type "workflow_completed" :runId "run" :workflowName "review"
                   :status "paused" :consumedSubagents 0 :elapsedMs 0
                   :consumedNanoAiu 0 :attempt 1 :pauseInfo {:type "checkpoint" :key ""}
                   :failure {:Outer_Key {:inner_key nil}}}}
           {:content "Paused"
            :kind (assoc workflow-completed
                         :pause-info {:type "checkpoint" :key ""}
                         :failure {:Outer_Key {:inner_key nil}})}
           ::specs/system.notification-data]])]
    (try
      (doseq [[event-type data expected spec] cases]
        (testing (str event-type " " data)
          (mock/send-session-event! *mock-server* session-id event-type data)
          (let [event (await-event-type! events event-type 5000)]
            (is (= (:data event) expected))
            (is (valid? spec (:data event))))
          (mock/set-session-messages! *mock-server* session-id
                                      [{:type (name event-type) :data data}])
          (let [[event] (sdk/get-messages session)]
            (is (= (:type event) event-type))
            (is (= (:data event) expected))
            (is (valid? spec (:data event))))))
      (finally
        (sdk/unsubscribe-events! session events)
        (sdk/disconnect! session)))))

(deftest workflow-registration-replaces-the-retired-factory-spelling
  (doseq [value [[] nil false {}]]
    (is (= (s/valid? ::specs/join-session-config {:workflows value})
           (vector? value)))
    (doseq [spec [::specs/session-config ::specs/resume-session-config]]
      (is (not (s/valid? spec {:workflows value}))))
    (is (not (s/valid? ::specs/join-session-config {:factories value}))))
  (is (some? (io/resource "github/copilot_sdk/workflow.clj")))
  (is (nil? (io/resource "github/copilot_sdk/factory.clj")))
  (is (nil? (ns-resolve 'github.copilot-sdk 'pause-workflow-run!)))
  (is (nil? (ns-resolve 'github.copilot-sdk.workflow 'pause!)))
  (doseq [suffix ["define-workflow" "workflow-terminal-status?"
                  "run-workflow!" "<run-workflow!" "resume-workflow!" "<resume-workflow!"
                  "get-workflow-run" "<get-workflow-run"
                  "wait-for-workflow-run!" "<wait-for-workflow-run!"
                  "list-workflow-runs" "<list-workflow-runs"
                  "get-workflow-run-detail" "<get-workflow-run-detail"
                  "get-workflow-run-progress" "<get-workflow-run-progress"
                  "cancel-workflow-run!" "<cancel-workflow-run!"]]
    (is (some? (ns-resolve 'github.copilot-sdk (symbol suffix))) suffix))
  (doseq [suffix ["define-factory" "run-factory!" "resume-factory!"
                  "wait-for-factory-run!" "get-factory-run" "cancel-factory-run!"]]
    (is (nil? (ns-resolve 'github.copilot-sdk (symbol suffix))) suffix)))

(deftest paused-workflow-attempts-are-terminal
  (let [terminal? (ns-resolve 'github.copilot-sdk 'workflow-terminal-status?)]
    (is (some? terminal?))
    (when terminal?
      (doseq [status [:completed :halted :paused :cancelled :error "paused"]]
        (is (true? (terminal? status))))
      (doseq [status [:pending :running]]
        (is (false? (terminal? status)))))))
