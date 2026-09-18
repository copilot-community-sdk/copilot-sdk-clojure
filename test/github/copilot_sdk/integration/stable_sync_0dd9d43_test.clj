(ns github.copilot-sdk.integration.stable-sync-0dd9d43-test
  "Executable exact-pin certification for the upstream delta through 0dd9d43."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.coerce :as coerce]
            [github.copilot-sdk.generated.event-specs]
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
                     sha256-file
                     sha256-items
                     sha256-lines
                     sha256-resource
                     star-export-modules
                     upstream-repo]]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]))

(def ^:private report-resource
  "resources/stable_upstream_delta_0dd9d43.edn")

(def ^:private historical-resource
  "resources/stable_upstream_delta_e9df3938.edn")

(def ^:private expected-clojure-base
  "fb02b1a622da2e98859475abb91f7d5c721f3fd7")

(def ^:private expected-certification-commit
  "33e3f25a2c4d96c6a56f10c35fb6390c7bc92cf9")

(def ^:private expected-upstream-base
  "e9df3938b0f2bb028b203f4095d48b75f155c008")

(def ^:private expected-upstream-target
  "0dd9d4324339b84835c915b0700fdc5c25cec5af")

(def ^:private expected-commits
  ["f60f9d4053f39ab60aa222843db8a32772331478"
   "b47310c92d65671a93223a76b70d1949a94d9b4a"
   "7f86a900789ac71633e74ae3063c8df38642bcd8"
   "062ee6055ae10120fb310339557ec03a4f876353"
   "583f8f1ae0a66d786b90a313dafe7b21c6bdd93c"
   "db6bd6ff7e638360497d873023c7ea8de2c5c549"
   "e60d9037353249ef16b349eb4012e8c1d113fda5"
   "ff3ef7524dd14b26f274c8a814449d0d928f3a8d"
   "620b34776fd9c9fd6868d9cf1d668c341d33ddc1"
   "cd9fb1686c7ebfd79fc0bb089b32ce15b749bc41"
   "f541d4de344b1b1bb900b920f00f0abda25d2c0d"
   "7dc8827f0a1a326d54ce4740cd4a82a7e4d15477"
   "5ea60a61940901751c64aa0ed560ddc94d5b837a"
   "d4580777d46e1c1baf082a11c924b0138c2d00ab"
   "0dd9d4324339b84835c915b0700fdc5c25cec5af"])

(def ^:private allowed-classifications
  #{:experimental :generated-only :internal :language-specific :stable-public})

(def ^:private expected-stable-delta-ids
  #{:byok/azure-project-url
    :release/version-1.0.14
    :runtime/schema-1.0.86-0
    :session/auto-tier-fast-lifecycle})

(def ^:private inventory-sections
  [[:added-exported-symbols :exported-symbol]
   [:removed-exported-symbols :removed-exported-symbol]
   [:event-interface-fields :event-interface-field]
   [:changed-type-values :changed-type-value]
   [:changed-declarations :changed-declaration]])

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
   (for [[section item-type] inventory-sections
         [owner by-class] (get inventory section)
         classification classifications
         item (get by-class classification)]
     [item-type owner item])))

(defn- inventory-group-ids
  [inventory classifications]
  (set
   (for [[section] inventory-sections
         [owner by-class] (get inventory section)
         classification classifications
         :when (seq (get by-class classification))]
     [section owner classification])))

(defn- upstream-repo-or-skip
  [scope]
  (if-let [upstream @upstream-repo]
    upstream
    (do
      (println
       (format
        "SKIP %s: set COPILOT_UPSTREAM_VALIDATION=true for exact upstream checks"
        scope))
      nil)))

(deftest report-pins-history-release-and-local-artifacts
  (let [report (report)
        release-tag (get-in report [:upstream :release-tag])]
    (is (some? report) "The 0dd9d43 parity oracle must be committed")
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
      (is (= {:name "v1.0.14"
              :commit "e60d9037353249ef16b349eb4012e8c1d113fda5"}
             release-tag))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str expected-clojure-base "^{commit}")))))
      (is (zero? (:exit
                  (sh/sh "git" "merge-base" "--is-ancestor"
                         expected-clojure-base "HEAD"))))
      (is (= "1.0.86-0" (get-in report [:upstream :runtime-version])))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str expected-certification-commit "^{commit}"))))
          "the commit containing the certified local artifacts must resolve")
      (is (seq (:local-artifacts report)))
      (doseq [[path expected-hash] (:local-artifacts report)]
        (testing path
          (is (re-matches #"[0-9a-f]{64}" expected-hash))
          (is (= expected-hash
                 (git-file-sha256 expected-certification-commit path))
              "the sealed certification commit must match the ledger")
          (is (= expected-hash (sha256-file path))
              "the checked-out artifact must match the certified bytes"))))))

(deftest exact-upstream-range-is-fully-classified
  (let [report (report)
        {:keys [upstream commit-classifications changed-paths]} report]
    (is (= expected-commits (mapv :commit commit-classifications)))
    (is (= 15 (:commit-count upstream) (count commit-classifications)))
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
    (when-let [upstream-repo
               (upstream-repo-or-skip "0dd9d43 commit/path classification")]
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
    (is (= expected-stable-delta-ids (:stable-delta-ids report)))
    (is (= expected-stable-delta-ids
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
    (when-let [upstream-repo
               (upstream-repo-or-skip "0dd9d43 public-surface evidence")]
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
               (read-source target "nodejs/package.json"))
              {:keys [name commit]} (get-in report [:upstream :release-tag])]
          (is (= (get-in report [:upstream :target-package-version])
                 (get package-json "version")))
          (is (= (get-in report [:upstream :runtime-version])
                 (get package-json "copilotCliVersion")))
          (is (= commit
                 (git-output upstream-repo
                             "rev-parse"
                             (str "refs/tags/" name "^{commit}")))))
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
        (doseq [[path classifications]
                (:changed-declarations inventory)]
          (is (= (apply set/union #{} (vals classifications))
                 (changed-exported-declarations
                  upstream-repo base target path))
              (str "changed declarations drifted for " path)))
        (doseq [[evidence-id {:keys [path contains local changed]}]
                (:source-evidence report)
                :let [source
                      (if local
                        (slurp path)
                        (read-source target path))
                      changed-lines
                      (when (and (not local) (not= false changed))
                        (changed-source-lines
                         upstream-repo base target path))]]
          (testing (name evidence-id)
            (is (str/includes? source contains))
            (when (and (not local) (not= false changed))
              (is (seq changed-lines))
              (is (some #(str/includes? % contains) changed-lines)
                  (str "evidence marker did not change in " path)))))))))

(deftest fast-auto-tier-and-azure-project-url-contracts
  (doseq [event-type ["session.start" "session.resume"]
          :let [wire-event {:type event-type :data {:auto-tier "fast"}}
                idiom-event (coerce/event-wire->idiom wire-event)]]
    (testing event-type
      (is (= :fast (get-in idiom-event [:data :auto-tier])))
      (is (= wire-event (coerce/event-idiom->wire idiom-event)))
      (is (= :fast
             (get-in (session/coerce+normalize-event wire-event)
                     [:data :auto-tier])))))
  (is (s/valid? ::specs/auto-tier :fast))
  (let [create-params
        ((var-get (var client/build-create-session-params))
         {:capi {:auto-tier :fast}})
        resume-params
        ((var-get (var client/build-resume-session-params))
         "session-1"
         {:capi {:auto-tier :fast}})]
    (doseq [params [create-params resume-params]]
      (is (= :fast (get-in params [:capi :autoTier])))
      (is (str/includes?
           (json/write-str (util/clj->wire params))
           "\"autoTier\":\"fast\""))))
  (doseq [project-url
          ["https://resource.services.ai.azure.com/api/projects/project"
           "https://resource.services.ai.azure.com/api/projects/project/"]
          :let [config
                {:provider {:provider-type :azure
                            :base-url project-url
                            :wire-api :responses}}
                create-params
                ((var-get (var client/build-create-session-params)) config)
                resume-params
                ((var-get (var client/build-resume-session-params))
                 "session-1"
                 config)]]
    (testing project-url
      (doseq [params [create-params resume-params]]
        (is (= project-url (get-in params [:provider :baseUrl])))
        (is (= :azure (get-in params [:provider :type])))
        (is (= :responses (get-in params [:provider :wireApi]))))))
  (let [azure-delta
        (first
         (filter #(= :byok/azure-project-url (:id %))
                 (:stable-deltas (report))))]
    (is (= {:base-url-forms #{:resource-host :project-url}
            :forwarding :unchanged
            :trailing-slash-forms #{:present :absent}}
           (get-in azure-delta [:contract :clojure-sdk])))
    (is (= {:authority :upstream-documentation
            :versionless-responses-path :preserves-project-prefix}
           (get-in azure-delta [:contract :copilot-cli-runtime])))))

(deftest experimental-permission-events-remain-generated-only
  (let [event-schema
        (json/read-str (slurp "schemas/session-events.schema.json"))
        definitions (get event-schema "definitions")]
    (is (= "experimental"
           (get-in definitions ["PermissionAssentDetectedEvent" "stability"])))
    (is (= "experimental"
           (get-in definitions
                   ["PermissionContextualAuthorizationEvent" "stability"])))
    (is (= "experimental"
           (get-in definitions
                   ["PermissionMessageAuthorizationReadData"
                    "properties"
                    "activatesExtraction"
                    "stability"]))))
  (doseq [event-type
          [:copilot/permission.assentDetected
           :copilot/permission.contextualAuthorization]]
    (is (not (contains? sdk/event-types event-type)))
    (is (not (s/valid? ::specs/event-type event-type))))
  (is (nil?
       (s/get-spec
        :github.copilot-sdk.specs/permission.assentDetected-data)))
  (is (nil?
       (s/get-spec
        :github.copilot-sdk.specs/permission.contextualAuthorization-data)))
  (is (some?
       (s/get-spec
        :github.copilot-sdk.generated.event-specs/permission.assentDetected-data)))
  (is (some?
       (s/get-spec
        :github.copilot-sdk.generated.event-specs/permission.contextualAuthorization-data))))
