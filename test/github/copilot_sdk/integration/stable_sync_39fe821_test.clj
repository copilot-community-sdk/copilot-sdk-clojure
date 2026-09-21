(ns github.copilot-sdk.integration.stable-sync-39fe821-test
  "Executable exact-pin certification for the upstream delta through 39fe821."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.integration.stable-sync-support
             :refer [changed-exported-declarations
                     exported-symbols
                     git-file-sha256
                     git-lines
                     git-output
                     public-class-methods
                     read-resource
                     sha256-file
                     sha256-items
                     sha256-lines
                     sha256-resource
                     star-export-modules
                     upstream-repo]]))

(def ^:private report-resource
  "resources/stable_upstream_delta_39fe821.edn")

(def ^:private expected-clojure-base
  "9e1be3bda6cd642a8a731f1016dd0cc024f792ae")

(def ^:private expected-upstream-base
  "ca166d3eeec17b8efe0294af4b1ef9ca0f4445de")

(def ^:private expected-upstream-target
  "39fe821dec17fc20bf92250ab3e19935aaf2e6f5")

(def ^:private expected-commit-classifications
  [["c310b247470e8d04a4cd0b5230d435c9d9243ed3" :language-specific]
   ["39fe821dec17fc20bf92250ab3e19935aaf2e6f5" :experimental]])

(def ^:private expected-commits
  (mapv first expected-commit-classifications))

(def ^:private allowed-classifications
  #{:experimental :internal :language-specific})

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

(deftest report-pins-history-and-local-artifacts
  (let [report (report)
        artifact-commit
        (get-in report [:certification :local-artifact-commit])
        historical-resource
        (get-in report [:certification :historical-oracle :resource])]
    (is (some? report) "The 39fe821 parity oracle must be committed")
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
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str expected-clojure-base "^{commit}")))))
      (is (zero? (:exit
                  (sh/sh "git" "merge-base" "--is-ancestor"
                         expected-clojure-base "HEAD"))))
      (is (re-matches #"[0-9a-f]{40}" artifact-commit))
      (is (zero? (:exit
                  (sh/sh "git" "cat-file" "-e"
                         (str artifact-commit "^{commit}")))))
      (is (zero? (:exit
                  (sh/sh "git" "merge-base" "--is-ancestor"
                         artifact-commit "HEAD"))))
      (is (seq (:local-artifacts report)))
      (doseq [[path expected-hash] (:local-artifacts report)]
        (testing path
          (is (re-matches #"[0-9a-f]{64}" expected-hash))
          (is (= expected-hash
                 (git-file-sha256 artifact-commit path))
              "the sealed implementation commit must match the ledger")
          (is (= expected-hash
                 (sha256-file path))
              "the checked-out artifact must match the certified bytes"))))))

(deftest exact-upstream-range-is-fully-classified
  (let [{:keys [upstream commit-classifications changed-paths]} (report)]
    (is (= expected-commit-classifications
           (mapv (juxt :commit :classification) commit-classifications)))
    (is (= 2 (:commit-count upstream) (count commit-classifications)))
    (is (= :newline-joined-without-trailing-newline
           (:hash-format upstream)
           (:hash-format changed-paths)))
    (is (every? #(and (contains? allowed-classifications
                                 (:classification %))
                      (= :reviewed-no-port (:status %))
                      (string? (:reason %))
                      (not (str/blank? (:reason %)))
                      (pos-int? (:changed-path-count %))
                      (re-matches #"[0-9a-f]{64}"
                                  (:changed-paths-sha256 %))
                      (str/starts-with?
                       (:source-url %)
                       "https://github.com/github/copilot-sdk/pull/"))
                commit-classifications))
    (is (= #{} (:stable-delta-ids (report))))
    (is (= [] (:stable-deltas (report))))
    (when-let [upstream-repo
               (upstream-repo-or-skip "39fe821 commit/path classification")]
      (let [base (:base-commit upstream)
            target (:target-commit upstream)
            commits (git-lines upstream-repo "rev-list"
                               "--reverse" (str base ".." target))
            paths (git-lines upstream-repo "diff" "--name-only"
                             (str base ".." target))
            classifications (map #(classify-path changed-paths %) paths)]
        (is (= commits expected-commits))
        (is (= (:commits-sha256 upstream)
               (sha256-items commits)))
        (is (= (:count changed-paths) (count paths)))
        (is (= (:sha256 changed-paths) (sha256-items paths)))
        (is (every? some? classifications)
            "Every changed path needs a deliberate classification")
        (is (= (:classification-counts changed-paths)
               (frequencies classifications)))
        (is (= (set paths)
               (set (mapcat :upstream-paths
                            (:intentional-exclusions (report))))))
        (doseq [{:keys [commit subject source-url changed-path-count
                        changed-paths-sha256]}
                commit-classifications
                :let [commit-paths
                      (git-lines upstream-repo "diff-tree"
                                 "--no-commit-id" "--name-only" "-r"
                                 commit)
                      pr-number
                      (second (re-find #"\(#(\d+)\)$" subject))]]
          (testing commit
            (is (= subject
                   (git-output upstream-repo
                               "show" "-s" "--format=%s" commit)))
            (is (= changed-path-count (count commit-paths)))
            (is (= changed-paths-sha256
                   (sha256-items commit-paths)))
            (is (= (str "https://github.com/github/copilot-sdk/pull/"
                        pr-number)
                   source-url))))))))

(deftest exact-target-stable-public-surface-is-unchanged
  (let [report (report)
        surface (:target-public-surface report)
        exclusions (:intentional-exclusions report)
        classified-paths
        (set
         (concat
          (keys (get-in report [:changed-paths :exact-classifications]))
          (mapcat :upstream-paths exclusions)))]
    (is (= [] (get-in report [:public-surface-audit :stable-public-deltas])))
    (is (= [] (get-in report [:public-surface-audit :unclassified-deltas])))
    (is (every? empty? (vals (:symbol-inventory report))))
    (is (= (get-in report [:changed-paths :count])
           (count classified-paths)))
    (is (every? #(and (= :exclude (:decision %))
                      (= :approved (:status %))
                      (contains? (:decision-authorities report)
                                 (:authority %))
                      (seq (:upstream-paths %))
                      (every? (fn [path]
                                (= (:classification %)
                                   (classify-path
                                    (:changed-paths report)
                                    path)))
                              (:upstream-paths %))
                      (or (nil? (:decision-source %))
                          (.isFile (io/file (:decision-source %))))
                      (string? (:reason %))
                      (not (str/blank? (:reason %))))
                exclusions))
    (when-let [upstream-repo
               (upstream-repo-or-skip "39fe821 public-surface evidence")]
      (let [base (get-in report [:upstream :base-commit])
            target (get-in report [:upstream :target-commit])
            read-source
            (memoize
             (fn [pin path]
               (git-output upstream-repo "show" (str pin ":" path))))
            index-path (get-in surface [:package-root :path])
            event-path
            (get-in surface [:package-root :session-events :path])
            base-index-symbols
            (exported-symbols (read-source base index-path))
            target-index-symbols
            (exported-symbols (read-source target index-path))
            base-event-symbols
            (exported-symbols (read-source base event-path))
            target-event-symbols
            (exported-symbols (read-source target event-path))
            target-package-symbols
            (set/union target-index-symbols target-event-symbols)]
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
        (is (= base-index-symbols target-index-symbols))
        (is (= base-event-symbols target-event-symbols))
        (is (= #{"./generated/session-events.js"}
               (star-export-modules (read-source target index-path))))
        (is (= (get-in surface [:package-root :explicit-symbol-count])
               (count target-index-symbols)))
        (is (= (get-in surface
                       [:package-root :explicit-symbols-sha256])
               (sha256-lines (sort target-index-symbols))))
        (is (= (get-in surface
                       [:package-root :session-events :symbol-count])
               (count target-event-symbols)))
        (is (= (get-in surface
                       [:package-root :session-events :symbols-sha256])
               (sha256-lines (sort target-event-symbols))))
        (is (= (get-in surface [:package-root :symbol-count])
               (count target-package-symbols)))
        (is (= (get-in surface [:package-root :symbols-sha256])
               (sha256-lines (sort target-package-symbols))))
        (doseq [surface-key [:types :extension :tool-set :factory]
                :let [{:keys [path symbol-count symbols-sha256]}
                      (get surface surface-key)
                      base-symbols
                      (exported-symbols (read-source base path))
                      target-symbols
                      (exported-symbols (read-source target path))]]
          (testing (name surface-key)
            (is (= base-symbols target-symbols))
            (is (= symbol-count (count target-symbols)))
            (is (= symbols-sha256
                   (sha256-lines (sort target-symbols))))))
        (doseq [[_ {:keys [path class-name method-count methods-sha256]}]
                (:classes surface)
                :let [base-methods
                      (public-class-methods
                       (read-source base path) class-name)
                      target-methods
                      (public-class-methods
                       (read-source target path) class-name)]]
          (testing class-name
            (is (= base-methods target-methods))
            (is (= method-count (count target-methods)))
            (is (= methods-sha256
                   (sha256-lines (sort target-methods))))))
        (is (= #{}
               (changed-exported-declarations
                upstream-repo base target "nodejs/src/client.ts")))
        (doseq [path
                (get-in report
                        [:stable-surface-continuity
                         :unchanged-authority-paths])]
          (is (= (read-source base path) (read-source target path))
              (str "unchanged authority path drifted: " path)))
        (doseq [path
                (get-in report
                        [:stable-surface-continuity
                         :inventoried-authority-paths])]
          (is (not= (read-source base path) (read-source target path))
              (str "inventoried authority path did not change: " path)))))))

(deftest local-contract-outputs-and-release-policy-are-unchanged
  (let [report (report)
        historical-report
        (read-resource
         (get-in report [:certification :historical-oracle :resource]))
        unchanged-artifacts
        [".copilot-schema-version"
         "resources/github/copilot_sdk/api_surface.edn"
         "schemas/api.schema.json"
         "schemas/session-events.schema.json"
         "src/github/copilot_sdk/generated/coerce.clj"
         "src/github/copilot_sdk/generated/event_specs.clj"]]
    (is (= {:runtime-pin "1.0.87-0"
            :changed? false
            :generated-clojure-output
            {:session-event-schema-changed? false
             :event-specs-changed? false
             :event-metadata-changed? false
             :coercion-source-changed? false}}
           (:schema report)))
    (is (= {:sdk "1.0.14.0"
            :changed? false
            :release-required? false}
           (:version report)))
    (is (= "1.0.87-0" (str/trim (slurp ".copilot-schema-version"))))
    (is (str/includes? (slurp "build.clj")
                       "(def version \"1.0.14.0\")"))
    (doseq [path unchanged-artifacts]
      (testing path
        (is (= (get-in historical-report [:local-artifacts path])
               (sha256-file path)))))))
