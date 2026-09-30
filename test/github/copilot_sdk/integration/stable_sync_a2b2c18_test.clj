(ns github.copilot-sdk.integration.stable-sync-a2b2c18-test
  "Exact-pin certification of the complete Node SDK surface at CLI 1.0.90-5."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.integration.stable-sync-support :as ss]))

(def ^:private resource "resources/stable_upstream_delta_a2b2c18.edn")
(def ^:private base "d106d29dc6c5112da2abdae59008571b6692f12b")
(def ^:private target "a2b2c18eb5a20417fc613eaaa93199f55ad22ea4")
(def ^:private clojure-base "56019a78f8fe8ec73ad31eebc041e1690b119769")
(def ^:private classifications
  #{:stable-public :experimental :internal :generated-only :language-specific})
(def ^:private authority-paths
  #{"nodejs/package.json" "nodejs/src/index.ts" "nodejs/src/types.ts"
    "nodejs/src/client.ts" "nodejs/src/session.ts" "nodejs/src/extension.ts"
    "nodejs/src/toolSet.ts" "nodejs/src/canvas.ts" "nodejs/src/workflow.ts"
    "nodejs/src/host.ts" "nodejs/src/installationConfirmation.ts"
    "nodejs/src/generated/session-events.ts" "nodejs/src/generated/rpc.ts"})
(def ^:private additional-specs
  #{:github.copilot-sdk.specs/allowed-models
    :github.copilot-sdk.specs/config-source
    :github.copilot-sdk.specs/error-classification})

(defn- report []
  (or (ss/read-resource resource)
      (throw (ex-info "Missing exact-pin inventory" {:resource resource}))))

(defn- fingerprint [items]
  [(count items) (ss/sha256-lines (sort items))])

(defn- classified-symbols [groups]
  (into #{} cat (vals groups)))

(defn- interface-names [source]
  (into #{} (map second) (re-seq #"(?m)^export interface (\w+)" source)))

(defn- field-signatures [source class-name]
  (let [body (second (re-find (re-pattern (str "(?ms)^export class " class-name
                                               "\\b[^\\{]*\\{(.*?)^\\}"))
                              source))]
    (when-not body
      (throw (ex-info "Missing public class" {:class-name class-name})))
    (into #{}
          (map #(str/trim (or (second %) (nth % 2))))
          (re-seq #"(?m)^    ((?:(?:public|readonly|static)\s+)*[A-Za-z_$][\w$]*[?!]?:[^\n=;]+)(?:[=;])|^        (public readonly [A-Za-z_$][\w$]*[?!]?:[^\n,]+),"
                  body))))

(deftest historical-certification-remains-pinned
  (let [r (report)
        history (get-in r [:certification :historical-oracle])]
    (is (= (get-in r [:certification :clojure-base-commit]) clojure-base))
    (is (= (:resource history) "resources/stable_upstream_delta_d106d29.edn"))
    (is (= (ss/sha256-resource (:resource history))
           (ss/git-file-sha256 clojure-base (str "test/" (:resource history)))
           (:sha256 history)))
    (is (= (:version r) {:sdk "1.0.14.0" :changed? false :release-required? false}))
    (is (= (:method-deltas r)
           [{:class "CopilotClient" :method "startAhpHost"
             :classification :experimental :decision :exclude}]))
    (is (= (:unclassified-deltas r) []))))

(deftest every-commit-path-and-export-delta-is-classified
  (let [{:keys [upstream commit-classifications changed-paths target-public-surface]} (report)]
    (is (= (:base-commit upstream) base))
    (is (= (:target-commit upstream) target))
    (is (= (:commit-count upstream) (count commit-classifications) 9))
    (is (= (:count changed-paths) (reduce + (vals (:classification-counts changed-paths))) 338))
    (is (= (:rename-policy changed-paths) :no-renames))
    (is (= (:hash-format changed-paths) (:hash-format upstream)
           :newline-joined-without-trailing-newline))
    (doseq [entry commit-classifications]
      (is (seq (:classifications entry)))
      (is (every? classifications (:classifications entry)))
      (is (not (str/blank? (:reason entry)))))
    (doseq [[_ module] (:modules target-public-surface), kind [:added :removed :changed]]
      (is (every? classifications (keys (get module kind))))
      (is (= (count (classified-symbols (get module kind)))
             (reduce + 0 (map count (vals (get module kind)))))))
    (when-let [repo (ss/upstream-repo-or-skip "a2b2c18 commit/path inventory")]
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
        (is (set/subset? (into #{} (filter #(str/starts-with? % "nodejs/test/")) paths)
                         (set (keys (:test-inventory (report)))))))
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
    (is (= (get-in surface [:package-root :fingerprint 0]) 800))
    (is (= (count (:interfaces surface)) 105))
    (is (= (set (keys (:classes surface))) #{:client :session :host}))
    (is (= (get-in surface [:classes :host :classification]) :experimental))
    (doseq [[_ entry] (:test-inventory r)]
      (is (some some? (:blobs entry)))
      (is (classifications (:classification entry)))
      (is (not (str/blank? (:review entry)))))
    (when-let [repo (ss/upstream-repo-or-skip "a2b2c18 complete public-surface inventory")]
      (let [source (memoize
                    (fn [pin path]
                      (if (seq (ss/git-lines repo "ls-tree" "--name-only" pin "--" path))
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
              (is (= (ss/git-lines repo "ls-tree" "--name-only" pin "--" path) [])))))
        (doseq [[path module] (:modules surface)
                :let [old (ss/exported-symbols (before path))
                      new (ss/exported-symbols (after path))]]
          (testing path
            (is (= (fingerprint new) (:fingerprint module)))
            (is (= (set/difference new old) (classified-symbols (:added module))))
            (is (= (set/difference old new) (classified-symbols (:removed module))))
            (is (= (if (seq (before path))
                     (ss/changed-exported-declarations repo base target path) #{})
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
        (doseq [[_ {:keys [path class-name signatures fingerprint fields
                           added-signatures removed-signatures]}] (:classes surface)
                :let [old (if (seq (before path))
                            (ss/public-class-method-signatures (before path) class-name) [])
                      new (ss/public-class-method-signatures (after path) class-name)]]
          (is (= new signatures))
          (is (= [(count new) (ss/sha256-lines (sort new))] fingerprint))
          (is (= (set/difference (set new) (set old)) (set added-signatures)))
          (is (= (set/difference (set old) (set new)) (set removed-signatures)))
          (is (= (field-signatures (after path) class-name) fields)))
        (let [old-source (before "nodejs/src/types.ts")
              new-source (after "nodejs/src/types.ts")
              old-names (interface-names old-source) new-names (interface-names new-source)]
          (is (= (set (keys (:interfaces surface))) (set/union old-names new-names)))
          (doseq [[interface expected] (:interfaces surface)
                  :let [old (if (old-names interface) (ss/interface-fields old-source interface) #{})
                        new (if (new-names interface) (ss/interface-fields new-source interface) #{})]]
            (is (= (fingerprint new) (:fingerprint expected)))
            (is (= (set/difference new old) (:added-fields expected)))
            (is (= (set/difference old new) (:removed-fields expected)))))
        (doseq [path (:unchanged-authority-paths surface)]
          (is (= (before path) (after path)) path))
        (doseq [contract (:stable-deltas r), authority (:authority contract)
                :let [[path symbol] (str/split authority #":" 2)]]
          (is (seq (after path)) authority)
          (when symbol
            (is (str/includes? (after path) (last (str/split symbol #"\."))) authority)))
        (let [package (json/read-str (after "nodejs/package.json"))
              release (get-in r [:upstream :release-tag])]
          (is (= (get package "copilotCliVersion") "1.0.90-5"))
          (is (= (get package "version") "0.0.0-dev"))
          (is (= (set (keys (get package "exports"))) #{"." "./extension"}))
          (is (= (ss/git-output repo "rev-parse" (str "refs/tags/" (:name release) "^{commit}"))
                 (:commit release))))))))

(deftest stable-contract-matrix-and-api-snapshot-are-complete
  (let [r (report)
        path "resources/github/copilot_sdk/api_surface.edn"
        old (edn/read-string (ss/shell-output "git" "show" (str clojure-base ":" path)))
        snapshot (edn/read-string (slurp path))
        expected (update-in old [:namespaces 'github.copilot-sdk.specs :spec-keys]
                            #(vec (sort (into (set %) additional-specs))))]
    (is (= snapshot expected))
    (is (= (get-in r [:api-snapshot-impact :added-spec-keys]) additional-specs))
    (is (= (set (map :id (:stable-deltas r)))
           #{:session/allowed-models :events/mcp-failure-metadata
             :events/read-only-permission-result :client/owned-stdio-eof
             :client/pre-eof-delivery :client/uri-reconnect}))
    (doseq [contract (:stable-deltas r)]
      (is (= (:classification contract) :stable-public))
      (doseq [key [:authority :builders :wire :idiom :semantics :specs :fdefs :tests :proof-states :docs]]
        (is (seq (get contract key)) (str (:id contract) " " key)))
      (doseq [fdef (:fdefs contract)]
        (is (contains? (get-in snapshot [:namespaces (symbol (namespace fdef)) :fdefs]) fdef)
            (str fdef)))
      (doseq [spec (:specs contract)]
        (is (contains? (set (get-in snapshot [:namespaces 'github.copilot-sdk.specs :spec-keys])) spec)))
      (doseq [test (:tests contract)
              :let [path (str "test/" (-> (namespace test) (str/replace "." "/")
                                          (str/replace "-" "_")) ".clj")
                    source (slurp path)]]
        (is (str/includes? source (str "(deftest " (name test))) (str test))))
    (is (= (:unclassified-deltas r) []))
    (doseq [exclusion (:intentional-exclusions r)]
      (is (classifications (:classification exclusion)))
      (is (keyword? (:decision exclusion)))
      (is (not (str/blank? (:reason exclusion)))))))
