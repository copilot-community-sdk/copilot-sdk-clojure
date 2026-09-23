(ns github.copilot-sdk.integration.stable-sync-cb6fc66-test
  "Exact-pin public-surface inventory; external source verification is opt-in."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.integration.stable-sync-support :as ss]))

(def ^:private resource "resources/stable_upstream_delta_cb6fc66.edn")
(def ^:private base "39fe821dec17fc20bf92250ab3e19935aaf2e6f5")
(def ^:private target "cb6fc666cc45175adb11fa9e5021b96d7d37d298")
(def ^:private clojure-base "dcafef62ee16eea7437fae52a439f3ffc6559c6d")
(def ^:private implementation "40d684aeb2443ce5713649218f7e078d2c19c2bb")
(def ^:private sealed-artifact-paths
  #{".copilot-schema-version" ".github/workflows/ci.yml" "CHANGELOG.md" "build.clj"
    "doc/api/API.html" "doc/reference/API.md" "resources/github/copilot_sdk/api_surface.edn"
    "schemas/README.md" "schemas/api.schema.json" "schemas/session-events.schema.json"
    "script/codegen/coercions.edn" "script/codegen/emit_specs.clj" "script/generate_docs.clj"
    "src/github/copilot_sdk.clj" "src/github/copilot_sdk/generated/coerce.clj"
    "src/github/copilot_sdk/generated/event_metadata.clj"
    "src/github/copilot_sdk/generated/event_specs.clj"
    "src/github/copilot_sdk/instrument.clj" "src/github/copilot_sdk/protocol.clj"
    "src/github/copilot_sdk/session.clj" "src/github/copilot_sdk/specs.clj"
    "src/github/copilot_sdk/util.clj"
    "test/github/copilot_sdk/codegen_test.clj" "test/github/copilot_sdk/docs_links_test.clj"
    "test/github/copilot_sdk/integration/schema_1_0_89_test.clj"
    "test/github/copilot_sdk/integration/stable_sync_39fe821_test.clj"
    "test/github/copilot_sdk/integration/stable_sync_ca166d3_test.clj"
    "test/github/copilot_sdk/integration/stable_sync_support.clj"})
(def ^:private commits
  ["cb2a8cc60ee9f4a561b7f36af6eb2163c2caf78e"
   "51a0394ecca0ea12fe3f32491e81a2001e99e1ad"
   "7e478249a28cfb8aa4796298e38747f03c5df8b7"
   "cb6fc666cc45175adb11fa9e5021b96d7d37d298"])
(def ^:private classifications
  #{:stable-public :experimental :internal :generated-only :language-specific})
(def ^:private event-path "nodejs/src/generated/session-events.ts")
(def ^:private rpc-path "nodejs/src/generated/rpc.ts")
(def ^:private workflow-exports
  #{"defineWorkflow" "WorkflowResumeError" "isWorkflowRunTerminal"
    "SessionWorkflowApi" "WorkflowAgentOptions" "WorkflowAgentSummary"
    "WorkflowContext" "WorkflowDefinition" "WorkflowHandle" "WorkflowJsonSchema"
    "WorkflowLimitOverrides" "WorkflowLimits" "WorkflowListRunsOptions"
    "WorkflowMeta" "WorkflowPhaseObservation" "WorkflowPhaseStatus"
    "WorkflowPipelineStage" "WorkflowProgressLine" "WorkflowProgressPage"
    "WorkflowResumeErrorCode" "WorkflowResumeOptions" "WorkflowRunDetail"
    "WorkflowRunOptions" "WorkflowRunResult" "WorkflowRunStatus"
    "WorkflowRunSummary" "WorkflowRunsPage" "WorkflowStepOptions"})
(def ^:private event-exports
  #{"ModelDeselectedData" "ModelDeselectedEvent" "ModelDeselectedReason"
    "SystemMessageContentBlock"})
(def ^:private added-spec-keys
  #{:github.copilot-sdk.specs/session.model_deselected-data
    :github.copilot-sdk.specs/model-deselected-reason
    :github.copilot-sdk.specs/system.message-data
    :github.copilot-sdk.specs/system-message-content-block
    :github.copilot-sdk.specs/content-blocks
    :github.copilot-sdk.specs/cache-breakpoint
    :github.copilot-sdk.specs/is-static})
(def ^:private rpc-added
  #{"AcceptedEnqueueCommandResult" "UnsupportedEnqueueCommandResult"
    "ConnectorAccountRequest" "ConnectorAuthorizationRequirement"
    "ConnectorAuthorizationScope" "ConnectorAvailability" "ConnectorCapabilities"
    "ConnectorCatalogEntry" "ConnectorCatalogResult" "ConnectorCatalogStatus"
    "ConnectorConnectRequest" "ConnectorConnectResult" "ConnectorContinueRequest"
    "ConnectorDisconnectResult" "ConnectorMcpStatus" "ConnectorReconcileRequest"
    "ConnectorRuntimeStatus" "ConnectorStatus" "ProviderSyncRequest" "ProviderSyncResult"})
(def ^:private rpc-changed
  #{"AgentInfo" "CatalogCapability" "CopilotUserResponse" "EnqueueCommandResult"
    "ModelCapabilitiesOverrideSupports" "ModelCapabilitiesSupports"
    "ModelSwitchToRequest" "PermissionPathsList" "ProviderModelConfig"
    "SessionOpenOptions" "SessionUpdateOptionsParams" "SkillsDiscoverRequest"
    "SkillsGetDiscoveryPathsRequest" "SystemMessageBlock" "Tool"})
(def ^:private authority-paths
  #{"nodejs/package.json" "nodejs/src/index.ts" "nodejs/src/types.ts"
    "nodejs/src/client.ts" "nodejs/src/session.ts" "nodejs/src/extension.ts"
    "nodejs/src/toolSet.ts" "nodejs/src/factory.ts" "nodejs/src/canvas.ts"
    "nodejs/src/workflow.ts" "nodejs/src/generated/session-events.ts"
    "nodejs/src/generated/rpc.ts"})
(def ^:private internal-extension-signature
  "async resumeSessionForExtension(sessionId: string, config: ResumeSessionConfig, contributions: FactoryHandle[] | ExtensionOrchestrationContributions = {}, extensionOptions?: ExtensionJoinOptions): Promise<CopilotSession>")

(defn- report []
  (or (ss/read-resource resource)
      (throw (ex-info "Missing exact-pin inventory" {:resource resource}))))

(defn- local-source [commit path]
  (ss/shell-output "git" "show" (str commit ":" path)))

(defn- recorded-test? [source test-symbol]
  (and (str/includes? source (str "(ns " (namespace test-symbol)))
       (boolean
        (re-find (re-pattern (str "(?m)^\\(deftest\\s+"
                                  (java.util.regex.Pattern/quote (name test-symbol))
                                  "\\s"))
                 source))))

(defn- fingerprint [items]
  [(count items) (ss/sha256-lines (sort items))])

(defn- group-symbols [report groups]
  (into #{} (mapcat #(get-in report [:symbol-groups % :symbols])) groups))

(defn- declaration-symbols [by-class]
  (into #{} cat (vals by-class)))

(defn- field-signatures [source class-name]
  (let [body (second
              (re-find (re-pattern (str "(?ms)^export class " class-name
                                        "\\b[^\\{]*\\{(.*?)^\\}"))
                       source))]
    ;; Include parameter properties and unannotated internal fields, then
    ;; classify them explicitly; method-only inventories miss readonly APIs.
    (into #{}
          (map #(str/trim (or (second %) (nth % 2))))
          (re-seq #"(?m)^    ((?:(?:public|readonly|static)\s+)*[A-Za-z_$][\w$]*[?!]?:[^\n=;]+)(?:[=;])|^        (public readonly [A-Za-z_$][\w$]*[?!]?:[^\n,]+),"
                  body))))

(deftest pins-history-and-sealed-implementation
  (let [r (report)
        history (get-in r [:certification :historical-oracle])]
    (is (= (get-in r [:certification :clojure-base-commit]) clojure-base))
    (is (= (get-in r [:upstream :base-commit]) base))
    (is (= (get-in r [:upstream :target-commit]) target))
    (is (= history
           {:resource "resources/stable_upstream_delta_39fe821.edn"
            :sha256 "b4c6ad6adf7909c25e0fce0436af6ab9b95721bbf6912f98a076845b035c6dce"}))
    (is (= (ss/sha256-resource (:resource history)) (:sha256 history)))
    (is (= (ss/git-file-sha256 clojure-base (str "test/" (:resource history)))
           (:sha256 history)))
    (is (= (get-in r [:certification :local-artifact-seal])
           {:status :sealed :commit implementation}))
    (is (= (ss/shell-output "git" "merge-base" "--is-ancestor" implementation "HEAD") ""))
    (is (= (set (keys (:sealed-local-artifacts r))) sealed-artifact-paths))
    ;; Certificate files cannot participate in their own implementation seal.
    (is (set/subset?
         (disj (set (ss/git-lines "." "diff" "--name-only" clojure-base implementation))
               (str "test/" resource)
               "test/github/copilot_sdk/integration/stable_sync_cb6fc66_test.clj")
         sealed-artifact-paths))
    (doseq [[path expected-hash] (:sealed-local-artifacts r)]
      (testing path
        (is (re-matches #"[0-9a-f]{64}" expected-hash))
        (is (= (ss/git-file-sha256 implementation path) expected-hash))))
    (is (= (:version r) {:sdk "1.0.14.0" :changed? false :release-required? false}))
    (is (= (get-in r [:upstream :release-tag])
           {:name "v1.0.14" :commit "e60d9037353249ef16b349eb4012e8c1d113fda5"}))
    (is (= (get-in r [:upstream :runtime-version])
           (local-source implementation ".copilot-schema-version") "1.0.89-0"))
    (is (str/includes? (local-source implementation "build.clj") "(def version \"1.0.14.0\")"))
    (is (str/includes? (local-source implementation ".github/workflows/ci.yml") target))))

(deftest every-commit-and-path-has-a-deliberate-classification
  (let [{:keys [upstream commit-classifications changed-paths]} (report)]
    (is (= (mapv :commit commit-classifications) commits))
    (is (= (mapv :classification commit-classifications)
           [:language-specific :stable-public :internal :generated-only]))
    (is (= (:commit-count upstream) (count commits) 4))
    (is (= (:count changed-paths) 582
           (reduce + (vals (:classification-counts changed-paths)))))
    (is (= (:hash-format upstream) (:hash-format changed-paths)
           :newline-joined-without-trailing-newline))
    (doseq [row (concat commit-classifications (:prefix-classifications changed-paths))]
      (is (classifications (:classification row)))
      (is (not (str/blank? (:reason row)))))
    (is (every? classifications (vals (:exact-classifications changed-paths))))
    (when-let [repo (ss/upstream-repo-or-skip "cb6fc66 commit/path classification")]
      (let [actual-commits (ss/git-lines repo "rev-list" "--reverse" (str base ".." target))
            paths (ss/git-lines repo "diff" "--name-only" base target)
            classified (map #(ss/classify-path changed-paths %) paths)]
        (is (= actual-commits commits))
        (is (= (ss/sha256-items actual-commits) (:commits-sha256 upstream)))
        (is (= [(count paths) (ss/sha256-items paths)]
               [(:count changed-paths) (:sha256 changed-paths)]))
        (is (every? classifications classified))
        (is (= (frequencies classified) (:classification-counts changed-paths)))
        (is (set/subset? (set (keys (:exact-classifications changed-paths))) (set paths))
            "Exact classifications must not become stale")
        (doseq [{:keys [commit subject source-url changed-path-count changed-paths-sha256]}
                commit-classifications
                :let [paths (ss/git-lines repo "diff-tree" "--no-commit-id" "--name-only" "-r" commit)]]
          (is (= (ss/git-output repo "show" "-s" "--format=%s" commit) subject))
          (is (= [(count paths) (ss/sha256-items paths)]
                 [changed-path-count changed-paths-sha256]))
          (is (= source-url
                 (if (= commit (first commits))
                   "https://github.com/github/copilot-sdk/pull/2737"
                   (str "https://github.com/github/copilot-sdk/commit/" commit)))))))))

(deftest stable-contract-matrix-and-exclusions-have-no-gaps
  (let [r (report)
        groups (:symbol-groups r)
        deltas (:stable-deltas r)
        common (:event-contract r)
        test-source (local-source implementation
                                  "test/github/copilot_sdk/integration/schema_1_0_89_test.clj")]
    (is (= (:unclassified-deltas r) []))
    (is (= (set (map :id deltas))
           #{:events/model-deselected :events/tool-title :events/system-message-content-blocks}))
    (is (= (get groups :events) {:classification :stable-public :symbols event-exports}))
    (is (= (get groups :workflows) {:classification :experimental :symbols workflow-exports}))
    (is (= (get groups :workflow-internals)
           {:classification :internal :symbols #{"WORKFLOW_AGENT_OPTION_KEYS" "getWorkflowDefinition"}}))
    (is (= (get groups :rpc) {:classification :generated-only :symbols rpc-added}))
    (is (= (:changed-declarations r)
           {"nodejs/src/extension.ts" {:experimental #{"JoinSessionConfig"}}
            event-path {:stable-public #{"SessionEvent" "SystemMessageData" "ToolExecutionStartData"}}
            rpc-path {:generated-only rpc-changed}}))
    (is (= (into #{} (mapcat :exports) deltas) event-exports))
    (is (= (into #{} (mapcat :changed-declarations) deltas)
           (get-in r [:changed-declarations event-path :stable-public])))
    (doseq [delta deltas]
      (is (= (:classification delta) :stable-public))
      (is (seq (:wire delta)))
      (is (seq (:idiom delta)))
      (is (seq (:specs delta)))
      (is (seq (:proof-states delta)))
      (is (recorded-test? test-source (:test delta))))
    (is (recorded-test? test-source (:protocol-test common)))
    (is (= (:builders common) :not-applicable-event-payloads))
    (is (= (:fdef-impact common) :existing-subscription-and-history-contracts))
    (is (= (:example-impact common) :no-new-call-pattern))
    (is (= (:api-snapshot-impact common)
           {:added-spec-keys added-spec-keys :function-changes #{} :fdef-changes #{}}))
    (let [contract-specs (into #{} (mapcat :specs) deltas)
          snapshot-path "resources/github/copilot_sdk/api_surface.edn"
          baseline (edn/read-string (local-source clojure-base snapshot-path))
          snapshot (edn/read-string (local-source implementation snapshot-path))
          spec-path [:namespaces 'github.copilot-sdk.specs :spec-keys]
          public-specs (set (get-in snapshot spec-path))]
      (is (set/subset? added-spec-keys contract-specs))
      (is (set/subset? contract-specs public-specs))
      (is (= (set/difference public-specs (set (get-in baseline spec-path))) added-spec-keys))
      (is (= (update-in snapshot spec-path #(into [] (remove added-spec-keys) %)) baseline)
          "Only the seven curated spec keys change in the sealed API snapshot"))
    (doseq [path (:paths common)]
      (is (contains? (:sealed-local-artifacts r) path) path))
    (let [workflow (:workflow-exclusion r)]
      (is (= (select-keys workflow [:classification :decision :authority])
             {:classification :experimental :decision :exclude :authority :stable-only-policy}))
      (is (= (:existing-factories workflow) :unchanged-supported-experimental)))
    (is (= (into #{} (mapcat :added) (:rpc-review r)) rpc-added))
    (is (= (into #{} (mapcat :changed) (:rpc-review r)) rpc-changed))
    (doseq [row (:rpc-review r)]
      (is (= (:classification row) :generated-only))
      (is (not (str/blank? (:reason row)))))))

(deftest complete-target-surface-matches-exact-sources
  (let [r (report)
        surface (:target-public-surface r)
        modules (:modules surface)]
    (is (= (set (keys (:source-blobs surface))) authority-paths))
    (is (= (set (keys modules)) (disj authority-paths "nodejs/package.json")))
    (is (= (set (keys (:trees surface))) #{"nodejs/src" "nodejs/src/generated" "nodejs/test"}))
    (is (= (get-in surface [:package-root :fingerprint 0]) 798))
    (is (= (get-in surface [:package-root :added-export-groups]) [:events :workflows]))
    (is (= (:removed-exports surface) #{}))
    (when-let [repo (ss/upstream-repo-or-skip "cb6fc66 source/public-surface certification")]
      (let [read-source (memoize #(ss/git-output repo "show" (str %1 ":" %2)))
            before #(if (get-in surface [:source-blobs % 0]) (read-source base %) "")
            after #(read-source target %)]
        (doseq [[path pair] (merge (:source-blobs surface) (:trees surface)
                                   (into {} (map (fn [[p v]] [p (:blobs v)])) (:test-inventory r)))
                [pin blob] (map vector [base target] pair)]
          (testing (str pin ":" path)
            (if blob
              (is (= (ss/git-output repo "rev-parse" (str pin ":" path)) blob))
              (is (= (ss/git-lines repo "ls-tree" "-r" "--name-only" pin "--" path) [])))))
        (doseq [[path {:keys [fingerprint added-export-groups]}] modules
                :let [old (ss/exported-symbols (before path))
                      new (ss/exported-symbols (after path))]]
          (testing path
            (is (= [(count new) (ss/sha256-lines (sort new))] fingerprint))
            (is (= (set/difference new old) (group-symbols r added-export-groups)))
            (is (= (set/difference old new) #{}))
            (when (get-in surface [:source-blobs path 0])
              (is (= (ss/changed-exported-declarations repo base target path)
                     (declaration-symbols (get-in r [:changed-declarations path])))))))
        (let [root (:package-root surface)
              index-path "nodejs/src/index.ts"
              old (set/union (ss/exported-symbols (before index-path))
                             (ss/exported-symbols (before event-path)))
              new (set/union (ss/exported-symbols (after index-path))
                             (ss/exported-symbols (after event-path)))]
          (is (= (ss/star-export-modules (after index-path))
                 (:star-exports root) #{"./generated/session-events.js"}))
          (is (= (fingerprint new) (:fingerprint root)))
          (is (= (set/difference new old) (group-symbols r (:added-export-groups root))))
          (is (= (set/difference old new) #{})))
        (doseq [[_ {:keys [path class-name signatures added-signatures fields]}] (:classes surface)
                :let [old (ss/public-class-method-signatures (before path) class-name)
                      new (ss/public-class-method-signatures (after path) class-name)]]
          (testing class-name
            (is (= (fingerprint new) signatures))
            (is (= (set/difference (set new) (set old))
                   (declaration-symbols added-signatures)))
            (is (= (set/difference (set old) (set new)) #{}))
            (is (= [(field-signatures (before path) class-name)
                    (field-signatures (after path) class-name)]
                   fields))))
        (doseq [[interface expected] (:unchanged-interface-fields surface)]
          (let [old (ss/interface-fields (before "nodejs/src/types.ts") interface)
                new (ss/interface-fields (after "nodejs/src/types.ts") interface)]
            (is (= old new))
            (is (= (fingerprint new) expected))))
        (doseq [path (:unchanged-authority-paths surface)]
          (is (= (before path) (after path)) path))
        (doseq [[path markers] (:source-evidence r), marker markers]
          (is (str/includes? (after path) marker) (str path ": " marker)))
        (let [package (json/read-str (after "nodejs/package.json"))
              release (get-in r [:upstream :release-tag])]
          (is (= (get package "copilotCliVersion") "1.0.89-0"))
          (is (= (get package "version") "0.0.0-dev"))
          (is (= (ss/git-output repo "rev-parse" (str "refs/tags/" (:name release) "^{commit}"))
                 (:commit release))))))))

(deftest internal-overload-and-readonly-workflow-are-not-stable-method-deltas
  (let [classes (get-in (report) [:target-public-surface :classes])]
    (is (= (get-in classes [:client :added-signatures])
           {:internal #{internal-extension-signature}}))
    (is (= (get-in classes [:session :added-signatures]) {}))
    (is (= (get-in classes [:client :fields]) [#{} #{}]))
    (is (= (get-in classes [:session :fields])
           [#{"clientSessionApis: ClientSessionApiHandlers"
              "readonly factory: SessionFactoryApi" "public readonly sessionId: string"}
            #{"clientSessionApis: ClientSessionApiHandlers"
              "readonly factory: SessionFactoryApi" "public readonly sessionId: string"
              "readonly workflow: SessionWorkflowApi"}]))))

(deftest agent-metadata-is-reachable-only-through-experimental-rpc-results
  (let [review (some #(when (= (:id %) :rpc/agent-metadata) %) (:rpc-review (report)))
        result-types #{"AgentInfo" "AgentGetCurrentResult" "AgentList"
                       "AgentReloadResult" "AgentSelectResult" "ServerAgentList"}
        result-paths {"agents.discover" "ServerAgentList"
                      "session.agent.list" "AgentList"
                      "session.agent.getCurrent" "AgentGetCurrentResult"
                      "session.agent.select" "AgentSelectResult"
                      "session.agent.reload" "AgentReloadResult"}]
    (is (= (:transitive-result-types review) result-types))
    (is (= (:result-paths review) result-paths))
    (is (= (:stable-handwritten-exposure review) :none))
    (is (= (:experimental-rpc-exposure review) :reachable))
    (when-let [repo (ss/upstream-repo-or-skip "cb6fc66 AgentInfo reachability")]
      (let [read-source #(ss/git-output repo "show" (str target ":" %))
            rpc (read-source rpc-path)]
        (is (= (ss/added-interface-fields repo base target rpc-path "AgentInfo")
               #{"reasoningEffort"}))
        (doseq [result-type result-types]
          (is (str/includes? rpc (str "/** @experimental */\nexport interface " result-type " {"))))
        (doseq [rpc-namespace ["agents" "agent"]]
          (is (str/includes? rpc (str "/** @experimental */\n        " rpc-namespace ": {"))))
        (doseq [[wire-path result-type] result-paths]
          (is (re-find
               (re-pattern
                (str ": Promise<" result-type "> =>\\s*connection\\.sendRequest\\(\""
                     (java.util.regex.Pattern/quote wire-path) "\""))
               rpc)))
        (doseq [path (disj authority-paths "nodejs/package.json" rpc-path)
                :let [source (read-source path)]]
          (testing path
            (is (= (set/intersection result-types
                                     (set (re-seq #"[A-Za-z_$][A-Za-z0-9_$]*" source)))
                   #{}))
            (is (= (ss/star-export-modules source)
                   (if (= path "nodejs/src/index.ts")
                     #{"./generated/session-events.js"}
                     #{})))))
        (is (contains? (ss/interface-fields (read-source "nodejs/src/types.ts") "CustomAgentConfig")
                       "reasoningEffort"))
        (is (= (ss/interface-fields (read-source "nodejs/src/types.ts") "DefaultAgentConfig")
               #{"excludedTools"}))))))
