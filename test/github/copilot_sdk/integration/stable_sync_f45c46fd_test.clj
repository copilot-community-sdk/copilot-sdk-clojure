(ns github.copilot-sdk.integration.stable-sync-f45c46fd-test
  "Executable exact-pin certification for the upstream delta through f45c46fd."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.generated.event-metadata :as event-metadata]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.stable-sync-bba92dd-test
             :as baseline-cert]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.specs :as specs])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def ^:private report-resource
  "resources/stable_upstream_delta_f45c46fd.edn")

(def ^:private historical-resource
  "resources/stable_upstream_delta_bba92dd.edn")

(def ^:private expected-clojure-base
  "d8c211a3f6c089477ce9146aef14def4330f4e7a")

(def ^:private expected-upstream-base
  "bba92dda4c4c5a34340817112968bd78485df006")

(def ^:private expected-upstream-target
  "f45c46fd1812f8bed5b4cbc250f47177c83068f0")

(def ^:private expected-commits
  ["51cf90dfb03c062d9a518ddcb6d50c7d4724b3d6"
   "0cb0050ef4a6206808c7229ee11715f01bc256b0"
   "39c777fe7bf893ba5a4f8b710feeb2172d1d40f9"
   "a6f2e56acb5e04af56241146668fe71291304266"
   "f45c46fd1812f8bed5b4cbc250f47177c83068f0"])

(def ^:private expected-stable-delta-ids
  #{:events/tool-execution-mcp-transport
    :runtime/schema-1.0.84-5})

(def ^:private allowed-classifications
  #{:experimental :generated-only :internal :language-specific :stable-public})

(def ^:private upstream-validation-enabled?
  (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION")))

(def ^:private exported-symbols
  (var-get #'baseline-cert/exported-symbols))

(def ^:private star-export-modules
  (var-get #'baseline-cert/star-export-modules))

(def ^:private interface-fields
  (var-get #'baseline-cert/interface-fields))

(def ^:private changed-exported-declarations
  (var-get #'baseline-cert/changed-exported-declarations))

(def ^:private public-class-methods
  (var-get #'baseline-cert/public-class-methods))

(def ^:private changed-source-lines
  (var-get #'baseline-cert/changed-source-lines))

(def ^:private sha256-file
  (var-get #'baseline-cert/sha256-file))

(def ^:private sha256-resource
  (var-get #'baseline-cert/sha256-resource))

(defn- read-resource
  [resource]
  (some-> resource io/resource slurp edn/read-string))

(defn- report
  []
  (read-resource report-resource))

(defn- resolve-upstream
  []
  (let [{:keys [exit out err]}
        (sh/sh "bash"
               ".github/skills/update-upstream/scripts/resolve-upstream.sh")]
    (when-not (zero? exit)
      (throw (ex-info "Could not resolve the upstream checkout"
                      {:exit exit :stderr err})))
    (str/trim out)))

(def ^:private upstream-repo
  (delay
    (when upstream-validation-enabled?
      (resolve-upstream))))

(defn- shell-output
  [& args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (when-not (zero? exit)
      (throw (ex-info "Command failed"
                      {:args args :exit exit :stderr err})))
    (str/trim out)))

(defn- git-output
  [upstream & args]
  (apply shell-output "git" "-C" upstream args))

(defn- git-lines
  [upstream & args]
  (->> (str/split-lines (apply git-output upstream args))
       (remove str/blank?)
       vec))

(defn- sha256-items
  [items]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x"
            (java.math.BigInteger.
             1
             (.digest
              digest
              (.getBytes (str/join "\n" items)
                         StandardCharsets/UTF_8))))))

(defn- sha256-symbols
  [symbols]
  ((var-get #'baseline-cert/sha256-lines) symbols))

(defn- export-list-entries
  [source]
  (for [[_ clause module]
        (re-seq
         #"(?s)export(?:\s+type)?\s*\{(.*?)\}\s*from\s*\"([^\"]+)\";"
         source)
        entry (str/split
               (str/replace clause #"(?s)/\*.*?\*/|//[^\n]*" "")
               #",")
        :let [entry (-> entry str/trim
                        (str/replace #"^type\s+" ""))
              parts (str/split entry #"\s+as\s+")
              symbol (last parts)]
        :when (not (str/blank? symbol))]
    [symbol module]))

(defn- exported-symbol-modules
  [source]
  (into {} (export-list-entries source)))

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
    (for [{:keys [path symbol classification]}
          (:reexport-changes inventory)
          :when (contains? classifications classification)]
      [:reexport-change path symbol])
    (for [[interface-name by-class] (:event-interface-fields inventory)
          classification classifications
          field (get by-class classification)]
      [:event-interface-field interface-name field])
    (for [[path by-class] (:changed-declarations inventory)
          classification classifications
          declaration (get by-class classification)]
      [:changed-declaration path declaration]))))

(defn- referenced-evidence
  [report]
  (set
   (concat
    (mapcat :evidence (:stable-deltas report))
    (mapcat :evidence (:intentional-exclusions report)))))

(deftest report-pins-history-and-local-artifacts
  (let [report (report)
        historical (read-resource historical-resource)]
    (is (some? report) "The f45c46fd parity oracle must be committed")
    (is (some? historical) "The prior bba92dd oracle must remain available")
    (when (and report historical)
      (is (= expected-clojure-base
             (get-in report [:certification :clojure-base-commit])))
      (is (= expected-upstream-base
             (get-in report [:upstream :base-commit])
             (get-in historical [:upstream :target-commit])))
      (is (= expected-upstream-target
             (get-in report [:upstream :target-commit])))
      (is (= "0.0.0-dev"
             (get-in report [:upstream :target-package-version])))
      (is (= "1.0.84-5"
             (get-in report [:upstream :runtime-version])))
      (is (= historical-resource
             (get-in report
                     [:certification :historical-oracle :resource])))
      (is (= (get-in report [:certification :historical-oracle :sha256])
             (sha256-resource historical-resource)))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str expected-clojure-base "^{commit}"))))
          "the exact Clojure base must resolve")
      (is (zero? (:exit
                  (sh/sh "git" "merge-base" "--is-ancestor"
                         expected-clojure-base "HEAD")))
          "the certification must remain descended from its Clojure base")
      (is (= "1.0.84-5" (str/trim (slurp ".copilot-schema-version"))))
      (doseq [[path expected-hash] (:local-artifacts report)]
        (testing path
          (is (= expected-hash (sha256-file path))))))))

(deftest exact-upstream-range-is-fully-classified
  (let [report (report)
        {:keys [upstream commit-classifications changed-paths]} report
        entries (:entries changed-paths)
        entry-paths (mapv :path entries)]
    (is (= expected-commits (mapv :commit commit-classifications)))
    (is (= :newline-joined-without-trailing-newline
           (:hash-format upstream)
           (:hash-format changed-paths)))
    (is (= 5 (:commit-count upstream) (count commit-classifications)))
    (is (= expected-stable-delta-ids (:stable-delta-ids report)))
    (is (= expected-stable-delta-ids
           (set (map :id (:stable-deltas report)))))
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
    (is (= 92 (:count changed-paths) (count entries)))
    (is (= entry-paths (vec (sort entry-paths))))
    (is (= (count entry-paths) (count (set entry-paths))))
    (is (every? #(contains? allowed-classifications (:classification %))
                entries))
    (is (= (:classification-counts changed-paths)
           (frequencies (map :classification entries))))
    (when-let [upstream-repo @upstream-repo]
      (let [base (:base-commit upstream)
            target (:target-commit upstream)
            actual-commits
            (git-lines upstream-repo "rev-list" "--reverse"
                       (str base ".." target))
            actual-paths
            (sort
             (git-lines upstream-repo "diff" "--name-only"
                        (str base ".." target)))]
        (is (= expected-commits actual-commits))
        (is (= (:commits-sha256 upstream)
               (sha256-items actual-commits)))
        (is (= entry-paths actual-paths))
        (is (= (:sha256 changed-paths)
               (sha256-items actual-paths)))
        (doseq [pin [base target]]
          (is (zero? (:exit
                      (sh/sh "git" "-C" upstream-repo
                             "cat-file" "-e" (str pin "^{commit}"))))
              (str "upstream commit must resolve: " pin)))
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
        inventory (:symbol-inventory report)
        stable-items (inventory-items inventory #{:stable-public})
        nonstable-items
        (inventory-items inventory
                         #{:experimental :generated-only :internal})
        traced-stable-items
        (mapcat :inventory-items (:stable-deltas report))
        traced-exclusion-items
        (mapcat :inventory-items (:intentional-exclusions report))]
    (is (= stable-items (set traced-stable-items)))
    (is (= (count traced-stable-items) (count stable-items))
        "each stable inventory item must have exactly one owning delta")
    (is (= nonstable-items (set traced-exclusion-items)))
    (is (= (count traced-exclusion-items) (count nonstable-items))
        "each non-stable inventory item must have exactly one exclusion")
    (is (= (set (keys (:source-evidence report)))
           (referenced-evidence report)))
    (is (every? #(and (= :stable-public (:classification %))
                      (contains? #{:ported :already-supported :regenerated}
                                 (:status %))
                      (seq (:evidence %))
                      (vector? (:inventory-items %))
                      (seq (:clojure-paths %)))
                (:stable-deltas report)))
    (is (every? #(and (contains? allowed-classifications
                                 (:classification %))
                      (not= :stable-public (:classification %))
                      (= :exclude (:decision %))
                      (= :approved (:status %))
                      (contains? (:decision-authorities report)
                                 (:authority %))
                      (seq (:evidence %))
                      (string? (:reason %))
                      (not (str/blank? (:reason %))))
                (:intentional-exclusions report)))
    (doseq [{:keys [clojure-paths]} (:stable-deltas report)
            path clojure-paths]
      (is (.isFile (io/file path)) (str "missing Clojure evidence: " path)))
    (doseq [{delta-id :id :keys [clojure-evidence]} (:stable-deltas report)
            {:keys [path contains]} clojure-evidence
            :let [source (slurp path)]
            expected contains]
      (is (str/includes? source expected)
          (str delta-id " is missing documented evidence "
               (pr-str expected) " in " path)))
    (when-let [upstream-repo @upstream-repo]
      (let [base (get-in report [:upstream :base-commit])
            target (get-in report [:upstream :target-commit])
            surface (:target-public-surface report)
            read-source
            (memoize
             (fn [pin path]
               (git-output upstream-repo "show" (str pin ":" path))))
            index-path (get-in surface [:package-root :path])
            event-path
            (get-in surface [:package-root :session-events :path])
            index-source (read-source target index-path)
            explicit-symbols (exported-symbols index-source)
            event-symbols (exported-symbols
                           (read-source target event-path))
            package-symbols (set/union explicit-symbols event-symbols)
            baseline-index-symbols
            (exported-symbols (read-source base index-path))
            baseline-event-symbols
            (exported-symbols (read-source base event-path))
            baseline-package-symbols
            (set/union baseline-index-symbols baseline-event-symbols)
            package-delta (get-in surface [:package-root :delta])
            package-provenance
            (get-in surface [:package-root :provenance])
            historical (read-resource historical-resource)
            classified-package-additions
            (apply set/union
                   #{}
                   (vals (dissoc package-delta :removed)))]
        (doseq [[path hashes] (:source-blobs surface)
                [pin-key commit] [[:base base] [:target target]]]
          (testing (str path " " (name pin-key) " blob")
            (is (= (get hashes pin-key)
                   (git-output upstream-repo
                               "rev-parse" (str commit ":" path))))))
        (doseq [[path hashes] (:trees surface)
                [pin-key commit] [[:base base] [:target target]]]
          (testing (str path " " (name pin-key) " tree")
            (is (= (get hashes pin-key)
                   (git-output upstream-repo
                               "rev-parse" (str commit ":" path))))))
        (is (= (get-in report [:upstream :target-package-version])
               (get (json/read-str
                     (read-source target "nodejs/package.json"))
                    "version")))
        (is (= (get-in report [:upstream :runtime-version])
               (get (json/read-str
                     (read-source target "nodejs/package.json"))
                    "copilotCliVersion")))
        (is (= historical-resource
               (:baseline-resource package-provenance)))
        (is (= (get-in historical
                       [:target-public-surface :package-root :symbol-count])
               (:baseline-symbol-count package-provenance)))
        (is (= (get-in historical
                       [:target-public-surface :package-root
                        :classification-counts])
               (:baseline-classification-counts package-provenance)))
        (is (= #{"./generated/session-events.js"}
               (star-export-modules index-source)))
        (is (= (get-in surface [:package-root :explicit-symbol-count])
               (count explicit-symbols)))
        (is (= (get-in surface
                       [:package-root :explicit-symbols-sha256])
               (sha256-symbols (sort explicit-symbols))))
        (is (= (get-in surface
                       [:package-root :session-events :symbol-count])
               (count event-symbols)))
        (is (= (get-in surface
                       [:package-root :session-events :symbols-sha256])
               (sha256-symbols (sort event-symbols))))
        (is (= (get-in surface [:package-root :symbol-count])
               (count package-symbols)))
        (is (= (get-in surface [:package-root :symbols-sha256])
               (sha256-symbols (sort package-symbols))))
        (is (= classified-package-additions
               (set/difference package-symbols baseline-package-symbols)))
        (is (= (:removed package-delta)
               (set/difference baseline-package-symbols package-symbols)))
        (is (= (get-in surface
                       [:package-root :classification-counts])
               (merge-with
                +
                (get-in surface
                        [:package-root :provenance
                         :baseline-classification-counts])
                (update-vals
                 (dissoc package-delta :removed)
                 count))))
        (doseq [surface-key [:types :extension :tool-set]
                :let [{:keys [path symbol-count symbols-sha256]}
                      (get surface surface-key)
                      symbols (exported-symbols
                               (read-source target path))]]
          (testing (name surface-key)
            (is (= symbol-count (count symbols)))
            (is (= symbols-sha256
                   (sha256-symbols (sort symbols))))))
        (doseq [[_ {:keys [path class-name method-count methods-sha256]}]
                (:classes surface)
                :let [methods
                      (public-class-methods
                       (read-source target path) class-name)]]
          (testing class-name
            (is (= method-count (count methods)))
            (is (= methods-sha256
                   (sha256-symbols (sort methods))))))
        (doseq [[path classifications]
                (:added-exported-symbols inventory)]
          (let [expected (apply set/union #{} (vals classifications))
                actual
                (set/difference
                 (exported-symbols (read-source target path))
                 (exported-symbols (read-source base path)))]
            (is (= expected actual)
                (str "added exported symbols drifted for " path))))
        (doseq [[path classifications]
                (:removed-exported-symbols inventory)]
          (let [expected (apply set/union #{} (vals classifications))
                actual
                (set/difference
                 (exported-symbols (read-source base path))
                 (exported-symbols (read-source target path)))]
            (is (= expected actual)
                (str "removed exported symbols drifted for " path))))
        (doseq [{:keys [path symbol base-module target-module]}
                (:reexport-changes inventory)]
          (testing (str path " " symbol)
            (is (= base-module
                   (get (exported-symbol-modules
                         (read-source base path))
                        symbol)))
            (is (= target-module
                   (get (exported-symbol-modules
                         (read-source target path))
                        symbol)))))
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
        (doseq [[path classifications]
                (:changed-declarations inventory)]
          (is (= (apply set/union #{} (vals classifications))
                 (changed-exported-declarations
                  upstream-repo base target path))
              (str "changed declarations drifted for " path)))
        (let [changed-lines-by-path
              (into {}
                    (map
                     (fn [path]
                       [path
                        (changed-source-lines
                         upstream-repo base target path)]))
                    (set (map :path
                              (vals (:source-evidence report)))))]
          (doseq [[evidence-id {:keys [path symbol]}]
                  (:source-evidence report)]
            (testing (name evidence-id)
              (is (seq (get changed-lines-by-path path)))
              (when symbol
                (let [source (read-source target path)]
                  (is (str/includes? source symbol))
                  (is (some #(str/includes? % symbol)
                            (get changed-lines-by-path path))
                      "the evidence symbol must occur on a changed line"))))))))))

(deftest schema-and-exclusion-boundaries-are-executable
  (let [event-schema
        (json/read-str (slurp "schemas/session-events.schema.json"))
        event-definitions (get event-schema "definitions")
        api-schema (json/read-str (slurp "schemas/api.schema.json"))
        api-definitions (get api-schema "definitions")
        tool-start
        {:tool-call-id "call-1"
         :tool-name "mcp-tool"
         :mcp-transport "stdio"}
        message-authorization-data
        {:action-class "shell"
         :polarity "grant"
         :record-id "record-1"
         :span-end 9
         :span-start 1
         :turn-index 2
         :world {:snake_key {"mixedCase" [1 true nil]}}}
        message-authorization-event
        {:id "event-1"
         :parentId nil
         :timestamp "2026-10-01T00:00:00Z"
         :type "permission.messageAuthorization"
         :data message-authorization-data}
        live
        (#'protocol/normalize-incoming
         {:method "session.event"
          :params {:event message-authorization-event}})
        historical
        (#'protocol/normalize-incoming
         {:id "response-1"
          :result {:events [message-authorization-event]}})]
    (is (= ["stdio" "http" "sse" "memory"]
           (get-in event-definitions ["McpServerTransport" "enum"])))
    (is (= "#/definitions/McpServerTransport"
           (get-in event-definitions
                   ["ToolExecutionStartData"
                    "properties"
                    "mcpTransport"
                    "$ref"])))
    (doseq [definition
            ["PermissionCarriedForwardEvent"
             "PermissionMessageAuthorizationEvent"
             "PermissionMessageAuthorizationReadEvent"
             "PermissionMessageAuthorizationDegradedEvent"]]
      (is (= "experimental"
             (get-in event-definitions [definition "stability"]))
          definition))
    (doseq [[definition property]
            [["PermissionCompletedData" "decisionSource"]
             ["PermissionPromptRequestRead" "resolvedPath"]
             ["PermissionPromptRequestWrite" "resolvedPath"]
             ["PermissionRequestRead" "resolvedPath"]
             ["PermissionRequestShell" "resolvedPaths"]
             ["PermissionRequestShell" "resolvedWorkingDirectory"]
             ["PermissionRequestWrite" "resolvedPath"]]]
      (is (= "experimental"
             (get-in event-definitions
                     [definition "properties" property "stability"]))
          (str definition "." property)))
    (doseq [definition
            ["CatalogTrustEligibility"
             "CatalogTrustProvenance"
             "CatalogTrustSnapshot"
             "CatalogTrustSnapshotCurrent"
             "CatalogTrustTier"]]
      (is (contains? api-definitions definition) definition))
    (is (s/valid? ::generated-events/tool.execution_start-data tool-start))
    (is (s/valid? ::specs/tool.execution_start-data tool-start))
    (is (s/valid? ::specs/tool.execution_start-data
                  (dissoc tool-start :mcp-transport)))
    (is (s/valid? ::generated-events/decision-source
                  "authorization_carry_forward"))
    (is (s/valid? ::generated-events/permission.messageAuthorization-data
                  message-authorization-data))
    (is (contains?
         (set (get event-metadata/opaque-json-paths
                   "permission.messageAuthorization"))
         {:wire [:data :world] :idiom [:data :world]}))
    (is (= (:world message-authorization-data)
           (get-in live [:params :event :data :world])
           (get-in historical [:result :events 0 :data :world])))
    (doseq [invalid [nil :stdio "ftp"]]
      (is (not (s/valid? ::generated-events/tool.execution_start-data
                         (assoc tool-start :mcp-transport invalid))))
      (is (not (s/valid? ::specs/tool.execution_start-data
                         (assoc tool-start :mcp-transport invalid)))))
    (is (contains? generated-events/event-types
                   "permission.carriedForward"))
    (is (contains? generated-events/event-types
                   "permission.messageAuthorization"))
    (doseq [event-type
            ["permission.carriedForward"
             "permission.messageAuthorization"
             "permission.messageAuthorizationRead"
             "permission.messageAuthorizationDegraded"]]
      (is (not (contains? sdk/event-types
                          (keyword "copilot" event-type)))
          event-type))))
