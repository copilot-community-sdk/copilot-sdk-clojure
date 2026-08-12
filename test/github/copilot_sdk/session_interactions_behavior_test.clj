(ns github.copilot-sdk.session-interactions-behavior-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.public-behavior-support :as support]
            [github.copilot-sdk.session :as session]))

(use-fixtures :each support/with-piped-client)

(deftest ui-elicitation-sends-exact-request-and-normalizes-response
  (let [requests (atom [])
        _ (mock/set-request-hook!
           support/*mock-server*
           (fn [method params]
             (cond
               (= "session.create" method)
               {::mock/merge-response
                {:capabilities {:ui {:elicitation true}}}}

               (= "session.ui.elicitation" method)
               (do
                 (swap! requests conj [method params])
                 {::mock/merge-response
                  {:content {:environment "production"}}}))))
        copilot-session (sdk/create-session support/*test-client*
                                            {:session-id "elicitation-session"})
        schema {:type "object"
                :properties {"environment" {:type "string"
                                            :enum ["staging" "production"]}}
                :required ["environment"]}
        result (sdk/ui-elicitation! copilot-session
                                    {:message "Choose an environment"
                                     :requested-schema schema})]
    (is (= [["session.ui.elicitation"
             {:sessionId "elicitation-session"
              :message "Choose an environment"
              :requestedSchema
              {:type "object"
               :properties {:environment {:type "string"
                                          :enum ["staging" "production"]}}
               :required ["environment"]}}]]
           @requests))
    (is (= {:action "accept"
            :content {:environment "production"}}
           result))))

(deftest async-pending-tool-call-returns-raw-result-envelope-then-closes
  (let [requests (atom [])
        copilot-session (sdk/create-session support/*test-client*
                                            {:session-id "pending-tool-session"})
        _ (mock/set-request-hook!
           support/*mock-server*
           (fn [method params]
             (when (= "session.tools.handlePendingToolCall" method)
               (swap! requests conj [method params]))))
        envelope
        (support/read-value-then-close!!
         (sdk/<handle-pending-tool-call!
          copilot-session
          {:request-id "tool-request-1"
           :result "completed"}))]
    (is (= [["session.tools.handlePendingToolCall"
             {:sessionId "pending-tool-session"
              :requestId "tool-request-1"
              :result {:textResultForLlm "completed"
                       :resultType "success"
                       :toolTelemetry {}}}]]
           @requests))
    (is (= {:ok true} (:result envelope)))
    (is (nil? (:error envelope)))))

(deftest async-pending-permission-returns-raw-error-envelope-then-closes
  (let [requests (atom [])
        copilot-session (sdk/create-session support/*test-client*
                                            {:session-id "pending-permission-session"})
        _ (mock/set-request-hook!
           support/*mock-server*
           (fn [method params]
             (when (= "session.permissions.handlePendingPermissionRequest" method)
               (swap! requests conj [method params])
               (throw (ex-info "permission response rejected"
                               {:code -32060
                                :data {:reason "stale-request"}})))))
        envelope
        (support/read-value-then-close!!
         (sdk/<handle-pending-permission-request!
          copilot-session
          {:request-id "permission-request-1"
           :result {:kind :approve-once}}))]
    (is (= [["session.permissions.handlePendingPermissionRequest"
             {:sessionId "pending-permission-session"
              :requestId "permission-request-1"
              :result {:kind "approve-once"}}]]
           @requests))
    (is (= {:code -32060
            :message "permission response rejected"
            :data {:reason "stale-request"}}
           (:error envelope)))
    (is (nil? (:result envelope)))))

(deftest workspace-path-flows-from-session-producers-to-public-accessor
  (let [cases
        [{:label "sync create"
          :method "session.create"
          :session-id "workspace-create-sync"
          :produce #(sdk/create-session support/*test-client*
                                        {:session-id "workspace-create-sync"})}
         {:label "async create"
          :method "session.create"
          :session-id "workspace-create-async"
          :produce #(support/read-value-then-close!!
                     (sdk/<create-session support/*test-client*
                                          {:session-id "workspace-create-async"}))}
         {:label "sync resume"
          :method "session.resume"
          :session-id "workspace-resume-sync"
          :produce #(sdk/resume-session support/*test-client*
                                        "workspace-resume-sync" {})}
         {:label "async resume"
          :method "session.resume"
          :session-id "workspace-resume-async"
          :produce #(support/read-value-then-close!!
                     (sdk/<resume-session support/*test-client*
                                          "workspace-resume-async" {}))}]]
    (doseq [{:keys [label method session-id produce]} cases]
      (testing label
        (when (= method "session.resume")
          (swap! (:sessions support/*mock-server*)
                 assoc session-id {:id session-id}))
        (let [workspace (str "/workspaces/" session-id)]
          (mock/set-request-hook!
           support/*mock-server*
           (fn [request-method _]
             (when (= method request-method)
               {::mock/merge-response {:workspacePath workspace}})))
          (let [copilot-session (produce)]
            (is (= workspace (sdk/workspace-path copilot-session)))))))))

(deftest sessions-fork-normalizes-workspace-path-in-response
  (let [copilot-session (sdk/create-session support/*test-client*
                                            {:session-id "fork-source"})]
    (mock/set-request-hook!
     support/*mock-server*
     (fn [method _]
       (when (= "sessions.fork" method)
         {::mock/merge-response {:workspacePath "/workspaces/forked"}})))
    (is (= "/workspaces/forked"
           (:workspace-path (session/sessions-fork! copilot-session))))))
