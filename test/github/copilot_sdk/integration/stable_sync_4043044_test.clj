(ns github.copilot-sdk.integration.stable-sync-4043044-test
  "Complete stable-surface certification for the CLI 1.0.99-2 source snapshot."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.integration.stable-sync-support :as ss]))

(def ^:private resource "resources/stable_upstream_delta_4043044.edn")
(def ^:private report-path (str "test/" resource))
(def ^:private base "6be623b01b194a61466385e3f708639ec98ce8ac")
(def ^:private target "404304416ac5358651e3877a33bc9e8f4e482162")
(def ^:private retained "206b49eee92f37a6d5a69eeba16fa5b123b1ddd0")
(def ^:private clojure-base "f68d853975e823079e7e9d2558157f04eed49664")
(def ^:private implementation "3ca4289a7bcb9dcf6129f22cd140ebfce0662156")
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

(defn- report []
  (or (ss/read-resource resource)
      (throw (ex-info "Missing exact-pin certification" {:resource resource}))))

(defn- local-source [path]
  (ss/git-output "." "show" (str implementation ":" path)))

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

(defn- declaration-classification [source symbol]
  (let [matcher (re-matcher (re-pattern (str "(?m)^export (?:interface|type) "
                                             (java.util.regex.Pattern/quote symbol) "\\b"))
                            source)]
    (when-not (.find matcher)
      (throw (ex-info "Missing generated declaration" {:symbol symbol})))
    (loop [prefix (str/trimr (subs source 0 (.start matcher)))
           annotations #{}]
      (if (str/ends-with? prefix "*/")
        (let [start (.lastIndexOf prefix "/*")
              comment (subs prefix start)]
          (recur (str/trimr (subs prefix 0 start))
                 (cond-> annotations
                   (str/includes? comment "@internal") (conj :internal)
                   (str/includes? comment "@experimental") (conj :experimental))))
        (cond
          (annotations :internal) :internal
          (annotations :experimental) :experimental
          :else :unclassified)))))

(defn- without-local-seal [report]
  (-> report
      (dissoc :sealed-local-artifacts :unchanged-local-artifacts :certification-artifacts)
      (update :certification dissoc :local-artifact-seal)))

(deftest certification-preserves-history-and-is-not-a-release
  (let [r (report)
        history (get-in r [:certification :historical-oracle])]
    (is (= (get-in r [:certification :clojure-base-commit]) clojure-base))
    (is (= (:resource history) "resources/stable_upstream_delta_6be623b.edn"))
    (is (= (ss/sha256-resource (:resource history))
           (ss/git-file-sha256 clojure-base (str "test/" (:resource history)))
           (:sha256 history)))
    (is (= (:version r) {:sdk "1.0.16.0" :changed? false :release-required? false}))
    (is (= (:unclassified-deltas r) []))
    (is (= (get-in r [:certification :local-artifact-seal])
           {:status :sealed :commit implementation}))))

(deftest every-implementation-artifact-is-sealed
  (let [r (report)
        artifacts (:sealed-local-artifacts r)
        unchanged (:unchanged-local-artifacts r)
        changed (set (ss/git-lines "." "diff" "--no-renames" "--name-only" clojure-base implementation))]
    (is (= (ss/git-output "." "merge-base" "--is-ancestor" implementation "HEAD") ""))
    (is (= (:certification-artifacts r) #{report-path}))
    (is (= changed (conj (set/difference (set (keys artifacts)) unchanged) report-path)))
    (is (empty? (set/intersection changed unchanged)))
    (doseq [[path expected] artifacts]
      (is (= (ss/git-file-sha256 implementation path) expected) path))
    (doseq [path unchanged]
      (is (= (ss/git-file-sha256 clojure-base path)
             (ss/git-file-sha256 implementation path)) path))
    (is (contains? artifacts (:decision r)))
    ;; The inventory cannot hash its own final seal. Its pre-seal content is
    ;; committed with the implementation and must otherwise remain identical.
    (is (= (without-local-seal r)
           (without-local-seal (edn/read-string (local-source report-path))))))
  (is (= (local-source ".copilot-schema-version") "1.0.99-2"))
  (is (str/includes? (local-source ".github/workflows/ci.yml") target))
  (is (str/includes? (local-source "build.clj") "(def version \"1.0.16.0\")")))

(deftest every-upstream-commit-and-path-is-classified
  (let [{:keys [upstream changed-paths commit-classifications]} (report)]
    (is (= (:base-commit upstream) base))
    (is (= (:target-commit upstream) target))
    (is (= (:commit-count upstream) (count commit-classifications) 10))
    (is (= (:count changed-paths) (reduce + (vals (:classification-counts changed-paths))) 1667))
    (is (= (:rename-policy changed-paths) :no-renames))
    (is (= (:hash-format changed-paths) (:hash-format upstream) :newline-joined-without-trailing-newline))
    (doseq [entry commit-classifications]
      (is (seq (:classifications entry)))
      (is (every? classifications (:classifications entry)))
      (is (not (str/blank? (:reason entry)))))
    (when-let [repo (ss/upstream-repo-or-skip "4043044 complete change inventory")]
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
               expected-classes :classifications} commit-classifications
              :let [paths (ss/git-lines repo "diff-tree" "--no-commit-id" "--no-renames" "--name-only" "-r" commit)]]
        (is (= (ss/git-output repo "show" "-s" "--format=%s" commit) subject))
        (is (= source-url (str "https://github.com/github/copilot-sdk/commit/" commit)))
        (is (= [(count paths) (ss/sha256-items paths)] [changed-path-count changed-paths-sha256]))
        (is (= (set (map #(ss/classify-path changed-paths %) paths)) expected-classes))))))

(deftest complete-exports-types-classes-and-builders-match-the-pin
  (let [r (report)
        surface (:target-public-surface r)]
    (is (= (set (keys (:source-blobs surface))) authority-paths))
    (is (= (set (keys (:modules surface))) (disj authority-paths "nodejs/package.json")))
    (is (= (set (keys (:classes surface))) #{:client :session :host}))
    (is (= (get-in surface [:classes :host :classification]) :experimental))
    (doseq [[_ module] (:modules surface), kind [:added :removed :changed]]
      (is (every? classifications (keys (get module kind))))
      (is (= (count (classified-symbols (get module kind)))
             (reduce + 0 (map count (vals (get module kind)))))))
    (when-let [repo (ss/upstream-repo-or-skip "4043044 complete public surface")]
      (let [source (memoize (fn [pin path] (ss/git-output repo "show" (str pin ":" path))))
            before #(source base %)
            after #(source target %)]
        (is (= (conj (set (ss/git-lines repo "ls-tree" "-r" "--name-only" target "nodejs/src"))
                     "nodejs/package.json")
               authority-paths))
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
          (is (= (ss/star-export-modules (after "nodejs/src/index.ts"))
                 (:star-exports root) #{"./generated/session-events.js"}))
          (is (= (set/difference current old) (:added root)))
          (is (= (set/difference old current) (:removed root))))
        (doseq [[_ {:keys [path class-name signatures fields added removed]
                    expected-fingerprint :fingerprint}] (:classes surface)
                :let [old (set (ss/public-class-method-signatures (before path) class-name))
                      current (set (ss/public-class-method-signatures (after path) class-name))]]
          (is (= current signatures))
          (is (= (fingerprint current) expected-fingerprint))
          (is (= (set/difference current old) added))
          (is (= (set/difference old current) removed))
          (is (= (class-fields (before path) class-name)
                 (class-fields (after path) class-name) fields)))
        (is (= (interface-deltas (before "nodejs/src/types.ts") (after "nodejs/src/types.ts"))
               (:interface-field-deltas surface)))
        (is (= (fingerprint (map (fn [[name fields]] (pr-str [name (sort fields)]))
                                 (interface-inventory (after "nodejs/src/types.ts"))))
               (:interfaces-fingerprint surface)))
        (is (= (interface-deltas (before "nodejs/src/generated/session-events.ts")
                                 (after "nodejs/src/generated/session-events.ts"))
               (into {} (map (fn [[name entry]] [name (select-keys entry [:added :removed])]))
                     (:event-field-deltas surface))))
        (doseq [path (:unchanged-authority-paths surface)]
          (is (= (before path) (after path)) path))
        (let [old (rpc-methods (before "nodejs/src/generated/rpc.ts"))
              current (rpc-methods (after "nodejs/src/generated/rpc.ts"))
              expected (:generated-rpc-methods r)]
          (is (= (fingerprint current) (:fingerprint expected)))
          (is (= (set/difference current old) (set (keys (:added expected)))))
          (is (= (set/difference old current) (:removed expected))))
        (let [module (get-in surface [:modules "nodejs/src/generated/rpc.ts"])]
          (doseq [kind [:added :changed], [classification symbols] (get module kind), symbol symbols]
            (is (= (declaration-classification (after "nodejs/src/generated/rpc.ts") symbol)
                   classification) symbol))
          (is (= (frequencies (mapcat (fn [kind]
                                        (mapcat (fn [[classification symbols]]
                                                  (repeat (count symbols) classification))
                                                (get module kind)))
                                      [:added :changed]))
                 {:experimental 152 :internal 95})))
        (doseq [{:keys [authority text]} (:semantic-note-deltas r)
                :let [[path _] (str/split authority #":" 2)]]
          (is (str/includes? (after path) text)))
        (let [package (json/read-str (after "nodejs/package.json"))]
          (is (= (get package "copilotCliVersion") "1.0.99-2"))
          (is (= (get package "version") "0.0.0-dev"))
          (is (= (set (keys (get package "exports"))) #{"." "./extension"})))
        (let [{:keys [name commit]} (get-in r [:upstream :release-tag])]
          (is (= (ss/git-output repo "rev-parse" (str "refs/tags/" name "^{commit}")) commit)))))))

(deftest removed-upstream-tests-remain-historical-evidence
  (let [r (report)
        evidence (:retained-tests r)]
    (is (= (:pin evidence) retained))
    (is (= (:target-test-tree-absent? evidence) true))
    (is (= (count (:reviews evidence)) 10))
    (doseq [[_ entry] (:reviews evidence)]
      (is (classifications (:classification entry)))
      (is (not (str/blank? (:review entry)))))
    (when-let [repo (ss/upstream-repo-or-skip "4043044 retained test evidence")]
      (let [source #(ss/git-output repo "show" (str %1 ":" %2))]
        (is (= (ss/git-lines repo "ls-tree" "--name-only" target "--" "nodejs/test") []))
        (is (= (ss/git-output repo "rev-parse" (str retained ":nodejs/test")) (:tree evidence)))
        (is (= (fingerprint (ss/git-lines repo "ls-tree" "-r" "--name-only" retained "nodejs/test"))
               (:path-fingerprint evidence)))
        (doseq [[path {:keys [blob]}] (:reviews evidence)]
          (is (= (ss/git-output repo "rev-parse" (str retained ":" path)) blob)))
        (is (= (:unchanged-hand-authored-authority evidence)
               (into #{} (filter #(and (str/starts-with? % "nodejs/src/")
                                       (not (str/starts-with? % "nodejs/src/generated/"))
                                       (= (source retained %) (source target %))))
                     authority-paths)))
        (is (= (interface-deltas (source retained "nodejs/src/generated/session-events.ts")
                                 (source target "nodejs/src/generated/session-events.ts"))
               {"PermissionRequestedData" {:added #{"fusion"} :removed #{}}
                "ToolShellOutputData" {:added #{"fusion"} :removed #{}}}))))))

(deftest contract-matrix-and-schemas-cover-the-public-change
  (let [r (report)
        snapshot (edn/read-string (local-source "resources/github/copilot_sdk/api_surface.edn"))
        old (edn/read-string (ss/git-output "." "show"
                                            (str clojure-base ":resources/github/copilot_sdk/api_surface.edn")))
        old-specs (set (get-in old [:namespaces 'github.copilot-sdk.specs :spec-keys]))
        current-specs (set (get-in snapshot [:namespaces 'github.copilot-sdk.specs :spec-keys]))
        impact (:api-snapshot-impact r)
        schema (json/read-str (local-source "schemas/session-events.schema.json"))]
    (is (= (set/difference current-specs old-specs) (:added-spec-keys impact)))
    (is (= (count (:added-spec-keys impact)) 19))
    (is (= (set/difference old-specs current-specs) (:removed-spec-keys impact)
           #{:github.copilot-sdk.specs/factory-run-id}))
    (is (= (:vars impact) {'github.copilot-sdk.session
                           {:added #{} :removed #{} :changed '#{handle-permission-request!}}}))
    (is (= (:fdefs impact) {'github.copilot-sdk.session
                            {:added '#{github.copilot-sdk.session/handle-permission-request!}
                             :removed #{} :changed #{}}}))
    (is (= (count (:stable-deltas r)) 15))
    (is (= (into {} (keep (fn [{:keys [configuration-impact idiom]}]
                            (when (= configuration-impact :new-option)
                              [(:key idiom) (:scopes idiom)])))
                 (:stable-deltas r))
           {:name #{:create}
            :agent-directories #{:create :resume :join}
            :hook-directories #{:create :resume :join}
            :interactive-user? #{:create :resume :join}
            :enable-github-cache-store? #{:create :resume :join}}))
    (doseq [contract (:stable-deltas r)]
      (is (= (:classification contract) :stable-public))
      (doseq [key [:authority :builders :wire :idiom :semantics :specs :fdefs :tests :proof-states :docs :examples]]
        (is (seq (get contract key)) (str (:id contract) " " key)))
      (doseq [path (:docs contract)]
        (is (contains? (:sealed-local-artifacts r) path) path))
      (doseq [spec (:specs contract)]
        (is (contains? current-specs spec) (str spec)))
      (doseq [fdef (:fdefs contract)]
        (is (contains? (get-in snapshot [:namespaces (symbol (namespace fdef)) :fdefs]) fdef) (str fdef))))
    (doseq [entry (concat (:stable-deltas r) (:intentional-exclusions r))
            test (:tests entry)
            :let [path (str "test/" (-> (namespace test) (str/replace "." "/") (str/replace "-" "_")) ".clj")
                  declaration (re-pattern (str "(?m)^\\(deftest(?:\\s+\\^:\\S+)?\\s+"
                                               (java.util.regex.Pattern/quote (name test)) "(?:\\s|$)"))]]
      (is (contains? (:sealed-local-artifacts r) path) path)
      (is (boolean (re-find declaration (local-source path))) (str test)))
    (doseq [entry (:intentional-exclusions r)]
      (is (classifications (:classification entry)))
      (is (keyword? (:decision entry)))
      (is (not (str/blank? (:reason entry)))))
    (is (= (get-in r [:schema :coercions-changed?]) false))
    (doseq [[path hash] (get-in r [:schema :artifacts])]
      (is (= (ss/git-file-sha256 implementation path) hash) path))
    (doseq [[name fields] (get-in r [:schema :stable-fields])]
      (is (= (select-keys (get-in schema ["definitions" name "properties"]) (keys fields)) fields))
      (is (empty? (set/intersection (set (keys fields))
                                    (set (get-in schema ["definitions" name "required"]))))))
    (is (= (set (get-in schema ["definitions" "RemediationAction" "enum"]))
           (get-in r [:schema :remediation-values])))))
