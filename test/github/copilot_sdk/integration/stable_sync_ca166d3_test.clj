(ns github.copilot-sdk.integration.stable-sync-ca166d3-test
  "Executable exact-pin certification for the upstream delta through ca166d3."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
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
                     upstream-repo]]
            [github.copilot-sdk.specs :as specs]))

(def ^:private report-resource
  "resources/stable_upstream_delta_ca166d3.edn")

(def ^:private expected-clojure-base
  "85a8037200f6c761f632c60110ee00c0a72d8821")

(def ^:private expected-upstream-base
  "0dd9d4324339b84835c915b0700fdc5c25cec5af")

(def ^:private expected-upstream-target
  "ca166d3eeec17b8efe0294af4b1ef9ca0f4445de")

(def ^:private expected-commits
  ["dc2447f107549d7ca404112e8f1d31054287ed2c"
   "fc44743a1d75a9349828434ef6fbff9b5fe9910c"
   "ad69ee168680728872434817191ef8cc5e449431"
   "791f7fb7dab1ddc843e4796079f256ccd40c4082"
   "782c45dcdd1746acf7310096bafa8dbc488e5286"
   "8045fb74c7d8684fd388b09e324c00b02e9347a5"
   "8b22f15e28e210c9b90f822d92c4a38e53d079aa"
   "0a7113bf0ade366558f922c7665d0ee21fe02df6"
   "9515b5e85c71b21e82381d22d69c2486561fd531"
   "ca166d3eeec17b8efe0294af4b1ef9ca0f4445de"])

(def ^:private allowed-classifications
  #{:experimental :generated-only :internal :language-specific :stable-public})

(defn- report
  []
  (read-resource report-resource))

(defn- classify-path
  [{:keys [exact-classifications prefix-classifications]} path]
  (or (get exact-classifications path)
      (some (fn [{:keys [prefix classification]}]
              (when (str/starts-with? path prefix)
                classification))
            prefix-classifications)))

(defn- classified-items
  [classifications]
  (apply set/union #{} (vals classifications)))

(defn- valid-upstream-prs?
  [prs]
  (and (vector? prs)
       (seq prs)
       (every? pos-int? prs)
       (= (count prs) (count (distinct prs)))))

(defn- inventory-group-ids
  [inventory classifications]
  (set
   (concat
    (for [section
          [:added-exported-symbols
           :removed-exported-symbols
           :changed-declarations]
          [path by-class] (get inventory section)
          classification classifications
          :when (seq (get by-class classification))]
      [section path classification])
    (for [[path by-interface] (:interface-fields inventory)
          [interface-name by-class] by-interface
          classification classifications
          :when (seq (get by-class classification))]
      [:interface-fields path interface-name classification])
    (for [[path by-type] (:changed-type-values inventory)
          [type-name by-class] by-type
          classification classifications
          :when (seq (get by-class classification))]
      [:changed-type-values path type-name classification]))))

(defn- type-alias-values
  [source type-name]
  (let [pattern
        (re-pattern
         (str "(?s)(?:export\\s+)?type\\s+"
              (java.util.regex.Pattern/quote type-name)
              "\\s*=\\s*(.*?);"))
        body (second (re-find pattern source))]
    (set (map second (re-seq #"\"([^\"]+)\"" (or body ""))))))

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
        artifact-commit
        (get-in report [:certification :local-artifact-commit])
        historical-resource
        (get-in report [:certification :historical-oracle :resource])]
    (is (some? report) "The ca166d3 parity oracle must be committed")
    (when report
      (is (= expected-clojure-base
             (get-in report [:certification :clojure-base-commit])))
      (is (= expected-upstream-base
             (get-in report [:upstream :base-commit])))
      (is (= expected-upstream-target
             (get-in report [:upstream :target-commit])))
      (is (= (get-in report [:certification :historical-oracle :sha256])
             (sha256-resource historical-resource)))
      (is (= {:name "v1.0.14"
              :commit "e60d9037353249ef16b349eb4012e8c1d113fda5"}
             (get-in report [:upstream :release-tag])))
      (is (= "1.0.87-0" (get-in report [:upstream :runtime-version])))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str expected-clojure-base "^{commit}")))))
      (is (zero? (:exit
                  (sh/sh "git" "merge-base" "--is-ancestor"
                         expected-clojure-base "HEAD"))))
      (is (re-matches #"[0-9a-f]{40}" artifact-commit))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str artifact-commit "^{commit}"))))
          "the commit containing the certified local artifacts must resolve")
      (is (seq (:local-artifacts report)))
      (doseq [[path expected-hash] (:local-artifacts report)]
        (testing path
          (is (re-matches #"[0-9a-f]{64}" expected-hash))
          (is (= expected-hash
                 (git-file-sha256 artifact-commit path))
              "the sealed implementation commit must match the ledger"))))))

(deftest exact-upstream-range-is-fully-classified
  (let [{:keys [upstream commit-classifications changed-paths]} (report)]
    (is (= expected-commits (mapv :commit commit-classifications)))
    (is (= 10 (:commit-count upstream) (count commit-classifications)))
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
               (upstream-repo-or-skip "ca166d3 commit/path classification")]
      (let [base (:base-commit upstream)
            target (:target-commit upstream)
            commits (git-lines upstream-repo "rev-list"
                               "--reverse" (str base ".." target))
            paths (git-lines upstream-repo "diff" "--name-only"
                             (str base ".." target))
            classifications (map #(classify-path changed-paths %) paths)
            actual-counts (frequencies classifications)]
        (is (= commits expected-commits))
        (is (= (:commits-sha256 upstream)
               (sha256-items commits)))
        (is (= (:count changed-paths) (count paths)))
        (is (= (:sha256 changed-paths) (sha256-items paths)))
        (is (every? some? classifications)
            "Every changed path needs a deliberate classification")
        (is (= (:classification-counts changed-paths) actual-counts))
        (doseq [{:keys [commit changed-path-count changed-paths-sha256]}
                commit-classifications
                :let [commit-paths
                      (git-lines upstream-repo "diff-tree"
                                 "--no-commit-id" "--name-only" "-r"
                                 commit)]]
          (testing commit
            (is (= changed-path-count (count commit-paths)))
            (is (= changed-paths-sha256
                   (sha256-items commit-paths)))
            (let [subject
                  (git-output upstream-repo "show" "-s" "--format=%s" commit)
                  pr-number
                  (second (re-find #"\(#(\d+)\)$" subject))]
              (is (= subject
                     (:subject
                      (first
                       (filter #(= commit (:commit %))
                               commit-classifications)))))
              (is (= (str "https://github.com/github/copilot-sdk/pull/"
                          pr-number)
                     (:source-url
                      (first
                       (filter #(= commit (:commit %))
                               commit-classifications))))))))))))

(deftest stable-deltas-and-exclusions-are-traceable
  (let [report (report)
        inventory (:symbol-inventory report)
        stable-deltas (:stable-deltas report)
        compatibility-deltas (:compatibility-deltas report)
        exclusions (:intentional-exclusions report)
        stable-paths
        (set (for [[path classification]
                   (get-in report [:changed-paths :exact-classifications])
                   :when (= :stable-public classification)]
               path))
        traced-stable-paths (set (mapcat :upstream-paths stable-deltas))
        nonstable-groups
        (inventory-group-ids
         inventory #{:experimental :generated-only :internal})
        stable-groups
        (inventory-group-ids inventory #{:stable-public})
        traced-stable-groups
        (set (mapcat :inventory-groups stable-deltas))
        traced-nonstable-groups
        (set (mapcat :inventory-groups exclusions))
        referenced-evidence
        (set (mapcat :evidence
                     (concat stable-deltas compatibility-deltas exclusions)))
        contract-ids
        (map :id (mapcat :upstream-contracts stable-deltas))]
    (is (= (:stable-delta-ids report) (set (map :id stable-deltas))))
    (is (= (count stable-deltas) (count (distinct (map :id stable-deltas)))))
    (is (= stable-paths traced-stable-paths))
    (is (= stable-groups traced-stable-groups))
    (is (= (count contract-ids) (count (distinct contract-ids))))
    (is (= nonstable-groups traced-nonstable-groups))
    (is (= (set (keys (:source-evidence report))) referenced-evidence))
    (is (every? #(and (= :stable-public (:classification %))
                      (contains? #{:documented :ported :regenerated}
                                 (:status %))
                      (valid-upstream-prs? (:upstream-prs %))
                      (vector? (:upstream-paths %))
                      (vector? (:inventory-groups %))
                      (seq (:upstream-contracts %))
                      (seq (:evidence %))
                      (seq (:clojure-paths %)))
                stable-deltas))
    (is (every? #(valid-upstream-prs? (:upstream-prs %))
                compatibility-deltas))
    (is (every? #(and (= :exclude (:decision %))
                      (= :approved (:status %))
                      (contains? (:decision-authorities report)
                                 (:authority %))
                      (seq (:evidence %))
                      (string? (:reason %))
                      (not (str/blank? (:reason %))))
                exclusions))
    (doseq [delta stable-deltas
            contract (:upstream-contracts delta)]
      (testing (str (:id delta) " " (:id contract))
        (is (contains? (set (:evidence delta)) (:evidence contract)))
        (is (string?
             (get-in report
                     [:source-evidence (:evidence contract) :path])))))
    (doseq [{:keys [clojure-paths]}
            (concat stable-deltas compatibility-deltas)
            path clojure-paths]
      (is (.isFile (io/file path)) (str "missing Clojure evidence: " path)))))

(deftest exact-target-public-surface-and-source-evidence
  (let [report (report)
        surface (:target-public-surface report)
        inventory (:symbol-inventory report)]
    (when-let [upstream-repo
               (upstream-repo-or-skip "ca166d3 public-surface evidence")]
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
        (is (= (classified-items
                (get-in surface [:package-root :added]))
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
        (doseq [[path classifications]
                (:added-exported-symbols inventory)]
          (is (= (classified-items classifications)
                 (set/difference
                  (exported-symbols (read-source target path))
                  (exported-symbols (read-source base path))))
              (str "added exported symbols drifted for " path)))
        (doseq [[path classifications]
                (:removed-exported-symbols inventory)]
          (is (= (classified-items classifications)
                 (set/difference
                  (exported-symbols (read-source base path))
                  (exported-symbols (read-source target path))))
              (str "removed exported symbols drifted for " path)))
        (doseq [[path by-interface] (:interface-fields inventory)
                [interface-name classifications] by-interface]
          (let [base-fields
                (interface-fields (read-source base path) interface-name)
                target-fields
                (interface-fields (read-source target path) interface-name)]
            (is (= (classified-items classifications)
                   (set/difference target-fields base-fields))
                (str "interface fields drifted for "
                     path " " interface-name))))
        (doseq [[path by-type] (:changed-type-values inventory)
                [type-name classifications] by-type]
          (let [base-values
                (type-alias-values (read-source base path) type-name)
                target-values
                (type-alias-values (read-source target path) type-name)]
            (is (= (classified-items classifications)
                   (set/difference target-values base-values))
                (str "type values drifted for " path " " type-name))))
        (doseq [[path classifications]
                (:changed-declarations inventory)]
          (is (= (classified-items classifications)
                 (changed-exported-declarations
                  upstream-repo base target path))
              (str "changed declarations drifted for " path)))
        (let [{:keys [unchanged-authority-paths
                      inventoried-authority-paths]}
              (:stable-surface-continuity report)]
          (doseq [path unchanged-authority-paths]
            (is (= (read-source base path) (read-source target path))
                (str "unchanged authority path drifted: " path)))
          (doseq [path inventoried-authority-paths]
            (is (not= (read-source base path) (read-source target path))
                (str "inventoried authority path did not change: " path))))
        (doseq [[evidence-id {:keys [path contains]}]
                (:source-evidence report)
                :let [source (read-source target path)
                      changed-lines
                      (changed-source-lines
                       upstream-repo base target path)]]
          (testing (name evidence-id)
            (is (str/includes? source contains))
            (is (seq changed-lines))
            (is (some #(str/includes? % contains) changed-lines)
                (str "evidence marker did not change in " path))))))))

(deftest local-stable-contracts-and-exclusions
  (is (= "1.0.87-0" (str/trim (slurp ".copilot-schema-version"))))
  (doseq [event-type
          [:copilot/session.indexed_search
           :copilot/session.permission_recovery]]
    (is (contains? sdk/event-types event-type))
    (is (contains? sdk/session-events event-type))
    (is (s/valid? ::specs/event-type event-type)))
  (is (s/valid?
       ::specs/response-schema
       {"type" "object"
        "properties" {"answer" {"type" "string"}}
        "required" ["answer"]}))
  (is (s/valid?
       ::specs/extension-context-attachment
       {:type :extension-context
        :extension-id "github.example"
        :title "Current selection"
        :captured-at "2026-04-27T12:00:00Z"
        :payload nil}))
  (is (s/valid?
       ::specs/session.indexed_search-data
       {:kind "status" :state "ready"}))
  (is (s/valid?
       ::specs/session.permission_recovery-data
       {:episode-id "episode-1"
        :status "recovering"
        :on-blocked "ask"
        :reason "permission_required"
        :max-attempts 2
        :attempts []}))
  (doseq [event-type
          [:copilot/permission.assentDetected
           :copilot/permission.contextualAuthorization]]
    (is (not (contains? sdk/event-types event-type)))
    (is (not (s/valid? ::specs/event-type event-type)))))
