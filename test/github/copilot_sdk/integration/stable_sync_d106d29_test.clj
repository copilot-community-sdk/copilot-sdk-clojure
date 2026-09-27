(ns github.copilot-sdk.integration.stable-sync-d106d29-test
  "Exact-pin stable-surface and canonical Workflow migration certification."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [github.copilot-sdk.integration.stable-sync-support :as ss]))

(def ^:private resource "resources/stable_upstream_delta_d106d29.edn")
(def ^:private base "4001c1da7d832c51bad1d38619c1a082af390efb")
(def ^:private target "d106d29dc6c5112da2abdae59008571b6692f12b")
(def ^:private clojure-base "ce48c20ad87782198a0db762d920b5f3bc5f69b6")
(def ^:private implementation "c8dc16971ef5c831528d1c4f3687d66af344286f")
(def ^:private classifications
  #{:stable-public :experimental :internal :generated-only :language-specific})
(def ^:private authority-paths
  #{"nodejs/package.json" "nodejs/src/index.ts" "nodejs/src/types.ts"
    "nodejs/src/client.ts" "nodejs/src/session.ts" "nodejs/src/extension.ts"
    "nodejs/src/toolSet.ts" "nodejs/src/canvas.ts" "nodejs/src/workflow.ts"
    "nodejs/src/factory.ts" "nodejs/src/generated/session-events.ts"
    "nodejs/src/generated/rpc.ts"})
(def ^:private additional-spec-keys
  #{:github.copilot-sdk.specs/assistant.turn_end-data
    :github.copilot-sdk.specs/workflow-run-id
    :github.copilot-sdk.specs/workflow-limit-overrides
    :github.copilot-sdk.specs/workflow-progress-options})
(def ^:private changed-fdefs
  #{'github.copilot-sdk.workflow/get-run-progress
    'github.copilot-sdk.workflow/<get-run-progress})

(defn- report []
  (or (ss/read-resource resource)
      (throw (ex-info "Missing exact-pin inventory" {:resource resource}))))

(defn- local-source [commit path]
  (ss/shell-output "git" "show" (str commit ":" path)))

(defn- fingerprint [items]
  [(count items) (ss/sha256-lines (sort items))])

(defn- classified-symbols [groups]
  (into #{} cat (vals groups)))

(defn- interface-names [source]
  (into #{} (map second) (re-seq #"(?m)^export interface (\w+)" source)))

(defn- field-signatures [source class-name]
  (let [body (second
              (re-find (re-pattern (str "(?ms)^export class " class-name
                                        "\\b[^\\{]*\\{(.*?)^\\}"))
                       source))]
    (when-not body
      (throw (ex-info "Missing public class" {:class-name class-name})))
    (into #{}
          (map #(str/trim (or (second %) (nth % 2))))
          (re-seq #"(?m)^    ((?:(?:public|readonly|static)\s+)*[A-Za-z_$][\w$]*[?!]?:[^\n=;]+)(?:[=;])|^        (public readonly [A-Za-z_$][\w$]*[?!]?:[^\n,]+),"
                  body))))

(defn- recorded-test? [source test-symbol]
  (and (str/includes? source (str "(ns " (namespace test-symbol)))
       (boolean
        (re-find
         (re-pattern (str "(?m)^\\(deftest\\s+(?:\\^:[^\\s]+\\s+)*"
                          (java.util.regex.Pattern/quote (name test-symbol))
                          "(?:\\s|$)"))
         source))))

(deftest historical-oracle-and-complete-implementation-remain-pinned
  (let [r (report)
        history (get-in r [:certification :historical-oracle])
        artifacts (:sealed-local-artifacts r)
        removed (:removed-local-artifacts r)]
    (is (= (get-in r [:certification :clojure-base-commit]) clojure-base))
    (is (= (:resource history) "resources/stable_upstream_delta_4001c1d.edn"))
    (is (= (ss/sha256-resource (:resource history))
           (ss/git-file-sha256 clojure-base (str "test/" (:resource history)))
           (:sha256 history)))
    (is (= (get-in r [:certification :local-artifact-seal])
           {:status :sealed :commit implementation}))
    (is (= (ss/shell-output "git" "merge-base" "--is-ancestor" implementation "HEAD") ""))
    (is (= (set/difference
            (set (ss/git-lines "." "diff" "--no-renames" "--name-only"
                               clojure-base implementation))
            #{(str "test/" resource)
              "test/github/copilot_sdk/integration/stable_sync_d106d29_test.clj"})
           (set/union (set (keys artifacts)) removed)))
    (is (empty? (set/intersection (set (keys artifacts)) removed)))
    (doseq [[path expected] artifacts]
      (testing path
        (is (= (ss/git-file-sha256 implementation path) expected))))
    (doseq [path removed]
      (is (= (ss/git-lines "." "ls-tree" "-r" "--name-only" implementation "--" path) [])
          path))
    (is (set/subset?
         #{"src/github/copilot_sdk/factory.clj"
           "doc/api/github.copilot-sdk.factory.html"
           "doc/api/agent-factories.html"}
         removed))
    (is (= (:version r) {:sdk "1.0.14.0" :changed? false :release-required? false}))
    (is (str/includes? (local-source implementation "build.clj") "(def version \"1.0.14.0\")"))
    (is (= (local-source implementation ".copilot-schema-version") "1.0.89-5"))
    (is (str/includes? (local-source implementation ".github/workflows/ci.yml") target))))

(deftest every-upstream-commit-and-path-is-classified
  (let [{:keys [upstream commit-classifications changed-paths]} (report)]
    (is (= (:base-commit upstream) base))
    (is (= (:target-commit upstream) target))
    (is (= (:commit-count upstream) 1))
    (is (= (mapv :commit commit-classifications) [target]))
    (is (= (:count changed-paths) 306 (reduce + (vals (:classification-counts changed-paths)))))
    (is (= (:rename-policy changed-paths) :no-renames))
    (is (= (:hash-format changed-paths) (:hash-format upstream)
           :newline-joined-without-trailing-newline))
    (doseq [entry (concat commit-classifications (:prefix-classifications changed-paths))]
      (is (classifications (:classification entry)))
      (is (not (str/blank? (:reason entry)))))
    (is (every? classifications (vals (:exact-classifications changed-paths))))
    (when-let [repo (ss/upstream-repo-or-skip "d106d29 commit/path classification")]
      (let [commits (ss/git-lines repo "rev-list" "--reverse" (str base ".." target))
            paths (ss/git-lines repo "diff" "--no-renames" "--name-only" base target)
            assigned (map #(ss/classify-path changed-paths %) paths)]
        (is (= commits [target]))
        (is (= (ss/sha256-items commits) (:commits-sha256 upstream)))
        (is (= [(count paths) (ss/sha256-items paths)]
               [(:count changed-paths) (:sha256 changed-paths)]))
        (is (every? classifications assigned))
        (is (= (frequencies assigned) (:classification-counts changed-paths)))
        (is (set/subset? (set (keys (:exact-classifications changed-paths))) (set paths))))
      (doseq [{:keys [commit subject source-url changed-path-count changed-paths-sha256]}
              commit-classifications
              :let [paths (ss/git-lines repo "diff-tree" "--no-commit-id" "--no-renames"
                                        "--name-only" "-r" commit)]]
        (is (= (ss/git-output repo "show" "-s" "--format=%s" commit) subject))
        (is (= source-url (str "https://github.com/github/copilot-sdk/commit/" commit)))
        (is (= [(count paths) (ss/sha256-items paths)]
               [changed-path-count changed-paths-sha256]))))))

(deftest complete-package-types-builders-and-session-surface-match-the-target
  (let [r (report)
        surface (:target-public-surface r)
        modules (:modules surface)]
    (is (= (set (keys (:source-blobs surface))) authority-paths))
    (is (= (set (keys modules)) (disj authority-paths "nodejs/package.json")))
    (is (= (get-in surface [:package-root :fingerprint 0]) 784))
    (is (= (count (:interfaces surface)) 107))
    (is (= (set (keys (:trees surface))) #{"nodejs/src" "nodejs/src/generated" "nodejs/test"}))
    (doseq [[_ entry] (:test-inventory r)]
      (is (classifications (:classification entry)))
      (is (not (str/blank? (:review entry)))))
    (doseq [[_ module] modules, key [:added :removed :changed]]
      (is (every? classifications (keys (get module key))))
      (is (= (count (classified-symbols (get module key)))
             (reduce + 0 (map count (vals (get module key)))))))
    (when-let [repo (ss/upstream-repo-or-skip "d106d29 complete public-surface certification")]
      (let [source (memoize
                    (fn [pin path]
                      (if (seq (ss/git-lines repo "ls-tree" "-r" "--name-only" pin "--" path))
                        (ss/git-output repo "show" (str pin ":" path))
                        "")))
            before #(source base %)
            after #(source target %)]
        (doseq [[path pair] (merge (:source-blobs surface) (:trees surface)
                                   (into {} (map (fn [[path entry]] [path (:blobs entry)]))
                                         (:test-inventory r)))
                [pin expected] (map vector [base target] pair)]
          (testing (str pin ":" path)
            (if expected
              (is (= (ss/git-output repo "rev-parse" (str pin ":" path)) expected))
              (is (= (ss/git-lines repo "ls-tree" "-r" "--name-only" pin "--" path) [])))))
        (doseq [[path module] modules
                :let [old (ss/exported-symbols (before path))
                      new (ss/exported-symbols (after path))]]
          (testing path
            (is (= (fingerprint new) (:fingerprint module)))
            (is (= (set/difference new old) (classified-symbols (:added module))))
            (is (= (set/difference old new) (classified-symbols (:removed module))))
            (is (= (if (and (seq (before path)) (seq (after path)))
                     (ss/changed-exported-declarations repo base target path)
                     #{})
                   (classified-symbols (:changed module))))))
        (let [index "nodejs/src/index.ts"
              events "nodejs/src/generated/session-events.ts"
              old (set/union (ss/exported-symbols (before index))
                             (ss/exported-symbols (before events)))
              new (set/union (ss/exported-symbols (after index))
                             (ss/exported-symbols (after events)))
              root (:package-root surface)]
          (is (= (ss/star-export-modules (after index)) (:star-exports root)
                 #{"./generated/session-events.js"}))
          (is (= (fingerprint new) (:fingerprint root)))
          (is (= (set/difference new old) (:added root)))
          (is (= (set/difference old new) (:removed root))))
        (doseq [[_ {:keys [path class-name signatures fingerprint added-signatures
                           removed-signatures fields removed-fields]}] (:classes surface)
                :let [old (ss/public-class-method-signatures (before path) class-name)
                      new (ss/public-class-method-signatures (after path) class-name)]]
          (is (= new signatures))
          (is (= [(count new) (ss/sha256-lines (sort new))] fingerprint))
          (is (= (set/difference (set new) (set old)) (set added-signatures)))
          (is (= (set/difference (set old) (set new)) (set removed-signatures)))
          (is (= (field-signatures (after path) class-name) fields))
          (is (= (set/difference (field-signatures (before path) class-name) fields)
                 removed-fields)))
        (let [old-source (before "nodejs/src/types.ts")
              new-source (after "nodejs/src/types.ts")
              old-names (interface-names old-source)
              new-names (interface-names new-source)]
          (is (= (set (keys (:interfaces surface))) (set/union old-names new-names)))
          (is (= (set/difference old-names new-names) #{"FactoryLimits" "FactoryMeta"}))
          (doseq [[interface expected] (:interfaces surface)
                  :let [old (if (old-names interface) (ss/interface-fields old-source interface) #{})
                        new (if (new-names interface) (ss/interface-fields new-source interface) #{})]]
            (is (= (fingerprint new) (:fingerprint expected)))
            (is (= (set/difference new old) (:added-fields expected)))
            (is (= (set/difference old new) (:removed-fields expected)))))
        (doseq [path (:unchanged-authority-paths surface)]
          (is (= (before path) (after path)) path))
        (let [package (json/read-str (after "nodejs/package.json"))
              release (get-in r [:upstream :release-tag])]
          (is (= (get package "copilotCliVersion") "1.0.89-5"))
          (is (= (get package "version") "0.0.0-dev"))
          (is (= (set (keys (get package "exports"))) #{"." "./extension"}))
          (is (= (ss/git-output repo "rev-parse" (str "refs/tags/" (:name release) "^{commit}"))
                 (:commit release))))))))

(defn- migrate-api-name [value]
  (if (and (or (symbol? value) (keyword? value))
           (not= value :github.copilot-sdk.specs/factory-run-id))
    (let [rename #(-> % (str/replace "factories" "workflows")
                      (str/replace "factory" "workflow"))]
      (if (symbol? value)
        (symbol (rename (str value)))
        (keyword (rename (subs (str value) 1)))))
    value))

(defn- apply-progress-argument-contracts [snapshot]
  (reduce
   (fn [surface fdef]
     (update-in
      surface [:namespaces 'github.copilot-sdk.workflow :fdefs fdef]
      #(walk/postwalk
        (fn [form]
          (if (= form '(clojure.spec.alpha/? clojure.core/map?))
            '(clojure.spec.alpha/? :github.copilot-sdk.specs/workflow-progress-options)
            form))
        %)))
   snapshot
   changed-fdefs))

(deftest snapshot-changes-only-the-declared-contracts
  (let [r (report)
        path "resources/github/copilot_sdk/api_surface.edn"
        old (edn/read-string (local-source clojure-base path))
        new (edn/read-string (local-source implementation path))
        spec-path [:namespaces 'github.copilot-sdk.specs :spec-keys]
        expected (-> (walk/postwalk migrate-api-name old)
                     (update-in spec-path #(vec (sort (into (set %) additional-spec-keys))))
                     apply-progress-argument-contracts)]
    (is (= new expected))
    (is (= (get-in r [:api-snapshot-impact :additional-spec-keys]) additional-spec-keys))
    (is (= (get-in r [:api-snapshot-impact :non-migration-fdef-changes]) changed-fdefs))
    (is (= (get-in r [:api-snapshot-impact :added-spec-keys])
           (set/difference (set (get-in new spec-path)) (set (get-in old spec-path)))))
    (is (= (get-in r [:api-snapshot-impact :removed-spec-keys])
           (set/difference (set (get-in old spec-path)) (set (get-in new spec-path)))))))

(deftest contract-matrix-links-executable-proofs-and-explicit-exclusions
  (let [r (report)
        snapshot (edn/read-string
                  (local-source implementation "resources/github/copilot_sdk/api_surface.edn"))
        specs (set (get-in snapshot [:namespaces 'github.copilot-sdk.specs :spec-keys]))
        deltas (:stable-deltas r)
        migration (first (:experimental-migrations r))]
    (is (= (:unclassified-deltas r) []))
    (is (= (set (map :id deltas))
           #{:client/process-file-logging :events/parent-call-metadata
             :events/auto-tier-model-change-source :events/workflow-run-correlation}))
    (doseq [delta deltas]
      (is (= (:classification delta) :stable-public)))
    (doseq [delta (conj deltas migration)]
      (doseq [key [:authority :builders :wire :idiom :specs :fdefs :proof-states :tests :docs]]
        (is (seq (get delta key)) (str (:id delta) " " key)))
      (is (set/subset? (set (:specs delta)) specs))
      (doseq [fdef (:fdefs delta)]
        (is (some? (get-in snapshot [:namespaces (symbol (namespace fdef)) :fdefs fdef]))
            (str "Missing registered fdef " fdef))))
    (doseq [entry (conj deltas migration)]
      (is (not (str/blank? (:semantics entry))))
      (doseq [test-symbol (:tests entry)
              :let [path (str "test/" (-> (namespace test-symbol)
                                          (str/replace "." "/")
                                          (str/replace "-" "_")) ".clj")]]
        (is (contains? (:sealed-local-artifacts r) path))
        (is (recorded-test? (local-source implementation path) test-symbol)
            (str "Missing contract proof " test-symbol)))
      (doseq [path (:docs entry)]
        (is (contains? (:sealed-local-artifacts r) path))))
    (is (= (select-keys migration [:id :classification :decision :aliases])
           {:id :orchestration/factory-to-workflow :classification :experimental
            :decision :migrate-existing-surface :aliases :none}))
    (is (contains? (:sealed-local-artifacts r) (:adr migration)))
    (is (= (count (:intentional-exclusions r)) 8))
    (doseq [entry (:intentional-exclusions r)]
      (is (classifications (:classification entry)))
      (is (keyword? (:decision entry)))
      (is (not (str/blank? (:reason entry)))))))

(deftest schema-artifacts-retain-the-wire-idiom-boundary
  (let [r (report)
        schema (:schema r)
        events (json/read-str (local-source implementation "schemas/session-events.schema.json"))
        api (json/read-str (local-source implementation "schemas/api.schema.json"))
        source (local-source implementation "src/github/copilot_sdk/specs.clj")]
    (is (= (:runtime-pin schema) "1.0.89-5"))
    (doseq [[path expected] (:artifacts schema)]
      (is (= (ss/git-file-sha256 implementation path) expected)))
    (doseq [definition ["AssistantTurnStartData" "AssistantTurnEndData" "ModelCallFailureData"]]
      (is (= (get-in events ["definitions" definition "properties" "parentToolCallId" "type"]) "string"))
      (is (not (contains? (set (get-in events ["definitions" definition "required"])) "parentToolCallId"))))
    (is (= (get-in events ["definitions" "CompactionCompleteData" "properties"
                           "activeWorkflowSummary" "visibility"])
           "internal"))
    (is (not (str/includes? source "(s/def ::active-workflow-summary")))
    (is (not (str/includes? source "(s/def ::model.call_start-data")))
    (is (= (get-in api ["clientSession" "workflow" "execute" "rpcMethod"]) "workflow.execute"))
    (is (= (get-in api ["clientSession" "workflow" "abort" "rpcMethod"]) "workflow.abort"))
    (is (= (set (keys (get-in api ["definitions" "WorkflowGetRunProgressRequest" "properties"])))
           #{"runId" "phaseId" "afterSeq" "beforeSeq" "limit"}))
    (is (= (get-in api ["definitions" "WorkflowGetRunProgressRequest" "properties" "limit" "minimum"]) 1))
    (is (= (get-in api ["definitions" "WorkflowGetRunProgressRequest" "properties" "limit" "maximum"]) 500))
    (is (nil? (get-in api ["clientSession" "factory"])))
    (is (nil? (get-in api ["session" "factory"])))))
