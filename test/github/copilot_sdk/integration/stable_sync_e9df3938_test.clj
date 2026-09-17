(ns github.copilot-sdk.integration.stable-sync-e9df3938-test
  "Executable exact-pin certification for the upstream delta through e9df3938."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.stable-sync-support
             :refer [changed-exported-declarations
                     changed-source-lines
                     exported-symbols
                     git-file-sha256
                     git-lines
                     git-output
                     interface-fields
                     public-class-methods
                     read-resource
                     sha256-items
                     sha256-lines
                     sha256-resource
                     star-export-modules
                     string-union-values
                     upstream-repo]]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.specs :as specs]))

(def ^:private report-resource
  "resources/stable_upstream_delta_e9df3938.edn")

(def ^:private historical-resource
  "resources/stable_upstream_delta_f45c46fd.edn")

(def ^:private expected-clojure-base
  "27bd8e8353964eb10e425bfaef84683e9c013f2e")

(def ^:private expected-certification-commit
  "627341b10bb0fa0040bcf8df507998a5f7444256")

(def ^:private expected-upstream-base
  "f45c46fd1812f8bed5b4cbc250f47177c83068f0")

(def ^:private expected-upstream-target
  "e9df3938b0f2bb028b203f4095d48b75f155c008")

(def ^:private expected-commits
  ["8aab138e899dde9426aec6a671a824790e780089"
   "ef64ceaf881ccc0e694be3d24101641bf9938f1a"
   "ad0d5e7299454cadec20cd0f015cd408a2b7ab7b"
   "7bd35d769652cb73960353288707c589b7d04524"
   "bab21f2f599e933e5657ee364b6fa75d0794363e"
   "557e496195c5c8dc1a964812f25790636930763e"
   "d494785763c6a5a93e37916cee6572f0d6943c16"
   "81be283a5d9b043fcb0d115bb5f8c7adf7e561eb"
   "dcf488292b1f7852efe7da79de378836e1e144ab"
   "8d1545db2e33a4e1435c55ca96a02a8059c2fa07"
   "e5c53790c6a8aa253aa3887ebb8958646bd60452"
   "22e860719494262832504b1301237c05b0f4fab8"
   "c03400d12d2175efdc557ef9eb04b36d0d055ff3"
   "a675b55531a9dfc647ee015e32d74279568550f3"
   "9553d5224c73df2d02aee5ec01eeb8353acc736a"
   "e9df3938b0f2bb028b203f4095d48b75f155c008"])

(def ^:private allowed-classifications
  #{:experimental :generated-only :internal :language-specific :stable-public})

(defn- report
  []
  (read-resource report-resource))

(defn- classify-path
  [{:keys [exact-classifications language-specific-prefixes]} path]
  (or (get exact-classifications path)
      (when (some #(str/starts-with? path %) language-specific-prefixes)
        :language-specific)))

(defn- inventory-items
  [inventory classifications]
  (set
   (concat
    (for [[path by-class] (:added-exported-symbols inventory)
          classification classifications
          symbol (get by-class classification)]
      [:exported-symbol path symbol])
    (for [[path by-class] (:removed-exported-symbols inventory)
          classification classifications
          symbol (get by-class classification)]
      [:removed-exported-symbol path symbol])
    (for [[interface-name by-class] (:event-interface-fields inventory)
          classification classifications
          field (get by-class classification)]
      [:event-interface-field interface-name field])
    (for [[type-name by-class] (:changed-type-values inventory)
          classification classifications
          value (get by-class classification)]
      [:changed-type-value type-name value])
    (for [[path by-class] (:changed-declarations inventory)
          classification classifications
          declaration (get by-class classification)]
      [:changed-declaration path declaration]))))

(defn- inventory-group-ids
  [inventory classifications]
  (set
   (for [section [:added-exported-symbols
                  :removed-exported-symbols
                  :event-interface-fields
                  :changed-type-values
                  :changed-declarations]
         [owner by-class] (get inventory section)
         classification classifications
         :when (seq (get by-class classification))]
     [section owner classification])))

(deftest exported-symbol-parser-handles-local-and-forwarded-exports
  (is (= #{"Declared" "LocalType" "ForwardedType" "Renamed"}
         (exported-symbols
          (str "export interface Declared {}\n"
               "export type { LocalType };\n"
               "export type {\n"
               "  ForwardedType,\n"
               "  Original as Renamed,\n"
               "} from \"./other.js\";\n"
               "// export { LineCommented };\n"
               "/*\n"
               "export interface BlockCommented {}\n"
               "export { AlsoCommented } from \"./commented.js\";\n"
               "*/\n")))))

(deftest report-pins-history-and-local-artifacts
  (let [report (report)]
    (is (some? report) "The e9df3938 parity oracle must be committed")
    (when report
      (is (= expected-clojure-base
             (get-in report [:certification :clojure-base-commit])))
      (is (= expected-upstream-base
             (get-in report [:upstream :base-commit])))
      (is (= expected-upstream-target
             (get-in report [:upstream :target-commit])))
      (is (= historical-resource
             (get-in report
                     [:certification :historical-oracle :resource])))
      (is (= (get-in report [:certification :historical-oracle :sha256])
             (sha256-resource historical-resource)))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str expected-clojure-base "^{commit}")))))
      (is (zero? (:exit
                  (sh/sh "git" "merge-base" "--is-ancestor"
                         expected-clojure-base "HEAD"))))
      (is (= "1.0.84-8" (get-in report [:upstream :runtime-version])))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str expected-certification-commit "^{commit}"))))
          "the commit containing the certified local artifacts must resolve")
      (is (seq (:local-artifacts report)))
      (doseq [[path expected-hash] (:local-artifacts report)]
        (testing path
          (is (= expected-hash
                 (git-file-sha256 expected-certification-commit path))))))))

(deftest exact-upstream-range-is-fully-classified
  (let [report (report)
        {:keys [upstream commit-classifications changed-paths]} report]
    (is (= expected-commits (mapv :commit commit-classifications)))
    (is (= 16 (:commit-count upstream) (count commit-classifications)))
    (is (= :newline-joined-without-trailing-newline
           (:hash-format upstream)
           (:hash-format changed-paths)))
    (is (every? #(contains? allowed-classifications (:classification %))
                commit-classifications))
    (is (every? #(and (keyword? (:status %))
                      (string? (:source-url %))
                      (string? (:reason %))
                      (not (str/blank? (:reason %)))
                      (pos-int? (:changed-path-count %))
                      (re-matches #"[0-9a-f]{64}"
                                  (:changed-paths-sha256 %)))
                commit-classifications))
    (when-let [upstream-repo @upstream-repo]
      (let [base (:base-commit upstream)
            target (:target-commit upstream)
            actual-commits
            (git-lines upstream-repo "rev-list" "--reverse"
                       (str base ".." target))
            actual-paths
            (sort
             (git-lines upstream-repo "diff" "--name-only"
                        (str base ".." target)))
            classifications
            (map #(classify-path changed-paths %) actual-paths)]
        (is (= expected-commits actual-commits))
        (is (= (:commits-sha256 upstream)
               (sha256-items actual-commits)))
        (is (= (:count changed-paths) (count actual-paths)))
        (is (= (:sha256 changed-paths)
               (sha256-items actual-paths)))
        (is (every? some? classifications)
            (str "unclassified paths: "
                 (pr-str
                  (keep #(when-not (classify-path changed-paths %) %)
                        actual-paths))))
        (is (= (:classification-counts changed-paths)
               (frequencies classifications)))
        (doseq [{:keys [commit subject changed-path-count
                        changed-paths-sha256]}
                commit-classifications
                :let [paths
                      (sort
                       (git-lines upstream-repo "diff-tree"
                                  "--no-commit-id" "--name-only" "-r"
                                  commit))]]
          (testing commit
            (is (= subject
                   (git-output upstream-repo
                               "show" "-s" "--format=%s" commit)))
            (is (= changed-path-count (count paths)))
            (is (= changed-paths-sha256
                   (sha256-items paths)))))))))

(deftest target-public-surface-and-delta-inventory-are-exact
  (let [report (report)
        surface (:target-public-surface report)
        inventory (:symbol-inventory report)
        stable-items (inventory-items inventory #{:stable-public})
        traced-stable-items (mapcat :inventory-items (:stable-deltas report))
        nonstable-groups
        (inventory-group-ids inventory #{:experimental :generated-only})
        traced-nonstable-groups
        (mapcat :inventory-groups (:intentional-exclusions report))
        referenced-evidence
        (set
         (concat
          (mapcat :evidence (:stable-deltas report))
          (mapcat :evidence (:compatibility-deltas report))
          (mapcat :evidence (:intentional-exclusions report))))]
    (is (= (:stable-delta-ids report)
           (set (map :id (:stable-deltas report)))))
    (is (= stable-items (set traced-stable-items)))
    (is (= (count stable-items) (count traced-stable-items)))
    (is (= nonstable-groups (set traced-nonstable-groups)))
    (is (= (count nonstable-groups) (count traced-nonstable-groups)))
    (is (= (set (keys (:source-evidence report))) referenced-evidence))
    (is (every? #(and (= :stable-public (:classification %))
                      (contains? #{:documented :ported :regenerated}
                                 (:status %))
                      (seq (:evidence %))
                      (seq (:clojure-paths %)))
                (:stable-deltas report)))
    (is (every? #(and (= :exclude (:decision %))
                      (= :approved (:status %))
                      (contains? (:decision-authorities report)
                                 (:authority %))
                      (seq (:evidence %))
                      (string? (:reason %))
                      (not (str/blank? (:reason %))))
                (:intentional-exclusions report)))
    (doseq [{:keys [clojure-paths]}
            (concat (:stable-deltas report)
                    (:compatibility-deltas report))
            path clojure-paths]
      (is (.isFile (io/file path)) (str "missing Clojure evidence: " path)))
    (when-let [upstream-repo @upstream-repo]
      (let [base (get-in report [:upstream :base-commit])
            target (get-in report [:upstream :target-commit])
            read-source
            (memoize
             (fn [pin path]
               (git-output upstream-repo "show" (str pin ":" path))))
            index-path (get-in surface [:package-root :path])
            event-path
            (get-in surface [:package-root :session-events :path])
            index-symbols
            (exported-symbols (read-source target index-path))
            event-symbols
            (exported-symbols (read-source target event-path))
            package-symbols (set/union index-symbols event-symbols)
            baseline-package-symbols
            (set/union
             (exported-symbols (read-source base index-path))
             (exported-symbols (read-source base event-path)))]
        (doseq [[surface-key label]
                [[:source-blobs "blob"] [:trees "tree"]]
                [path hashes] (get surface surface-key)
                [pin-key commit] [[:base base] [:target target]]]
          (testing (str path " " (name pin-key) " " label)
            (is (= (get hashes pin-key)
                   (git-output upstream-repo
                               "rev-parse" (str commit ":" path))))))
        (let [package-json
              (json/read-str
               (read-source target "nodejs/package.json"))]
          (is (= (get-in report [:upstream :target-package-version])
                 (get package-json "version")))
          (is (= (get-in report [:upstream :runtime-version])
                 (get package-json "copilotCliVersion"))))
        (is (= #{"./generated/session-events.js"}
               (star-export-modules (read-source target index-path))))
        (is (= (get-in surface [:package-root :explicit-symbol-count])
               (count index-symbols)))
        (is (= (get-in surface
                       [:package-root :explicit-symbols-sha256])
               (sha256-lines (sort index-symbols))))
        (is (= (get-in surface
                       [:package-root :session-events :symbol-count])
               (count event-symbols)))
        (is (= (get-in surface
                       [:package-root :session-events :symbols-sha256])
               (sha256-lines (sort event-symbols))))
        (is (= (get-in surface [:package-root :symbol-count])
               (count package-symbols)))
        (is (= (get-in surface [:package-root :symbols-sha256])
               (sha256-lines (sort package-symbols))))
        (is (= (apply set/union
                      #{}
                      (vals (get-in surface [:package-root :added])))
               (set/difference package-symbols baseline-package-symbols)))
        (is (= (get-in surface [:package-root :removed])
               (set/difference baseline-package-symbols package-symbols)))
        (doseq [surface-key [:types :extension :tool-set :factory]
                :let [{:keys [path symbol-count symbols-sha256]}
                      (get surface surface-key)
                      symbols (exported-symbols
                               (read-source target path))]]
          (testing (name surface-key)
            (is (= symbol-count (count symbols)))
            (is (= symbols-sha256
                   (sha256-lines (sort symbols))))))
        (doseq [[_ {:keys [path class-name method-count methods-sha256]}]
                (:classes surface)
                :let [methods
                      (public-class-methods
                       (read-source target path) class-name)]]
          (testing class-name
            (is (= method-count (count methods)))
            (is (= methods-sha256
                   (sha256-lines (sort methods))))))
        (doseq [[inventory-key from to label]
                [[:added-exported-symbols base target "added"]
                 [:removed-exported-symbols target base "removed"]]
                [path classifications] (get inventory inventory-key)]
          (let [expected (apply set/union #{} (vals classifications))
                actual
                (set/difference
                 (exported-symbols (read-source to path))
                 (exported-symbols (read-source from path)))]
            (is (= expected actual)
                (str label " exported symbols drifted for " path))))
        (doseq [[interface-name classifications]
                (:event-interface-fields inventory)]
          (let [expected (apply set/union #{} (vals classifications))
                base-fields
                (interface-fields (read-source base event-path)
                                  interface-name)
                target-fields
                (interface-fields (read-source target event-path)
                                  interface-name)]
            (is (= expected (set/difference target-fields base-fields))
                (str "event fields drifted for " interface-name))))
        (doseq [[type-name classifications]
                (:changed-type-values inventory)]
          (let [expected (apply set/union #{} (vals classifications))
                base-values
                (string-union-values (read-source base event-path)
                                     type-name)
                target-values
                (string-union-values (read-source target event-path)
                                     type-name)]
            (is (= expected (set/difference target-values base-values))
                (str "added type values drifted for " type-name))
            (is (empty? (set/difference base-values target-values))
                (str "removed type values were not classified for "
                     type-name))))
        (doseq [[path classifications]
                (:changed-declarations inventory)]
          (is (= (apply set/union #{} (vals classifications))
                 (changed-exported-declarations
                  upstream-repo base target path))
              (str "changed declarations drifted for " path)))
        (doseq [[evidence-id {:keys [path contains local]}]
                (:source-evidence report)
                :let [source
                      (if local
                        (slurp path)
                        (read-source target path))
                      changed-lines
                      (when-not local
                        (changed-source-lines
                         upstream-repo base target path))]]
          (testing (name evidence-id)
            (is (str/includes? source contains))
            (when-not local
              (is (seq changed-lines))
              (is (some #(str/includes? % contains) changed-lines)
                  (str "evidence marker did not change in " path)))))))))

(deftest stable-event-contracts-and-exclusions-are-executable
  (let [event-schema
        (json/read-str (slurp "schemas/session-events.schema.json"))
        event-definitions (get event-schema "definitions")
        api-schema (json/read-str (slurp "schemas/api.schema.json"))
        api-definitions (get api-schema "definitions")
        assistant-message
        {:message-id "assistant-1"
         :content "done"
         :originating-message-id "user-1"}
        tool-start
        {:tool-call-id "tool-1"
         :tool-name "server.tool"
         :mcp-config-server-name "configured-server"
         :mcp-config-source "managed"}
        subagent-started
        {:tool-call-id "tool-1"
         :agent-name "reviewer"
         :agent-display-name "Reviewer"
         :agent-description "Reviews changes"
         :model-selection-source "runtime_policy"}
        mcp-servers-loaded
        {:servers [{:name "managed-server"
                    :display-name "Managed Server"
                    :source "managed"
                    :status "connected"}]}
        refresh-completed
        {:request-id "refresh-1" :outcome "error"}
        wire-tool-start
        {:id "event-1"
         :parentId nil
         :timestamp "2026-10-01T00:00:00Z"
         :type "tool.execution_start"
         :data {:toolCallId "tool-1"
                :toolName "server.tool"
                :mcpConfigServerName "configured-server"
                :mcpConfigSource "managed"}}
        normalized-tool-start
        (#'protocol/normalize-incoming
         {:method "session.event"
          :params {:event wire-tool-start}})]
    (is (= ["user" "workspace" "plugin" "builtin" "managed"]
           (get-in event-definitions ["McpServerSource" "enum"])))
    (is (= ["headers" "none" "error" "timeout"]
           (get-in event-definitions
                   ["McpHeadersRefreshCompletedOutcome" "enum"])))
    (is (= "experimental"
           (get-in event-definitions
                   ["ToolExecutionCompleteData"
                    "properties"
                    "shellExecution"
                    "stability"])))
    (doseq [definition
            ["SkillInvokedRefEvent"
             "SkillContextDeliveredEvent"
             "SkillContextDeliveredRefEvent"]]
      (is (= "experimental"
             (get-in event-definitions [definition "stability"]))
          definition))
    (is (= "experimental"
           (get-in api-definitions ["FactoryAbortRequest" "stability"])))
    (is (= ["runId" "executionToken"]
           (get-in api-definitions ["FactoryAbortRequest" "required"])))
    (is (= "experimental"
           (get-in api-definitions ["FactoryPauseRequest" "stability"])))
    (is (nil? (ns-resolve 'github.copilot-sdk 'pause-factory-run!)))
    (is (nil? (ns-resolve 'github.copilot-sdk.factory 'pause!)))
    (doseq [[generated-spec idiom-spec value]
            [[::generated-events/assistant.message-data
              ::specs/assistant.message-data
              assistant-message]
             [::generated-events/tool.execution_start-data
              ::specs/tool.execution_start-data
              tool-start]
             [::generated-events/subagent.started-data
              ::specs/subagent.started-data
              subagent-started]
             [::generated-events/session.mcp_servers_loaded-data
              ::specs/session.mcp_servers_loaded-data
              mcp-servers-loaded]
             [::generated-events/mcp.headers_refresh_completed-data
              ::specs/mcp.headers_refresh_completed-data
              refresh-completed]]]
      (is (s/valid? generated-spec value))
      (is (s/valid? idiom-spec value)))
    (doseq [event-type
            [:copilot/mcp.headers_refresh_required
             :copilot/mcp.headers_refresh_completed]]
      (is (contains? sdk/event-types event-type))
      (is (s/valid? ::specs/event-type event-type)))
    (doseq [outcome ["headers" "none" "error" "timeout"]]
      (is (s/valid? ::specs/mcp.headers_refresh_completed-data
                    (assoc refresh-completed :outcome outcome))))
    (is (= {:mcp-config-server-name "configured-server"
            :mcp-config-source "managed"}
           (select-keys
            (get-in normalized-tool-start [:params :event :data])
            [:mcp-config-server-name :mcp-config-source])))
    (is (not (s/valid? ::specs/assistant.message-data
                       (assoc assistant-message :originating-message-id nil))))
    (is (not (s/valid? ::specs/tool.execution_start-data
                       (assoc tool-start :mcp-config-source "unknown"))))
    (is (not (s/valid? ::specs/subagent.started-data
                       (assoc subagent-started
                              :model-selection-source "unknown"))))
    (is (not (s/valid? ::specs/session.mcp_servers_loaded-data
                       (assoc-in mcp-servers-loaded
                                 [:servers 0 :display-name]
                                 42))))
    (is (not (s/valid? ::specs/session.mcp_servers_loaded-data
                       (assoc-in mcp-servers-loaded
                                 [:servers 0 :source]
                                 "unknown"))))
    (is (not (s/valid? ::specs/mcp.headers_refresh_completed-data
                       (assoc refresh-completed :outcome nil))))))
