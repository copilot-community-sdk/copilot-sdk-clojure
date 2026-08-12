(ns github.copilot-sdk.integration.session-rpc-test
  "Focused integration tests using the mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.tools :as tools]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support
             :refer [*mock-server*
                     *test-client*
                     await-value!
                     await-atom!
                     await-event-type!
                     observe-take-attempts
                     with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]))

(use-fixtures :each with-mock-server)

(deftest test-history-clear-context
  (let [clear-context (ns-resolve 'github.copilot-sdk 'history-clear-context!)
        seen (atom [])]
    (is (some? clear-context))
    (when clear-context
      (mock/set-request-hook!
       *mock-server*
       (fn [method params]
         (when (= "session.history.clearContext" method)
           (reset! seen params))))
      (let [copilot-session
            (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})]
        (is (= {:messages-cleared 3}
               (clear-context copilot-session "Start from this requirement")))
        (is (= {:sessionId (sdk/session-id copilot-session)
                :prompt "Start from this requirement"}
               @seen))))))

(deftest test-mode-get
  (testing "mode-get calls session.mode.get RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/mode-get session)]
        (is (some? result))
        (is (= "interactive" (:mode result)))
        (is (some #(= "session.mode.get" (:method %)) @requests))))))

(deftest test-mode-set
  (testing "mode-set! calls session.mode.set RPC with mode param"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/mode-set! session "plan")
      (let [mode-rpcs (filter #(= "session.mode.set" (:method %)) @requests)]
        (is (= 1 (count mode-rpcs)))
        (is (= "plan" (:mode (:params (first mode-rpcs)))))))))

(deftest test-plan-read
  (testing "plan-read calls session.plan.read RPC and returns normalized shape"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/plan-read session)]
        (is (some? result))
        (is (some #(= "session.plan.read" (:method %)) @requests))
        ;; Mock returns {:exists false :content nil :filePath nil}
        ;; plan-read renames :exists → :exists? and wire->clj converts :filePath → :file-path
        (is (contains? result :exists?) ":exists key should be renamed to :exists?")
        (is (false? (:exists? result)))
        (is (nil? (:content result)))))))

(deftest test-plan-update
  (testing "plan-update! calls session.plan.update RPC with content"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/plan-update! session "# My Plan\n\nStep 1: ...")
      (let [plan-rpcs (filter #(= "session.plan.update" (:method %)) @requests)]
        (is (= 1 (count plan-rpcs)))
        (is (= "# My Plan\n\nStep 1: ..." (:content (:params (first plan-rpcs)))))))))

(deftest test-plan-delete
  (testing "plan-delete! calls session.plan.delete RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/plan-delete! session)
      (is (some #(= "session.plan.delete" (:method %)) @requests)))))

(deftest test-workspace-list-files
  (testing "workspace-list-files calls session.workspace.listFiles RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/workspace-list-files session)]
        (is (some? result))
        (is (some #(= "session.workspace.listFiles" (:method %)) @requests))))))

(deftest test-workspace-read-file
  (testing "workspace-read-file calls session.workspace.readFile RPC with path"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/workspace-read-file session "notes.md")
      (let [rpcs (filter #(= "session.workspace.readFile" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "notes.md" (:path (:params (first rpcs)))))))))

(deftest test-workspace-create-file
  (testing "workspace-create-file! calls session.workspace.createFile RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/workspace-create-file! session "test.txt" "content here")
      (let [rpcs (filter #(= "session.workspace.createFile" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "test.txt" (:path (:params (first rpcs)))))
        (is (= "content here" (:content (:params (first rpcs)))))))))

(deftest test-agent-list
  (testing "agent-list calls session.agent.list RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (let [result (session/agent-list session)]
        (is (some? result))
        (is (some #(= "session.agent.list" (:method %)) @requests))))))

(deftest test-agent-select
  (testing "agent-select! calls session.agent.select RPC with agent name"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/agent-select! session "researcher")
      (let [rpcs (filter #(= "session.agent.select" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "researcher" (:name (:params (first rpcs)))))))))

(deftest test-agent-deselect
  (testing "agent-deselect! calls session.agent.deselect RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/agent-deselect! session)
      (is (some #(= "session.agent.deselect" (:method %)) @requests)))))

(deftest test-fleet-start
  (testing "fleet-start! calls session.fleet.start RPC with session-id forced"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)]
      ;; Pass params that attempt to override session-id
      (session/fleet-start! session {:prompt "do stuff" :session-id "evil-override"})
      (let [rpcs (filter #(= "session.fleet.start" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        ;; Session-id must be the real one, not the override
        (is (= session-id (:sessionId (:params (first rpcs)))))
        (is (= "do stuff" (:prompt (:params (first rpcs)))))))))

(deftest test-mcp-config-list
  (testing "mcp-config-list calls mcp.config.list RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-list *test-client*)]
      (is (some? result))
      (is (some #(= "mcp.config.list" (:method %)) @requests)))))

(deftest test-mcp-config-add
  (testing "mcp-config-add! calls mcp.config.add RPC with params"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-add! *test-client*
                                         {:name "my-server" :command "npx" :args ["-y" "server"]})]
      (is (some? result))
      (let [rpcs (filter #(= "mcp.config.add" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "my-server" (:name (:params (first rpcs)))))))))

(deftest test-mcp-config-update
  (testing "mcp-config-update! calls mcp.config.update RPC with params"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-update! *test-client*
                                            {:name "my-server" :tools ["read_file"]})]
      (is (some? result))
      (let [rpcs (filter #(= "mcp.config.update" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "my-server" (:name (:params (first rpcs)))))))))

(deftest test-mcp-config-remove
  (testing "mcp-config-remove! calls mcp.config.remove RPC with params"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          result (client/mcp-config-remove! *test-client* {:name "my-server"})]
      (is (some? result))
      (let [rpcs (filter #(= "mcp.config.remove" (:method %)) @requests)]
        (is (= 1 (count rpcs)))
        (is (= "my-server" (:name (:params (first rpcs)))))))))

(deftest test-agent-get-current
  (testing "agent-get-current calls session.agent.getCurrent RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})
          result (session/agent-get-current session)]
      (is (some? result))
      (is (some #(= "session.agent.getCurrent" (:method %)) @requests)))))

(deftest test-agent-reload
  (testing "agent-reload! calls session.agent.reload RPC"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/agent-reload! session)
      (is (some #(= "session.agent.reload" (:method %)) @requests)))))

(deftest test-enable-config-discovery-on-wire
  (testing "enableConfigDiscovery is forwarded in session.create (upstream PR #1044)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :enable-config-discovery true})
          create-params (get @seen "session.create")]
      (is (true? (:enableConfigDiscovery create-params)))))

  (testing "enableConfigDiscovery is forwarded in session.resume (upstream PR #1044)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :enable-config-discovery false})
          resume-params (get @seen "session.resume")]
      (is (false? (:enableConfigDiscovery resume-params)))))

  (testing "enableConfigDiscovery is omitted when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :enableConfigDiscovery))))))

(deftest test-model-capabilities-on-wire
  (testing "fixed-precision Long limits validate while arbitrary-precision integers do not"
    (let [limits {:max-prompt-tokens (long 120000)
                  :max-output-tokens (long 16000)
                  :max-context-window-tokens (long 136000)
                  :vision {:max-prompt-images (long 5)
                           :max-prompt-image-size (long 1048576)}}]
      (is (s/valid? ::specs/model-capabilities {:limits limits}))
      (is (not (s/valid? ::specs/model-capabilities
                         {:limits (assoc limits :max-prompt-tokens 120000N)})))))

  (testing "modelCapabilities is forwarded in session.create (upstream PR #1029)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :model-capabilities {:supports {:vision true}}})
          create-params (get @seen "session.create")]
      (is (= true (get-in create-params [:modelCapabilities :supports :vision])))))

  (testing "modelCapabilities is forwarded in session.resume (upstream PR #1029)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.resume"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :model-capabilities {:supports {:reasoning-effort true}}})
          resume-params (get @seen "session.resume")]
      (is (= true (get-in resume-params [:modelCapabilities :supports :reasoningEffort])))))

  (testing "modelCapabilities is omitted when not set"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server* (fn [method params]
                                                    (when (#{"session.create"} method)
                                                      (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :modelCapabilities))))))

(deftest test-switch-model-with-model-capabilities
  (testing "switch-model! forwards modelCapabilities (upstream PR #1029)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4"
                               {:model-capabilities {:supports {:vision false}}})]
      (is (= false (get-in @captured-params [:modelCapabilities :supports :vision])))))

  (testing "set-model! forwards modelCapabilities (alias for switch-model!)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/set-model! session "gpt-5.4"
                            {:model-capabilities {:supports {:vision true}}})]
      (is (= true (get-in @captured-params [:modelCapabilities :supports :vision]))))))

(deftest test-switch-model-with-context-tier
  (testing "switch-model! forwards contextTier (upstream PR #1522)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4" {:context-tier :long-context})]
      (is (= "long_context" (:contextTier @captured-params))
          "context-tier keyword must convert to the underscore wire value")))

  (testing "switch-model! forwards reasoningSummary (setModel parity)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4" {:reasoning-summary "concise"})]
      (is (= "concise" (:reasoningSummary @captured-params)))))

  (testing "set-model! forwards contextTier (alias for switch-model!)"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/set-model! session "gpt-5.4" {:context-tier :default})]
      (is (= "default" (:contextTier @captured-params)))))

  (testing "switch-model! omits contextTier when :context-tier is nil"
    (let [captured-params (atom nil)
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (= method "session.model.switchTo")
                                        (reset! captured-params params))))
          session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          _ (sdk/switch-model! session "gpt-5.4" {:context-tier nil})]
      (is (not (contains? @captured-params :contextTier))
          "nil :context-tier must be omitted, not sent as contextTier: null (switchTo schema has no null tier)"))))

(deftest test-model-switch-context-tier-nil-under-instrumentation
  (let [instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
        unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)
        captured (atom [])]
    (mock/set-request-hook!
     *mock-server*
     (fn [method params]
       (when (= method "session.model.switchTo")
         (swap! captured conj params))))
    (instrument-all!)
    (try
      (let [session (sdk/create-session *test-client*
                                        {:on-permission-request sdk/approve-all})]
        (sdk/switch-model! session "gpt-5.4" {:context-tier nil})
        (sdk/set-model! session "gpt-5.4" {:context-tier nil})
        (is (= 2 (count @captured)))
        (is (every? #(not (contains? % :contextTier)) @captured)
            "instrumented switch/set must accept nil and omit contextTier"))
      (finally
        (unstrument-all!)))))

(deftest test-history-compact-rpc-name
  (testing "compaction-compact! uses session.history.compact RPC (upstream #1039)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/compaction-compact! session)
      (is (some #(= "session.history.compact" (:method %)) @requests))
      (is (not (some #(= "session.compaction.compact" (:method %)) @requests))))))

(deftest test-history-truncate-rpc
  (testing "history-truncate! calls session.history.truncate RPC (upstream #1039)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/history-truncate! session)
      (is (some #(= "session.history.truncate" (:method %)) @requests)))))

(deftest test-sessions-fork-rpc
  (testing "sessions-fork! calls sessions.fork RPC (upstream #1039)"
    (let [requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj {:method method :params params})))
          session (sdk/create-session *test-client*
                                      {:on-permission-request sdk/approve-all})]
      (session/sessions-fork! session)
      (is (some #(= "sessions.fork" (:method %)) @requests)))))
