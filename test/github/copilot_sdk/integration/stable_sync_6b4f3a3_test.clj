(ns github.copilot-sdk.integration.stable-sync-6b4f3a3-test
  "Exact-pin certification of the complete Node SDK surface at CLI 1.0.92-4."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.integration.stable-sync-support :as ss]))

(def ^:private resource "resources/stable_upstream_delta_6b4f3a3.edn")
(def ^:private base "ef04633cc84e4ba8e79888a39259ca276f5de732")
(def ^:private target "6b4f3a3bde7eb9a8604617b91effe0f4a2e3921b")
(def ^:private clojure-base "49f74ee22cc335008528b4970898b904b18e1df8")
(def ^:private implementation "245fcbff7ec65e157cbebf46d1b9366226342bda")
(def ^:private classifications
  #{:stable-public :experimental :internal :generated-only :language-specific})
(def ^:private authority-paths
  #{"nodejs/package.json" "nodejs/src/index.ts" "nodejs/src/types.ts"
    "nodejs/src/client.ts" "nodejs/src/session.ts" "nodejs/src/extension.ts"
    "nodejs/src/toolSet.ts" "nodejs/src/canvas.ts" "nodejs/src/workflow.ts"
    "nodejs/src/host.ts" "nodejs/src/installationConfirmation.ts"
    "nodejs/src/ffiRuntimeHost.ts" "nodejs/src/copilotRequestHandler.ts"
    "nodejs/src/cliVersion.ts" "nodejs/src/runtimeArtifacts.ts"
    "nodejs/src/schema.ts" "nodejs/src/sdkProtocolVersion.ts"
    "nodejs/src/sessionFsProvider.ts" "nodejs/src/telemetry.ts"
    "nodejs/src/generated/session-events.ts" "nodejs/src/generated/rpc.ts"})
(def ^:private added-functions
  '#{github.copilot-sdk/session-fs-write-failure
     github.copilot-sdk/session-fs-write-failure?
     github.copilot-sdk.session/session-fs-write-failure
     github.copilot-sdk.session/session-fs-write-failure?})
(def ^:private added-specs
  #{:github.copilot-sdk.specs/elicitation-requested-schema
    :github.copilot-sdk.specs/exit-plan-mode-action
    :github.copilot-sdk.specs/human-response-actor
    :github.copilot-sdk.specs/human-response-recorded-response
    :github.copilot-sdk.specs/human_response.recorded-data
    :github.copilot-sdk.specs/on-subagent-start
    :github.copilot-sdk.specs/on-subagent-stop
    :github.copilot-sdk.specs/read-file-bytes
    :github.copilot-sdk.specs/reasoning-effort-model
    :github.copilot-sdk.specs/tool-shell-output-stream
    :github.copilot-sdk.specs/tool.shell_output-data
    :github.copilot-sdk.specs/write-file-bytes})

(defn- report []
  (or (ss/read-resource resource)
      (throw (ex-info "Missing exact-pin inventory" {:resource resource}))))

(defn- local-source [path]
  (ss/shell-output "git" "show" (str implementation ":" path)))

(defn- fingerprint [items]
  [(count items) (ss/sha256-lines (sort items))])

(defn- classified-symbols [groups]
  (into #{} cat (vals groups)))

(defn- interface-fields [source]
  (into (sorted-map)
        (map (fn [[_ name]] [name (ss/interface-fields source name)]))
        (re-seq #"(?m)^export interface (\w+)" source)))

(defn- field-signatures [source class-name]
  (let [body (second (re-find (re-pattern (str "(?ms)^export class " class-name
                                               "\\b[^\\{]*\\{(.*?)^\\}")) source))]
    (when-not body
      (throw (ex-info "Missing public class" {:class-name class-name})))
    (into #{}
          (map #(str/trim (or (second %) (nth % 2))))
          (re-seq #"(?m)^    ((?:(?:public|readonly|static)\s+)*[A-Za-z_$][\w$]*[?!]?:[^\n=;]+)(?:[=;])|^        (public readonly [A-Za-z_$][\w$]*[?!]?:[^\n,]+),"
                  body))))

(deftest historical-certification-and-version-remain-pinned
  (let [r (report)
        history (get-in r [:certification :historical-oracle])]
    (is (= (get-in r [:certification :clojure-base-commit]) clojure-base))
    (is (= (:resource history) "resources/stable_upstream_delta_ef04633.edn"))
    (is (= (ss/sha256-resource (:resource history))
           (ss/git-file-sha256 clojure-base (str "test/" (:resource history)))
           (:sha256 history)))
    (is (= (:version r) {:sdk "1.0.16.0" :changed? false :release-required? false}))
    (is (= (:method-deltas r) []))
    (is (= (:unclassified-deltas r) []))))

(deftest implementation-and-runtime-artifacts-remain-sealed
  (let [r (report)
        artifacts (:sealed-local-artifacts r)
        unchanged (:unchanged-local-artifacts r)
        actual-changes (set (ss/git-lines "." "diff" "--no-renames" "--name-only"
                                          clojure-base implementation))]
    (is (= (get-in r [:certification :local-artifact-seal])
           {:status :sealed :commit implementation}))
    (is (= (ss/shell-output "git" "merge-base" "--is-ancestor" implementation "HEAD") ""))
    (is (= actual-changes (set/difference (set (keys artifacts)) unchanged)))
    (is (empty? (set/intersection actual-changes unchanged)))
    (doseq [[path expected] artifacts]
      (is (= (ss/git-file-sha256 implementation path) expected) path))
    (doseq [path unchanged]
      (is (= (ss/git-file-sha256 clojure-base path)
             (ss/git-file-sha256 implementation path)) path))
    (is (contains? artifacts (:decision r)))
    (is (= (local-source ".copilot-schema-version") "1.0.92-4"))
    (is (str/includes? (local-source ".github/workflows/ci.yml") target))
    (is (str/includes? (local-source "build.clj") "(def version \"1.0.16.0\")"))
    (doseq [[path expected] (get-in r [:schema :artifacts])]
      (is (= (ss/git-file-sha256 implementation path) expected) path))
    (let [schema (json/read-str (local-source "schemas/session-events.schema.json"))]
      (is (= (get-in schema ["definitions" "AssistantMessageReasoningBlocks" "not"])
             {"required" ["blocks" "orderedBlocks"]}))
      (is (= (get-in schema ["definitions" "AssistantMessageReasoningBlocks" "stability"])
             "experimental"))
      (is (= (get-in schema ["definitions" "ToolExecutionCompleteData" "properties" "fileEdits" "stability"])
             "experimental")))))

(deftest every-commit-path-and-test-delta-is-classified
  (let [{:keys [upstream commit-classifications changed-paths target-public-surface
                test-inventory]} (report)]
    (is (= (:base-commit upstream) base))
    (is (= (:target-commit upstream) target))
    (is (= (:commit-count upstream) (count commit-classifications) 1))
    (is (= (:count changed-paths) (reduce + (vals (:classification-counts changed-paths))) 368))
    (is (= (:rename-policy changed-paths) :no-renames))
    (is (= (:hash-format changed-paths) (:hash-format upstream)
           :newline-joined-without-trailing-newline))
    (is (= (count test-inventory) 53))
    (doseq [entry commit-classifications]
      (is (seq (:classifications entry)))
      (is (every? classifications (:classifications entry)))
      (is (not (str/blank? (:reason entry)))))
    (doseq [[_ entry] test-inventory]
      (is (some some? (:blobs entry)))
      (is (classifications (:classification entry)))
      (is (not (str/blank? (:review entry)))))
    (doseq [[_ module] (:modules target-public-surface), kind [:added :removed :changed]]
      (is (every? classifications (keys (get module kind))))
      (is (= (count (classified-symbols (get module kind)))
             (reduce + 0 (map count (vals (get module kind)))))))
    (when-let [repo (ss/upstream-repo-or-skip "6b4f3a3 commit/path inventory")]
      (let [commits (ss/git-lines repo "rev-list" "--reverse" (str base ".." target))
            paths (ss/git-lines repo "diff" "--no-renames" "--name-only" base target)
            assigned (map #(ss/classify-path changed-paths %) paths)]
        (is (= commits (mapv :commit commit-classifications)))
        (is (= (ss/sha256-items commits) (:commits-sha256 upstream)))
        (is (= [(count paths) (ss/sha256-items paths)]
               [(:count changed-paths) (:sha256 changed-paths)]))
        (is (every? classifications assigned))
        (is (= (frequencies assigned) (:classification-counts changed-paths)))
        (is (set/subset? (set (keys (:exact-classifications changed-paths))) (set paths)))
        (is (= (into #{} (filter #(str/starts-with? % "nodejs/test/")) paths)
               (set (keys test-inventory))))
        (is (set/subset? (into #{} (filter #(str/starts-with? % "nodejs/src/")) paths)
                         authority-paths)))
      (doseq [{:keys [commit subject source-url changed-path-count changed-paths-sha256]
               expected-classes :classifications} commit-classifications
              :let [paths (ss/git-lines repo "diff-tree" "--no-commit-id" "--no-renames"
                                        "--name-only" "-r" commit)]]
        (is (= (ss/git-output repo "show" "-s" "--format=%s" commit) subject))
        (is (= source-url (str "https://github.com/github/copilot-sdk/commit/" commit)))
        (is (= [(count paths) (ss/sha256-items paths)]
               [changed-path-count changed-paths-sha256]))
        (is (= (set (map #(ss/classify-path changed-paths %) paths)) expected-classes))))))

(deftest complete-root-types-builders-and-session-inventory
  (let [r (report)
        surface (:target-public-surface r)]
    (is (= (set (keys (:source-blobs surface))) authority-paths))
    (is (= (set (keys (:modules surface))) (disj authority-paths "nodejs/package.json")))
    (is (= (get-in surface [:package-root :fingerprint 0]) 824))
    (is (= (get-in surface [:package-root :added])
           #{"SessionFsWriteFailure"
             "SubagentStartHandler" "SubagentStartHookInput" "SubagentStartHookOutput"
             "SubagentStopHandler" "SubagentStopHookInput" "SubagentStopHookOutput"
             "HumanResponseActor" "HumanResponseRecordedData" "HumanResponseRecordedEvent"
             "HumanResponseRecordedResponse" "ToolShellOutputData" "ToolShellOutputEvent"
             "ToolShellOutputStream" "ToolExecutionCompleteFileEdit" "ToolExecutionCompleteFileEditKind"}))
    (is (= (get-in surface [:package-root :removed]) #{}))
    (is (= (get-in surface [:interfaces :fingerprint 0]) 109))
    (is (= (get-in surface [:interfaces :added])
           #{"SubagentStartHookInput" "SubagentStartHookOutput" "SubagentStopHookInput"}))
    (is (= (set (keys (:classes surface))) #{:client :session :host}))
    (is (= (get-in surface [:classes :host :classification]) :experimental))
    (when-let [repo (ss/upstream-repo-or-skip "6b4f3a3 complete public-surface inventory")]
      (let [source (memoize (fn [pin path] (ss/git-output repo "show" (str pin ":" path))))
            before #(source base %)
            after #(source target %)]
        (is (= (conj (set (ss/git-lines repo "ls-tree" "-r" "--name-only" target "nodejs/src"))
                     "nodejs/package.json")
               authority-paths))
        (doseq [[path pair] (merge (:source-blobs surface) (:trees surface)
                                   (into {} (map (fn [[path entry]] [path (:blobs entry)]))
                                         (:test-inventory r)))
                [pin expected] (map vector [base target] pair)]
          (testing (str pin ":" path)
            (if expected
              (is (= (ss/git-output repo "rev-parse" (str pin ":" path)) expected))
              (is (= (ss/git-lines repo "ls-tree" "--name-only" pin "--" path) [])))))
        (doseq [[path module] (:modules surface)
                :let [old (ss/exported-symbols (before path))
                      new (ss/exported-symbols (after path))]]
          (testing path
            (is (= (fingerprint new) (:fingerprint module)))
            (is (= (set/difference new old) (classified-symbols (:added module))))
            (is (= (set/difference old new) (classified-symbols (:removed module))))
            (is (= (ss/changed-exported-declarations repo base target path)
                   (classified-symbols (:changed module))))))
        (let [exports #(set/union (ss/exported-symbols (% "nodejs/src/index.ts"))
                                  (ss/exported-symbols (% "nodejs/src/generated/session-events.ts")))
              old (exports before) new (exports after)
              root (:package-root surface)]
          (is (= (fingerprint new) (:fingerprint root)))
          (is (= (ss/star-export-modules (after "nodejs/src/index.ts"))
                 (:star-exports root) #{"./generated/session-events.js"}))
          (is (= (set/difference new old) (:added root)))
          (is (= (set/difference old new) (:removed root))))
        (doseq [[_ {:keys [path class-name signatures fields added-signatures removed-signatures]
                    expected-fingerprint :fingerprint}] (:classes surface)
                :let [old (ss/public-class-method-signatures (before path) class-name)
                      new (ss/public-class-method-signatures (after path) class-name)]]
          (is (= new signatures))
          (is (= (fingerprint new) expected-fingerprint))
          (is (= (set/difference (set new) (set old)) (set added-signatures) #{}))
          (is (= (set/difference (set old) (set new)) (set removed-signatures) #{}))
          (is (= (field-signatures (after path) class-name) fields))
          (is (= (field-signatures (before path) class-name) fields)))
        (let [old (interface-fields (before "nodejs/src/types.ts"))
              new (interface-fields (after "nodejs/src/types.ts"))
              interfaces (:interfaces surface)]
          (is (= (fingerprint (map (fn [[name fields]] (pr-str [name (sort fields)])) new))
                 (:fingerprint interfaces)))
          (is (= (set/difference (set (keys new)) (set (keys old))) (:added interfaces)))
          (is (= (set/difference (set (keys old)) (set (keys new))) (:removed interfaces)))
          (is (= (into #{} (filter #(not= (get old %) (get new %))) (set/union (set (keys old)) (set (keys new))))
                 (set (keys (:changed interfaces)))))
          (doseq [[name expected] (:changed interfaces)]
            (is (= (get new name) (:fields expected)))
            (is (= (set/difference (get new name #{}) (get old name #{})) (:added expected)))
            (is (= (set/difference (get old name #{}) (get new name #{})) (:removed expected)))))
        (doseq [path (:unchanged-authority-paths surface)]
          (is (= (before path) (after path)) path))
        (doseq [contract (:stable-deltas r), authority (:authority contract)
                :let [[path symbol] (str/split authority #":" 2)]]
          (is (str/includes? (after path) symbol) authority))
        (let [package (json/read-str (after "nodejs/package.json"))
              release (get-in r [:upstream :release-tag])]
          (is (= (get package "copilotCliVersion") "1.0.92-4"))
          (is (= (get package "version") "0.0.0-dev"))
          (is (= (set (keys (get package "exports"))) #{"." "./extension"}))
          (is (= (ss/git-output repo "rev-parse" (str "refs/tags/" (:name release) "^{commit}"))
                 (:commit release))))))))

(deftest stable-contract-matrix-and-api-snapshot-are-complete
  (let [r (report)
        path "resources/github/copilot_sdk/api_surface.edn"
        old (edn/read-string (ss/shell-output "git" "show" (str clojure-base ":" path)))
        snapshot (edn/read-string (local-source path))
        restored
        (reduce
         (fn [api sym]
           (let [ns (symbol (namespace sym))]
             (-> api
                 (update-in [:namespaces ns :vars] dissoc (symbol (name sym)))
                 (update-in [:namespaces ns :fdefs] dissoc sym))))
         (update-in snapshot [:namespaces 'github.copilot-sdk.specs :spec-keys]
                    #(vec (remove added-specs %)))
         added-functions)]
    (is (= restored old))
    (is (= (:api-snapshot-impact r)
           {:added-functions added-functions :added-fdefs added-functions :added-spec-keys added-specs}))
    (is (= (set (map :id (:stable-deltas r)))
           #{:hooks/subagent-lifecycle :session-fs/binary :session-fs/partial-write-failure
             :tools/apply-patch-string-override :events/shell-output :events/response-provenance
             :events/reasoning-effort-owner}))
    (doseq [contract (:stable-deltas r)]
      (is (= (:classification contract) :stable-public))
      (doseq [key [:authority :builders :wire :idiom :semantics :specs :fdefs :tests :proof-states :docs]]
        (is (seq (get contract key)) (str (:id contract) " " key)))
      (doseq [path (:docs contract)]
        (is (contains? (:sealed-local-artifacts r) path) path))
      (doseq [fdef (:fdefs contract)]
        (is (contains? (get-in snapshot [:namespaces (symbol (namespace fdef)) :fdefs]) fdef) (str fdef)))
      (doseq [spec (:specs contract)]
        (is (contains? (set (get-in snapshot [:namespaces 'github.copilot-sdk.specs :spec-keys])) spec))))
    (doseq [entry (concat (:stable-deltas r) (:experimental-maintenance r))
            test (:tests entry)
            :let [path (str "test/" (-> (namespace test) (str/replace "." "/")
                                        (str/replace "-" "_")) ".clj")
                  declaration (re-pattern (str "(?m)^\\(deftest(?:\\s+\\^:\\S+)?\\s+"
                                               (java.util.regex.Pattern/quote (name test)) "(?:\\s|$)"))]]
      (is (contains? (:sealed-local-artifacts r) path) path)
      (is (re-find declaration (local-source path)) (str test)))
    (doseq [entry (concat (:experimental-maintenance r) (:intentional-exclusions r))]
      (is (classifications (:classification entry)))
      (is (keyword? (:decision entry)))
      (is (not (str/blank? (:reason entry)))))))
