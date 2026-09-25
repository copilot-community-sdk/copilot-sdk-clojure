(ns github.copilot-sdk.integration.stable-sync-4001c1d-test
  "Exact-pin public-surface and implementation certification."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.integration.stable-sync-support :as ss]))

(def ^:private resource "resources/stable_upstream_delta_4001c1d.edn")
(def ^:private base "cb6fc666cc45175adb11fa9e5021b96d7d37d298")
(def ^:private target "4001c1da7d832c51bad1d38619c1a082af390efb")
(def ^:private clojure-base "ca911ba62f746a1e32c63c64f378d1e041d5bd4f")
(def ^:private implementation "648cb6c6624f61648ea8634d459aa19eb7c90421")
(def ^:private classifications
  #{:stable-public :experimental :internal :generated-only :language-specific})
(def ^:private commits
  ["075f027363fc3b1e904d09370763731c3ecd2d88"
   "4cd7a043e020493b77bd6f531f130f4235f37f64"
   "4001c1da7d832c51bad1d38619c1a082af390efb"])
(def ^:private authority-paths
  #{"nodejs/package.json" "nodejs/src/index.ts" "nodejs/src/types.ts"
    "nodejs/src/client.ts" "nodejs/src/session.ts" "nodejs/src/extension.ts"
    "nodejs/src/toolSet.ts" "nodejs/src/factory.ts" "nodejs/src/canvas.ts"
    "nodejs/src/workflow.ts" "nodejs/src/generated/session-events.ts"
    "nodejs/src/generated/rpc.ts"})
(def ^:private added-spec-keys
  #{:github.copilot-sdk.specs/refresh-custom-instructions?
    :github.copilot-sdk.specs/mcp-auth-static-client-config
    :github.copilot-sdk.specs/mcp.oauth_required-data
    :github.copilot-sdk.specs/event-ids
    :github.copilot-sdk.specs/session.snapshot_rewind-data})

(defn- report []
  (or (ss/read-resource resource)
      (throw (ex-info "Missing exact-pin inventory" {:resource resource}))))

(defn- local-source [commit path]
  (ss/shell-output "git" "show" (str commit ":" path)))

(defn- fingerprint [items]
  [(count items) (ss/sha256-lines (sort items))])

(defn- group-symbols [r groups]
  (into #{} (mapcat #(get-in r [:symbol-groups % :symbols])) groups))

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
        artifacts (:sealed-local-artifacts r)]
    (is (= (get-in r [:certification :clojure-base-commit]) clojure-base))
    (is (= history
           {:resource "resources/stable_upstream_delta_cb6fc66.edn"
            :sha256 "42ba15c806fa0d6b4575eed6cf961ea4dcca448796268d895b75c26c1cab66c6"}))
    (is (= (ss/sha256-resource (:resource history)) (:sha256 history)))
    (is (= (ss/git-file-sha256 clojure-base (str "test/" (:resource history)))
           (:sha256 history)))
    (is (= (get-in r [:certification :local-artifact-seal])
           {:status :sealed :commit implementation}))
    (is (re-matches #"[0-9a-f]{40}" implementation))
    (is (= (ss/shell-output "git" "merge-base" "--is-ancestor" implementation "HEAD") ""))
    (let [changed (set (ss/git-lines "." "diff" "--name-only" clojure-base implementation))]
      ;; Certificate files cannot participate in their own implementation seal.
      (is (set/subset? (disj changed
                             (str "test/" resource)
                             "test/github/copilot_sdk/integration/stable_sync_4001c1d_test.clj")
                       (set (keys artifacts)))))
    (is (contains? artifacts "test/github/copilot_sdk/optional_wire_contract_test.clj"))
    (doseq [[path expected] artifacts]
      (testing path
        (is (re-matches #"[0-9a-f]{64}" expected))
        (is (= (ss/git-file-sha256 implementation path) expected))))
    (is (= (:version r) {:sdk "1.0.14.0" :changed? false :release-required? false}))
    (is (str/includes? (local-source implementation "build.clj") "(def version \"1.0.14.0\")"))
    (is (= (local-source implementation ".copilot-schema-version") "1.0.89-3"))
    (is (str/includes? (local-source implementation ".github/workflows/ci.yml") target))))

(deftest every-commit-and-path-is-classified
  (let [{:keys [upstream commit-classifications changed-paths]} (report)]
    (is (= (:base-commit upstream) base))
    (is (= (:target-commit upstream) target))
    (is (= (:commit-count upstream) (count commits) 3))
    (is (= (mapv :commit commit-classifications) commits))
    (is (= (mapv :classification commit-classifications)
           [:experimental :stable-public :language-specific]))
    (is (= (:count changed-paths) 548 (reduce + (vals (:classification-counts changed-paths)))))
    (is (= (:hash-format upstream) (:hash-format changed-paths)
           :newline-joined-without-trailing-newline))
    (doseq [row (concat commit-classifications (:prefix-classifications changed-paths))]
      (is (classifications (:classification row)))
      (is (not (str/blank? (:reason row)))))
    (when-let [repo (ss/upstream-repo-or-skip "4001c1d changed-path certification")]
      (let [actual-commits (ss/git-lines repo "rev-list" "--reverse" (str base ".." target))
            paths (ss/git-lines repo "diff" "--name-only" base target)
            assigned (map #(ss/classify-path changed-paths %) paths)]
        (is (= actual-commits commits))
        (is (= (ss/sha256-items actual-commits) (:commits-sha256 upstream)))
        (is (= [(count paths) (ss/sha256-items paths)]
               [(:count changed-paths) (:sha256 changed-paths)]))
        (is (every? classifications assigned))
        (is (= (frequencies assigned) (:classification-counts changed-paths)))
        (is (set/subset? (set (keys (:exact-classifications changed-paths))) (set paths))))
      (doseq [{:keys [commit subject source-url changed-path-count changed-paths-sha256]}
              commit-classifications
              :let [paths (ss/git-lines repo "diff-tree" "--no-commit-id" "--name-only" "-r" commit)]]
        (is (= (ss/git-output repo "show" "-s" "--format=%s" commit) subject))
        (is (= source-url (str "https://github.com/github/copilot-sdk/commit/" commit)))
        (is (= [(count paths) (ss/sha256-items paths)]
               [changed-path-count changed-paths-sha256]))))))

(deftest contract-matrix-covers-specs-fdefs-tests-and-docs
  (let [r (report)
        deltas (:stable-deltas r)
        snapshot-path "resources/github/copilot_sdk/api_surface.edn"
        old (edn/read-string (local-source clojure-base snapshot-path))
        new (edn/read-string (local-source implementation snapshot-path))
        spec-path [:namespaces 'github.copilot-sdk.specs :spec-keys]
        spec-keys (set (get-in new spec-path))]
    (is (= (:unclassified-deltas r) []))
    (is (= (set (map :id deltas))
           #{:session/instruction-cache-refresh :session/root-completion
             :events/oauth-static-scope :events/selective-rewind}))
    (is (= (:api-snapshot-impact r)
           {:added-spec-keys added-spec-keys :function-changes #{} :fdef-changes #{}}))
    (is (= (set/difference spec-keys (set (get-in old spec-path))) added-spec-keys))
    (is (= (update-in new spec-path #(into [] (remove added-spec-keys) %)) old))
    (doseq [delta deltas]
      (is (= (:classification delta) :stable-public))
      (doseq [field [:authority :builders :wire :idiom :specs :fdefs :proof-states :tests :docs]]
        (is (seq (get delta field)) (str (:id delta) " " field)))
      (is (not (str/blank? (:semantics delta))))
      (is (set/subset? (set (:specs delta)) spec-keys))
      (doseq [fdef (:fdefs delta)]
        (is (some? (get-in new [:namespaces (symbol (namespace fdef)) :fdefs fdef]))
            (str "Missing registered fdef " fdef)))
      (doseq [test-symbol (:tests delta)
              :let [path (str "test/" (-> (namespace test-symbol)
                                          (str/replace "." "/")
                                          (str/replace "-" "_")) ".clj")]]
        (is (contains? (:sealed-local-artifacts r) path))
        (is (recorded-test? (local-source implementation path) test-symbol)
            (str "Missing contract proof " test-symbol)))
      (doseq [path (:docs delta)]
        (is (contains? (:sealed-local-artifacts r) path))))
    (let [refresh (first (filter #(= (:id %) :session/instruction-cache-refresh) deltas))]
      (is (= (:builders refresh) [:create-session :<create-session]))
      (is (= (:excluded-builders refresh)
             [:resume-session :<resume-session :join-session :session.options.update]))
      (is (= (get-in refresh [:wire :key]) "refreshCustomInstructions"))
      (is (= (get-in refresh [:idiom :key]) :refresh-custom-instructions?)))))

(deftest complete-public-surface-and-stable-tests-match-the-target
  (let [r (report)
        surface (:target-public-surface r)
        modules (:modules surface)]
    (is (= (set (keys (:source-blobs surface))) authority-paths))
    (is (= (set (keys modules)) (disj authority-paths "nodejs/package.json")))
    (is (= (set (keys (:trees surface))) #{"nodejs/src" "nodejs/src/generated" "nodejs/test"}))
    (is (= (get-in surface [:package-root :fingerprint 0]) 812))
    (is (= (:removed-exports surface) #{}))
    (is (= (count (:test-inventory r)) 21))
    (doseq [[_ entry] (:test-inventory r)]
      (is (not (str/blank? (:review entry)))))
    (when-let [repo (ss/upstream-repo-or-skip "4001c1d complete public-surface certification")]
      (let [source (memoize #(ss/git-output repo "show" (str %1 ":" %2)))
            before #(source base %)
            after #(source target %)]
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
            (is (= (ss/changed-exported-declarations repo base target path)
                   (into #{} cat (vals (get-in r [:changed-declarations path])))))))
        (let [index-path "nodejs/src/index.ts"
              event-path "nodejs/src/generated/session-events.ts"
              old (set/union (ss/exported-symbols (before index-path))
                             (ss/exported-symbols (before event-path)))
              new (set/union (ss/exported-symbols (after index-path))
                             (ss/exported-symbols (after event-path)))]
          (is (= (ss/star-export-modules (after index-path))
                 (get-in surface [:package-root :star-exports])
                 #{"./generated/session-events.js"}))
          (is (= (fingerprint new) (get-in surface [:package-root :fingerprint])))
          (is (= (set/difference new old) (group-symbols r [:connectors])))
          (is (= (set/difference old new) #{})))
        (doseq [[_ {:keys [path class-name signatures signature-inventory
                           added-signatures removed-signatures fields]}] (:classes surface)
                :let [old (ss/public-class-method-signatures (before path) class-name)
                      new (ss/public-class-method-signatures (after path) class-name)]]
          (is (= (fingerprint new) signatures))
          (is (= new old signature-inventory))
          (is (= added-signatures {}))
          (is (= removed-signatures #{}))
          (is (= [(field-signatures (before path) class-name)
                  (field-signatures (after path) class-name)]
                 fields)))
        (doseq [[interface expected] (:interfaces surface)
                :let [old (ss/interface-fields (before "nodejs/src/types.ts") interface)
                      new (ss/interface-fields (after "nodejs/src/types.ts") interface)]]
          (is (= (fingerprint new) (:fingerprint expected)))
          (is (= (set/difference new old) (:added-fields expected)))
          (is (= (set/difference old new) (:removed-fields expected))))
        (doseq [path (:unchanged-authority-paths surface)]
          (is (= (before path) (after path)) path))
        (doseq [[path markers] (:source-evidence r), marker markers]
          (is (str/includes? (after path) marker) (str path ": " marker)))
        (let [package (json/read-str (after "nodejs/package.json"))
              release (get-in r [:upstream :release-tag])]
          (is (= (get package "copilotCliVersion") "1.0.89-3"))
          (is (= (get package "version") "0.0.0-dev"))
          (is (= (set (keys (get package "exports"))) #{"." "./extension"}))
          (is (= (ss/git-output repo "rev-parse" (str "refs/tags/" (:name release) "^{commit}"))
                 (:commit release))))))))

(deftest experimental-aliases-and-internal-schema-events-stay-classified
  (let [r (report)
        exclusions (into {} (map (juxt :id identity)) (:exclusions r))
        event-defs (get (json/read-str (local-source implementation "schemas/session-events.schema.json"))
                        "definitions")
        old-api (get (json/read-str (local-source clojure-base "schemas/api.schema.json")) "definitions")
        new-api (get (json/read-str (local-source implementation "schemas/api.schema.json")) "definitions")]
    (is (= (set (keys exclusions))
           #{:diagnostics :connectors :fusion :generated-rpc :fusion-checkpoint :reasoning-prose}))
    (doseq [[_ exclusion] exclusions]
      (is (classifications (:classification exclusion)))
      (is (not (str/blank? (:reason exclusion))))
      (is (some? (:decision exclusion))))
    (doseq [id [:diagnostics :connectors :fusion]]
      (is (= (:classification (get exclusions id)) :experimental))
      (is (= (:decision (get exclusions id)) :exclude)))
    (is (= (count (group-symbols r [:connectors])) 14))
    (is (= (count (group-symbols r [:diagnostics])) 14))
    (is (= (set/difference (set (keys new-api)) (set (keys old-api)))
           (group-symbols r [:diagnostics :sandbox :queue])))
    (is (= (set/difference (set (keys old-api)) (set (keys new-api))) #{}))
    (doseq [definition ["FusionChangeCheckpointData" "FusionChangeCheckpointEvent"]]
      (is (= (get-in event-defs [definition "visibility"]) "internal")))
    (when-let [repo (ss/upstream-repo-or-skip "4001c1d transitive experimental classification")]
      (let [types (ss/git-output repo "show" (str target ":nodejs/src/types.ts"))
            client (ss/git-output repo "show" (str target ":nodejs/src/client.ts"))
            rpc (ss/git-output repo "show" (str target ":nodejs/src/generated/rpc.ts"))
            events (ss/git-output repo "show" (str target ":nodejs/src/generated/session-events.ts"))]
        (doseq [name (group-symbols r [:diagnostics :connectors])]
          (is (boolean
               (re-find
                (re-pattern (str "/\\*\\* @experimental \\*/\\s+export (?:type|interface) "
                                 (java.util.regex.Pattern/quote name) "\\b"))
                rpc))
              name))
        (is (str/includes? types "diagnostics?: DiagnosticsConfiguration;"))
        (is (= (count (re-seq #"config\.diagnostics !== undefined" client)) 2))
        (is (= (count (re-seq #"refreshCustomInstructions: config\.refreshCustomInstructions" client)) 1))
        (is (not (str/includes? types "oauthScopes")))
        (is (= (set/intersection #{"FusionChangeCheckpointData" "FusionChangeCheckpointEvent"}
                                 (ss/exported-symbols events))
               #{}))))))
