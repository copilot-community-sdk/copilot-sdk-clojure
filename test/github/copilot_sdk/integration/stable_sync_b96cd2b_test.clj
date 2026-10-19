(ns github.copilot-sdk.integration.stable-sync-b96cd2b-test
  "Complete public-surface certification for the CLI 1.0.100-1 snapshot."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk.integration.stable-sync-support :as ss]))

(def ^:private resource "resources/stable_upstream_delta_b96cd2b.edn")
(def ^:private report-path (str "test/" resource))
(def ^:private base "404304416ac5358651e3877a33bc9e8f4e482162")
(def ^:private target "b96cd2b54f7eb87458fd50f485a751c712e88f62")
(def ^:private clojure-base "3de1059b0d3582ab13e42b526a8902e2b503f8cb")
(def ^:private classifications
  #{:stable-public :experimental :internal :generated-only :language-specific})

(defn- report []
  (or (ss/read-resource resource)
      (throw (ex-info "Missing exact-pin certification" {:resource resource}))))

(defn- local-source [path]
  (slurp path))

(defn- fingerprint [items]
  [(count items) (ss/sha256-lines (sort items))])

(defn- classified-symbols [groups]
  (into #{} cat (vals groups)))

(defn- interface-inventory [source]
  (into (sorted-map)
        (map (fn [[_ name]] [name (ss/interface-fields source name)]))
        (re-seq #"(?m)^export interface (\w+)" source)))

(defn- interface-deltas [before after]
  (let [old (interface-inventory before)
        current (interface-inventory after)]
    (into {}
          (keep (fn [name]
                  (when (not= (get old name) (get current name))
                    [name {:added (set/difference (get current name #{}) (get old name #{}))
                           :removed (set/difference (get old name #{}) (get current name #{}))}])))
          (set/union (set (keys old)) (set (keys current))))))

(defn- class-fields [source class-name]
  (let [body (second (re-find (re-pattern (str "(?ms)^export class " class-name
                                               "\\b[^\\{]*\\{(.*?)^\\}")) source))]
    (when-not body
      (throw (ex-info "Missing public class" {:class-name class-name})))
    (into #{}
          (map #(str/trim (or (second %) (nth % 2))))
          (re-seq #"(?m)^    ((?:(?:public|readonly|static)\s+)*[A-Za-z_$][\w$]*[?!]?:[^\n=;]+)(?:[=;])|^        (public readonly [A-Za-z_$][\w$]*[?!]?:[^\n,]+),"
                  body))))

(defn- rpc-methods [source]
  (into #{} (map second) (re-seq #"connection\.sendRequest\(\"([^\"]+)\"" source)))

(defn- experimental-declaration? [source symbol]
  (let [matcher (re-matcher (re-pattern (str "(?m)^export (?:interface|type) "
                                             (java.util.regex.Pattern/quote symbol) "\\b")) source)]
    (when-not (.find matcher)
      (throw (ex-info "Missing generated declaration" {:symbol symbol})))
    (let [prefix (str/trimr (subs source 0 (.start matcher)))
          start (.lastIndexOf prefix "/*")]
      (and (>= start 0) (str/ends-with? prefix "*/")
           (str/includes? (subs prefix start) "@experimental")))))

(deftest historical-evidence-and-library-version-are-preserved
  (let [r (report)
        history (get-in r [:certification :historical-oracle])]
    (is (= (get-in r [:certification :clojure-base-commit]) clojure-base))
    (is (= (:resource history) "resources/stable_upstream_delta_4043044.edn"))
    (is (= (ss/sha256-resource (:resource history))
           (ss/git-file-sha256 clojure-base (str "test/" (:resource history)))
           (:sha256 history)))
    (is (= (:version r) {:sdk "1.0.16.0" :changed? false :release-required? false}))
    (is (= (:unclassified-deltas r) []))
    (is (= (str/trim (local-source ".copilot-schema-version")) "1.0.100-1"))
    (is (str/includes? (local-source ".github/workflows/ci.yml") target))
    (is (str/includes? (local-source "build.clj") "(def version \"1.0.16.0\")"))))

(deftest all-upstream-commits-and-paths-are-classified
  (let [{:keys [upstream changed-paths commit-classifications]} (report)]
    (is (= (:base-commit upstream) base))
    (is (= (:target-commit upstream) target))
    (is (= (:commit-count upstream) (count commit-classifications) 3))
    (is (= (:count changed-paths) (reduce + (vals (:classification-counts changed-paths))) 122))
    (is (= (:rename-policy changed-paths) :no-renames))
    (is (= (:hash-format changed-paths) (:hash-format upstream) :newline-joined-without-trailing-newline))
    (doseq [entry commit-classifications]
      (is (seq (:classifications entry)))
      (is (every? classifications (:classifications entry)))
      (is (not (str/blank? (:reason entry)))))
    (when-let [repo (ss/upstream-repo-or-skip "b96cd2b complete change inventory")]
      (let [commits (ss/git-lines repo "rev-list" "--reverse" (str base ".." target))
            paths (ss/git-lines repo "diff" "--no-renames" "--name-only" base target)
            assigned (map #(ss/classify-path changed-paths %) paths)]
        (is (= commits (mapv :commit commit-classifications)))
        (is (= (ss/sha256-items commits) (:commits-sha256 upstream)))
        (is (= [(count paths) (ss/sha256-items paths)] [(:count changed-paths) (:sha256 changed-paths)]))
        (is (every? classifications assigned))
        (is (= (frequencies assigned) (:classification-counts changed-paths)))
        (is (set/subset? (set (keys (:exact-classifications changed-paths))) (set paths))))
      (doseq [{:keys [commit subject source-url changed-path-count changed-paths-sha256]
               expected :classifications} commit-classifications
              :let [paths (ss/git-lines repo "diff-tree" "--no-commit-id" "--no-renames" "--name-only" "-r" commit)]]
        (is (= (ss/git-output repo "show" "-s" "--format=%s" commit) subject))
        (is (= source-url (str "https://github.com/github/copilot-sdk/commit/" commit)))
        (is (= [(count paths) (ss/sha256-items paths)] [changed-path-count changed-paths-sha256]))
        (is (= (set (map #(ss/classify-path changed-paths %) paths)) expected))))))

(deftest complete-exports-types-methods-and-builders-match-the-pin
  (let [r (report)
        surface (:target-public-surface r)
        authority (set (keys (:source-blobs surface)))]
    (is (= (set (keys (:modules surface))) (disj authority "nodejs/package.json")))
    (is (= (set (keys (:classes surface))) #{:client :session :host}))
    (is (= (get-in surface [:classes :host :classification]) :experimental))
    (is (= (set (keys (:builder-review surface))) #{:create :resume :join}))
    (doseq [[_ module] (:modules surface), kind [:added :removed :changed]]
      (is (every? classifications (keys (get module kind))))
      (is (= (count (classified-symbols (get module kind)))
             (reduce + 0 (map count (vals (get module kind)))))))
    (when-let [repo (ss/upstream-repo-or-skip "b96cd2b complete public surface")]
      (let [source (memoize (fn [pin path] (ss/git-output repo "show" (str pin ":" path))))
            before #(source base %)
            after #(source target %)]
        (is (= (conj (set (ss/git-lines repo "ls-tree" "-r" "--name-only" target "nodejs/src"))
                     "nodejs/package.json") authority))
        (doseq [[path pair] (merge (:source-blobs surface) (:trees surface))
                [pin expected] (map vector [base target] pair)]
          (if expected
            (is (= (ss/git-output repo "rev-parse" (str pin ":" path)) expected) (str pin ":" path))
            (is (= (ss/git-lines repo "ls-tree" "--name-only" pin "--" path) []))))
        (doseq [[path module] (:modules surface)
                :let [old (ss/exported-symbols (before path))
                      current (ss/exported-symbols (after path))]]
          (is (= (fingerprint current) (:fingerprint module)) path)
          (is (= (set/difference current old) (classified-symbols (:added module))) path)
          (is (= (set/difference old current) (classified-symbols (:removed module))) path)
          (is (= (ss/changed-exported-declarations repo base target path)
                 (classified-symbols (:changed module))) path))
        (let [exports #(set/union (ss/exported-symbols (% "nodejs/src/index.ts"))
                                  (ss/exported-symbols (% "nodejs/src/generated/session-events.ts")))
              old (exports before) current (exports after)
              root (:package-root surface)]
          (is (= (fingerprint current) (:fingerprint root)))
          (is (= (count current) 873))
          (is (= (ss/star-export-modules (after "nodejs/src/index.ts"))
                 (:star-exports root) #{"./generated/session-events.js"}))
          (is (= (set/difference current old) (:added root)))
          (is (= (set/difference old current) (:removed root))))
        (doseq [[_ {:keys [path class-name signatures fields added removed]
                    expected :fingerprint}] (:classes surface)
                :let [old (set (ss/public-class-method-signatures (before path) class-name))
                      current (set (ss/public-class-method-signatures (after path) class-name))]]
          (is (= current signatures))
          (is (= (fingerprint current) expected))
          (is (= (set/difference current old) added #{}))
          (is (= (set/difference old current) removed #{}))
          (is (= (class-fields (before path) class-name) (class-fields (after path) class-name) fields)))
        (is (= (interface-deltas (before "nodejs/src/types.ts") (after "nodejs/src/types.ts"))
               (:interface-field-deltas surface)
               {"McpAuthRequest" {:added #{"runtimeRefreshSupported"} :removed #{}}}))
        (is (= (fingerprint (map (fn [[name fields]] (pr-str [name (sort fields)]))
                                 (interface-inventory (after "nodejs/src/types.ts"))))
               (:interfaces-fingerprint surface)))
        (is (= (interface-deltas (before "nodejs/src/generated/session-events.ts")
                                 (after "nodejs/src/generated/session-events.ts"))
               (:event-field-deltas surface)))
        (doseq [[name fields] (:event-field-classifications surface)]
          (is (= (set (keys fields)) (get-in surface [:event-field-deltas name :added])))
          (is (every? classifications (vals fields))))
        (doseq [path (:unchanged-authority-paths surface)]
          (is (= (before path) (after path)) path))
        (is (every? (:unchanged-authority-paths surface)
                    ["nodejs/src/index.ts" "nodejs/src/client.ts" "nodejs/src/extension.ts"
                     "nodejs/src/workflow.ts" "nodejs/src/toolSet.ts"]))
        (let [old (rpc-methods (before "nodejs/src/generated/rpc.ts"))
              current (rpc-methods (after "nodejs/src/generated/rpc.ts"))
              expected (:generated-rpc-methods r)]
          (is (= (fingerprint current) (:fingerprint expected)))
          (is (= (set/difference current old) (set (keys (:added expected)))))
          (is (= (set/difference old current) (:removed expected)))
          (is (= (count (:added expected)) 9)))
        (let [module (get-in surface [:modules "nodejs/src/generated/rpc.ts"])]
          (doseq [kind [:added :changed], [classification symbols] (get module kind), symbol symbols]
            (is (= classification :experimental))
            (is (experimental-declaration? (after "nodejs/src/generated/rpc.ts") symbol) symbol)))
        (let [package (json/read-str (after "nodejs/package.json"))]
          (is (= (get package "copilotCliVersion") "1.0.100-1"))
          (is (= (get package "version") "0.0.0-dev"))
          (is (= (set (keys (get package "exports"))) #{"." "./extension"})))
        (let [{:keys [name commit]} (get-in r [:upstream :release-tag])]
          (is (= (ss/git-output repo "rev-parse" (str "refs/tags/" name "^{commit}")) commit target)))))))

(deftest absent-upstream-tests-are-not-presented-as-target-evidence
  (let [r (report)
        evidence (:retained-tests r)
        history (ss/read-resource (:historical-review-resource evidence))]
    (is (= (:pin evidence) (get-in history [:retained-tests :pin])))
    (is (= (:tree evidence) (get-in history [:retained-tests :tree])))
    (is (true? (:base-test-tree-absent? evidence)))
    (is (true? (:target-test-tree-absent? evidence)))
    (when-let [repo (ss/upstream-repo-or-skip "b96cd2b historical test provenance")]
      (is (= (ss/git-output repo "rev-parse" (str (:pin evidence) ":nodejs/test")) (:tree evidence)))
      (doseq [pin [base target]]
        (is (= (ss/git-lines repo "ls-tree" "--name-only" pin "--" "nodejs/test") []))))))

(deftest contract-matrix-and-schemas-match-the-local-surface
  (let [r (report)
        snapshot (edn/read-string (local-source "resources/github/copilot_sdk/api_surface.edn"))
        old (edn/read-string (ss/git-output "." "show"
                                            (str clojure-base ":resources/github/copilot_sdk/api_surface.edn")))
        old-specs (set (get-in old [:namespaces 'github.copilot-sdk.specs :spec-keys]))
        current-specs (set (get-in snapshot [:namespaces 'github.copilot-sdk.specs :spec-keys]))
        impact (:api-snapshot-impact r)
        schema (json/read-str (local-source "schemas/session-events.schema.json"))
        old-schema (json/read-str (ss/git-output "." "show" (str clojure-base ":schemas/session-events.schema.json")))]
    (is (= (set/difference current-specs old-specs) (:added-spec-keys impact)))
    (is (= (count (:added-spec-keys impact)) 8))
    (is (= (set/difference old-specs current-specs) (:removed-spec-keys impact) #{}))
    (is (= (:vars impact) {}))
    (is (= (:fdefs impact) {'github.copilot-sdk.session
                            {:added '#{github.copilot-sdk.session/handle-mcp-auth-request!}
                             :removed #{} :changed #{}}}))
    (is (= (count (:stable-deltas r)) 5))
    (is (not-any? #(= (:configuration-impact %) :new-option) (:stable-deltas r)))
    (doseq [contract (:stable-deltas r)]
      (is (= (:classification contract) :stable-public))
      (doseq [key [:authority :builders :wire :idiom :semantics :specs :fdefs :tests :proof-states :docs :examples]]
        (is (seq (get contract key)) (str (:id contract) " " key)))
      (doseq [path (:docs contract)]
        (is (seq (local-source path)) path))
      (doseq [spec (:specs contract)]
        (is (contains? current-specs spec) (str spec)))
      (doseq [fdef (:fdefs contract)]
        (is (contains? (get-in snapshot [:namespaces (symbol (namespace fdef)) :fdefs]) fdef) (str fdef))))
    (doseq [entry (concat (:stable-deltas r) (:intentional-exclusions r)), test (:tests entry)
            :let [path (str "test/" (-> (namespace test) (str/replace "." "/") (str/replace "-" "_")) ".clj")
                  declaration (re-pattern (str "(?m)^\\(deftest(?:\\s+\\^:\\S+)?\\s+"
                                               (java.util.regex.Pattern/quote (name test)) "(?:\\s|$)"))]]
      (is (boolean (re-find declaration (local-source path))) (str test)))
    (doseq [entry (:intentional-exclusions r)]
      (is (classifications (:classification entry)))
      (is (keyword? (:decision entry)))
      (is (not (str/blank? (:reason entry)))))
    (is (false? (get-in r [:schema :coercions-changed?])))
    (doseq [[path hash] (get-in r [:schema :artifacts])]
      (is (= (ss/sha256-file path) hash) path))
    (is (= (set/difference (set (keys (get schema "definitions")))
                           (set (keys (get old-schema "definitions"))))
           (get-in r [:schema :added-definitions])))
    (is (= (get-in schema ["definitions" "RequestBodyCompressionDisabledEvent" "visibility"]) "internal"))
    (is (= (get-in schema ["definitions" "McpServerToolsLoadedEvent" "properties" "ephemeral" "const"]) true))
    (is (= (set (get-in schema ["definitions" "McpOauthRecoveryCompletedOutcome" "enum"]))
           #{"succeeded" "cancelled" "failed"}))))
