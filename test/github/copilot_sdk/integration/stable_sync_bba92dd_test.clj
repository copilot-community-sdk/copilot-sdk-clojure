(ns github.copilot-sdk.integration.stable-sync-bba92dd-test
  "Executable exact-pin certification for the upstream delta through bba92dd."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support :refer [await-value!]]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.security MessageDigest)))

(def ^:private report-resource
  "resources/stable_upstream_delta_bba92dd.edn")

(def ^:private historical-resource
  "resources/stable_upstream_delta_d8bbc9d.edn")

(def ^:private expected-clojure-base
  "f4812923b8972da46422d085cf0910fc1ce59d55")

(def ^:private expected-upstream-base
  "d8bbc9dd7a6167d4806780f405d8ce74add1cc7c")

(def ^:private expected-upstream-target
  "bba92dda4c4c5a34340817112968bd78485df006")

(def ^:private expected-commits
  ["cd8cf15dc3f9e762615790aaed0a771a0f392755"
   "aa07e2973a3c85469b9e7faebfb5ddf6f59538ec"
   "fdef0b13f16096b419f0fd4ac3f1db1bb5dd678a"
   "a6df0741b5cadd8b0c73542c386561372f2830a1"
   "14cef8b79a5513ba7e8674d61ae86814cfacf523"
   "dd0b66575dd7b8fb1392c7ee6b067eb92635a6ad"
   "25d68e41680a357a8417be9f889387165bb0692c"
   "00121299e84ba4dde2567744e4b5210c8ee6a497"
   "1a9a4ca05a1117827aff4edb88055a9447b7bbed"
   "dc10be01270584373f12d9562978b4f9d04d61c5"
   "0d961da797f59ba17fbfdeb29a8bc43f674f3279"
   "806b1b1cc1bdfd618953235be6c4ce1b0d53aced"
   "44d62e0652d57bd6b6325544de507da7acce8f42"
   "12897847f4dc31887c154f3ca62e18e39b25721c"
   "210a4b5febc3a19c0f2083999fe71df0e6404efd"
   "790bfb1108a611a16ea8b6ce9a18b0d636b868a0"
   "0024a45fa897993bd2a54829b604a06622664e5e"
   "9509c5b30653ed63b4ea427450b0fbc397a040f0"
   "cf59e790ac70d85cbba2874f0f9cf89d75fd8d80"
   "314359ef47456b03ed5509fda8dec2ad49ff29f1"
   "c7bcea937c4ade430f24e923af2f867996692127"
   "3dbd843e46771f99070221a85d83c85d8046d0bd"
   "a85ffd7a0757421a5d28bf79cad8482addcc6d7f"
   "dcfbb93859bea38837234a6dd82b64d545f32624"
   "bba92dda4c4c5a34340817112968bd78485df006"])

(def ^:private expected-stable-delta-ids
  #{:auth/client-id-metadata-url
    :client/start-single-flight
    :docs/per-session-plugin-directories
    :docs/resume-websocket-responses
    :events/assistant-usage-billing-model
    :events/custom-agent-disable-model-invocation
    :events/sandbox-permissive-escalation
    :events/subagent-model-sources
    :models/max-output-tokens
    :runtime/schema-1.0.84-4
    :session/auto-tier-fast})

(def ^:private allowed-classifications
  #{:experimental :generated-only :internal :language-specific :stable-public})

(def ^:private upstream-validation-enabled?
  (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION")))

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

(defn- sha256-bytes
  [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn- sha256-file
  [path]
  (sha256-bytes
   (Files/readAllBytes (Paths/get path (make-array String 0)))))

(defn- sha256-resource
  [resource]
  (with-open [stream (io/input-stream (io/resource resource))]
    (sha256-bytes (.readAllBytes stream))))

(defn- sha256-lines
  [lines]
  (sha256-bytes
   (.getBytes (str (str/join "\n" lines) "\n")
              StandardCharsets/UTF_8)))

(defn- declaration-symbols
  [source]
  (into #{}
        (map second)
        (re-seq
         #"(?m)^export\s+(?:(?:declare|abstract)\s+)*(?:type|interface|class|enum|const|(?:async\s+)?function)\s+([A-Za-z_$][A-Za-z0-9_$]*)"
         source)))

(defn- export-list-symbols
  [source]
  (into
   #{}
   (comp
    (map second)
    (map #(str/replace % #"(?s)/\*.*?\*/|//[^\n]*" ""))
    (mapcat #(str/split % #","))
    (map str/trim)
    (remove str/blank?)
    (map #(str/replace % #"^type\s+" ""))
    (map #(last (str/split % #"\s+as\s+")))
    (map str/trim))
   (re-seq #"(?s)export(?:\s+type)?\s*\{(.*?)\}\s*from" source)))

(defn- exported-symbols
  [source]
  (set/union (declaration-symbols source)
             (export-list-symbols source)))

(defn- star-export-modules
  [source]
  (into #{}
        (map second)
        (re-seq
         #"(?m)^export\s+(?:type\s+)?\*\s+from\s+\"([^\"]+)\";"
         source)))

(defn- interface-fields
  [source interface-name]
  (let [pattern
        (re-pattern
         (str "(?ms)^export interface "
              (java.util.regex.Pattern/quote interface-name)
              "\\b[^\\{]*\\{(.*?)^\\}"))
        body (second (re-find pattern source))]
    (when-not body
      (throw (ex-info "Upstream interface not found"
                      {:interface interface-name})))
    (let [indent
          (second
           (re-find
            #"(?m)^([ \t]+)(?:readonly\s+)?[A-Za-z_$][A-Za-z0-9_$]*\??:"
            body))]
      (if-not indent
        #{}
        (into #{}
              (map second)
              (re-seq
               (re-pattern
                (str "(?m)^" (java.util.regex.Pattern/quote indent)
                     "(?:readonly\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\??:"))
               body))))))

(defn- added-interface-fields
  [upstream base target path interface-name]
  (set/difference
   (interface-fields
    (git-output upstream "show" (str target ":" path))
    interface-name)
   (interface-fields
    (git-output upstream "show" (str base ":" path))
    interface-name)))

(def ^:private exported-declaration-pattern
  #"(?m)^\s*export\s+(type|interface)\s+([A-Za-z_$][A-Za-z0-9_$]*)\b")

(defn- strip-typescript-comments
  [source]
  (let [length (count source)]
    (loop [index 0
           quote-char nil
           escaped? false
           comment-mode nil
           result (StringBuilder.)]
      (if (>= index length)
        (str result)
        (let [ch (.charAt source index)
              next-ch (when (< (inc index) length)
                        (.charAt source (inc index)))]
          (cond
            (= comment-mode :line)
            (if (#{\newline \return} ch)
              (recur (inc index) nil false nil (.append result ch))
              (recur (inc index) nil false :line (.append result \space)))

            (= comment-mode :block)
            (cond
              (and (= ch \*) (= next-ch \/))
              (recur (+ index 2) nil false nil
                     (doto result (.append \space) (.append \space)))

              (#{\newline \return} ch)
              (recur (inc index) nil false :block (.append result ch))

              :else
              (recur (inc index) nil false :block (.append result \space)))

            quote-char
            (cond
              escaped?
              (recur (inc index) quote-char false nil (.append result ch))

              (= ch \\)
              (recur (inc index) quote-char true nil (.append result ch))

              (= ch quote-char)
              (recur (inc index) nil false nil (.append result ch))

              :else
              (recur (inc index) quote-char false nil (.append result ch)))

            (#{\" \' \`} ch)
            (recur (inc index) ch false nil (.append result ch))

            (and (= ch \/) (= next-ch \/))
            (recur (+ index 2) nil false :line
                   (doto result (.append \space) (.append \space)))

            (and (= ch \/) (= next-ch \*))
            (recur (+ index 2) nil false :block
                   (doto result (.append \space) (.append \space)))

            :else
            (recur (inc index) nil false nil (.append result ch))))))))

(defn- declaration-end
  [source start kind]
  (loop [index start
         quote-char nil
         escaped? false
         paren-depth 0
         bracket-depth 0
         brace-depth 0
         angle-depth 0
         interface-body? false]
    (when (>= index (count source))
      (throw (ex-info "Unterminated exported TypeScript declaration"
                      {:kind kind :start start})))
    (let [ch (.charAt source index)]
      (cond
        quote-char
        (cond
          escaped?
          (recur (inc index) quote-char false
                 paren-depth bracket-depth brace-depth angle-depth
                 interface-body?)

          (= ch \\)
          (recur (inc index) quote-char true
                 paren-depth bracket-depth brace-depth angle-depth
                 interface-body?)

          (= ch quote-char)
          (recur (inc index) nil false
                 paren-depth bracket-depth brace-depth angle-depth
                 interface-body?)

          :else
          (recur (inc index) quote-char false
                 paren-depth bracket-depth brace-depth angle-depth
                 interface-body?))

        (#{\" \' \`} ch)
        (recur (inc index) ch false
               paren-depth bracket-depth brace-depth angle-depth
               interface-body?)

        (= kind "interface")
        (cond
          (= ch \{)
          (recur (inc index) nil false
                 paren-depth bracket-depth (inc brace-depth) angle-depth true)

          (and interface-body? (= ch \}) (= brace-depth 1))
          (inc index)

          (= ch \})
          (recur (inc index) nil false
                 paren-depth bracket-depth (dec brace-depth) angle-depth
                 interface-body?)

          :else
          (recur (inc index) nil false
                 paren-depth bracket-depth brace-depth angle-depth
                 interface-body?))

        :else
        (case ch
          \( (recur (inc index) nil false
                    (inc paren-depth) bracket-depth brace-depth angle-depth
                    interface-body?)
          \) (recur (inc index) nil false
                    (dec paren-depth) bracket-depth brace-depth angle-depth
                    interface-body?)
          \[ (recur (inc index) nil false
                    paren-depth (inc bracket-depth) brace-depth angle-depth
                    interface-body?)
          \] (recur (inc index) nil false
                    paren-depth (dec bracket-depth) brace-depth angle-depth
                    interface-body?)
          \{ (recur (inc index) nil false
                    paren-depth bracket-depth (inc brace-depth) angle-depth
                    interface-body?)
          \} (recur (inc index) nil false
                    paren-depth bracket-depth (dec brace-depth) angle-depth
                    interface-body?)
          \< (recur (inc index) nil false
                    paren-depth bracket-depth brace-depth (inc angle-depth)
                    interface-body?)
          \> (recur (inc index) nil false
                    paren-depth bracket-depth brace-depth
                    (max 0 (dec angle-depth)) interface-body?)
          \; (if (every? zero?
                         [paren-depth bracket-depth brace-depth angle-depth])
               (inc index)
               (recur (inc index) nil false
                      paren-depth bracket-depth brace-depth angle-depth
                      interface-body?))
          (recur (inc index) nil false
                 paren-depth bracket-depth brace-depth angle-depth
                 interface-body?))))))

(defn- exported-declarations
  [source]
  (let [source (strip-typescript-comments source)
        matcher (re-matcher exported-declaration-pattern source)]
    (loop [declarations {}]
      (if (.find matcher)
        (let [kind (.group matcher 1)
              declaration-name (.group matcher 2)
              start (.start matcher)
              end (declaration-end source (.end matcher) kind)
              normalized
              (-> (subs source start end)
                  (str/replace #"\s+" " ")
                  str/trim)]
          (recur (assoc declarations declaration-name normalized)))
        declarations))))

(defn- changed-exported-declarations
  [upstream base target path]
  (let [base-declarations
        (exported-declarations
         (git-output upstream "show" (str base ":" path)))
        target-declarations
        (exported-declarations
         (git-output upstream "show" (str target ":" path)))]
    (->> (set/intersection (set (keys base-declarations))
                           (set (keys target-declarations)))
         (filter #(not= (get base-declarations %)
                        (get target-declarations %)))
         set)))

(defn- class-source
  [source class-name]
  (let [pattern
        (re-pattern
         (str "(?ms)^export class "
              (java.util.regex.Pattern/quote class-name)
              "\\b.*?\\{(.*?)^\\}"))
        body (second (re-find pattern source))]
    (when-not body
      (throw (ex-info "Expected exported class was not found"
                      {:class-name class-name})))
    body))

(defn- public-class-methods
  [source class-name]
  (->> (re-seq
        #"(?m)^    ((?:(?:private|public|protected|async|static|override|readonly)\s+)*)(?:(?:get|set)\s+)?(\[Symbol\.[A-Za-z_$][A-Za-z0-9_$]*\]|[A-Za-z_$][A-Za-z0-9_$]*)\s*\("
        (class-source source class-name))
       (remove #(re-find #"\b(?:private|protected)\b" (second %)))
       (map #(nth % 2))
       set))

(defn- changed-source-lines
  [upstream base target path]
  (->> (git-lines upstream "diff" "--unified=0" base target "--" path)
       (keep (fn [line]
               (cond
                 (and (str/starts-with? line "+")
                      (not (str/starts-with? line "+++")))
                 (subs line 1)

                 (and (str/starts-with? line "-")
                      (not (str/starts-with? line "---")))
                 (subs line 1)

                 :else nil)))
       vec))

(defn- inventory-items
  [inventory classifications]
  (set
   (concat
    (for [[path by-class] (:added-exported-symbols inventory)
          classification classifications
          symbol (get by-class classification)]
      [:exported-symbol path symbol])
    (when (contains? classifications :stable-public)
      (for [[interface-name fields] (:session-config-fields inventory)
            field fields]
        [:session-config-field interface-name field]))
    (for [[interface-name by-class] (:event-interface-fields inventory)
          classification classifications
          field (get by-class classification)]
      [:event-interface-field interface-name field])
    (for [[path by-class] (:changed-declarations inventory)
          classification classifications
          declaration (get by-class classification)]
      [:changed-declaration path declaration]))))

(defn- generated-rpc-items
  [inventory]
  (let [{:keys [path added changed]} (:generated-rpc inventory)]
    (set
     (concat
      (map #(vector :generated-rpc-added path %) added)
      (map #(vector :generated-rpc-changed path %) changed)))))

(defn- expanded-exclusion-items
  [report]
  (let [rpc-items (generated-rpc-items (:symbol-inventory report))]
    (mapcat
     (fn [{:keys [inventory-items]}]
       (if (= :all-generated-rpc inventory-items)
         rpc-items
         inventory-items))
     (:intentional-exclusions report))))

(defn- referenced-evidence
  [report]
  (set
   (concat
    (mapcat :evidence (:stable-deltas report))
    (mapcat :evidence (:intentional-exclusions report)))))

(deftest report-pins-history-and-local-artifacts
  (let [report (report)
        historical (read-resource historical-resource)]
    (is (some? report) "The bba92dd parity oracle must be committed")
    (is (some? historical) "The prior d8bbc9d oracle must remain available")
    (when (and report historical)
      (is (= expected-clojure-base
             (get-in report [:certification :clojure-base-commit])))
      (is (= expected-upstream-base
             (get-in report [:upstream :base-commit])
             (get-in historical [:upstream :target-commit])))
      (is (= expected-upstream-target
             (get-in report [:upstream :target-commit])))
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
      (is (= "1.0.84-4" (str/trim (slurp ".copilot-schema-version"))))
      (doseq [[path expected-hash] (:local-artifacts report)]
        (testing path
          (is (= expected-hash (sha256-file path))))))))

(deftest exact-upstream-range-is-fully-classified
  (let [report (report)
        {:keys [upstream commit-classifications changed-paths]} report
        entries (:entries changed-paths)
        entry-paths (mapv :path entries)]
    (is (= expected-commits (mapv :commit commit-classifications)))
    (is (= 25 (:commit-count upstream) (count commit-classifications)))
    (is (= expected-stable-delta-ids (:stable-delta-ids report)))
    (is (= expected-stable-delta-ids
           (set (map :id (:stable-deltas report)))))
    (is (every? #(contains? allowed-classifications (:classification %))
                commit-classifications))
    (is (every? #(and (keyword? (:status %))
                      (string? (:reason %))
                      (not (str/blank? (:reason %)))
                      (pos-int? (:changed-path-count %))
                      (re-matches #"[0-9a-f]{64}"
                                  (:changed-paths-sha256 %)))
                commit-classifications))
    (is (= 162 (:count changed-paths) (count entries)))
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
               (sha256-lines actual-commits)))
        (is (= entry-paths actual-paths))
        (is (= (:sha256 changed-paths)
               (sha256-lines actual-paths)))
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
                   (sha256-lines paths)))))))))

(deftest target-public-surface-and-delta-inventory-are-exact
  (let [report (report)
        inventory (:symbol-inventory report)
        stable-items (inventory-items inventory #{:stable-public})
        nonstable-items
        (set/union
         (inventory-items inventory #{:experimental :internal})
         (generated-rpc-items inventory))
        traced-stable-items
        (mapcat :inventory-items (:stable-deltas report))
        traced-exclusion-items (expanded-exclusion-items report)]
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
    (when-let [upstream-repo @upstream-repo]
      (let [base (get-in report [:upstream :base-commit])
            target (get-in report [:upstream :target-commit])
            surface (:target-public-surface report)
            read-source
            (memoize
             (fn [path]
               (git-output upstream-repo "show" (str target ":" path))))
            index-source
            (read-source (get-in surface [:package-root :path]))
            explicit-symbols (exported-symbols index-source)
            event-path
            (get-in surface [:package-root :session-events :path])
            event-symbols (exported-symbols (read-source event-path))
            package-symbols (set/union explicit-symbols event-symbols)
            baseline-report (read-resource historical-resource)
            baseline-counts
            (get-in baseline-report
                    [:target-public-surface
                     :package-root
                     :classification-counts])
            added-classifications
            (get-in inventory [:added-exported-symbols event-path])
            expected-target-counts
            (merge-with +
                        baseline-counts
                        (update-vals added-classifications count))]
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
        (is (= #{"./generated/session-events.js"}
               (star-export-modules index-source)))
        (is (= (get-in surface [:package-root :explicit-symbol-count])
               (count explicit-symbols)))
        (is (= (get-in surface
                       [:package-root :explicit-symbols-sha256])
               (sha256-lines (sort explicit-symbols))))
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
        (is (= (get-in surface
                       [:package-root :classification-counts])
               expected-target-counts))
        (is (= (get-in surface
                       [:package-root :provenance
                        :baseline-symbol-count])
               (get-in baseline-report
                       [:target-public-surface :package-root
                        :symbol-count])))
        (doseq [surface-key [:types :extension :tool-set]
                :let [{:keys [path symbol-count symbols-sha256]}
                      (get surface surface-key)
                      symbols (exported-symbols (read-source path))]]
          (testing (name surface-key)
            (is (= symbol-count (count symbols)))
            (is (= symbols-sha256
                   (sha256-lines (sort symbols))))))
        (doseq [[_ {:keys [path class-name method-count methods-sha256]}]
                (:classes surface)
                :let [methods
                      (public-class-methods
                       (read-source path) class-name)]]
          (testing class-name
            (is (= method-count (count methods)))
            (is (= methods-sha256
                   (sha256-lines (sort methods))))))
        (doseq [[path classifications]
                (:added-exported-symbols inventory)]
          (let [expected (apply set/union #{} (vals classifications))
                actual
                (set/difference
                 (exported-symbols
                  (git-output upstream-repo "show"
                              (str target ":" path)))
                 (exported-symbols
                  (git-output upstream-repo "show"
                              (str base ":" path))))]
            (is (= expected actual)
                (str "added exported symbols drifted for " path))))
        (doseq [[interface-name expected]
                (:session-config-fields inventory)]
          (is (= expected
                 (added-interface-fields
                  upstream-repo base target
                  "nodejs/src/types.ts" interface-name))
              (str "session config fields drifted for " interface-name)))
        (doseq [[interface-name classifications]
                (:event-interface-fields inventory)]
          (is (= (apply set/union #{} (vals classifications))
                 (added-interface-fields
                  upstream-repo base target event-path interface-name))
              (str "event fields drifted for " interface-name)))
        (doseq [[path classifications]
                (:changed-declarations inventory)]
          (is (= (apply set/union #{} (vals classifications))
                 (changed-exported-declarations
                  upstream-repo base target path))
              (str "changed declarations drifted for " path)))
        (let [{:keys [path added changed]} (:generated-rpc inventory)]
          (is (= added
                 (set/difference
                  (exported-symbols
                   (git-output upstream-repo "show"
                               (str target ":" path)))
                  (exported-symbols
                   (git-output upstream-repo "show"
                               (str base ":" path))))))
          (is (= changed
                 (changed-exported-declarations
                  upstream-repo base target path))))
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
                (let [source (read-source path)]
                  (is (str/includes? source symbol))
                  (is (some #(str/includes? % symbol)
                            (get changed-lines-by-path path))
                      "the evidence symbol must occur on a changed line"))))))))))

(deftest schema-and-exclusion-boundaries-are-executable
  (let [schema
        (json/read-str (slurp "schemas/session-events.schema.json"))
        definitions (get schema "definitions")]
    (is (= ["efficiency" "balance" "intelligence" "fast"]
           (get-in definitions ["AutoTier" "enum"])))
    (is (= "experimental"
           (get-in definitions
                   ["AutoTierRecommendationEvent" "stability"])))
    (is (= "experimental"
           (get-in definitions ["PermissionsChangedData" "stability"])))
    (is (= "internal"
           (get-in definitions
                   ["CompactionCompleteData"
                    "properties"
                    "activeFactorySummary"
                    "visibility"])))
    (is (= "internal"
           (get-in definitions
                   ["CompactionCompleteCompactionTokensUsedCopilotUsage"
                    "properties"
                    "model"
                    "visibility"]))))
  (is (not (contains? sdk/event-types
                      "session.auto_tier_recommendation")))
  (is (nil? (s/get-spec ::specs/recommended-auto-tier)))
  (is (nil? (s/get-spec ::specs/active-factory-summary)))
  (is (nil? (s/get-spec ::specs/pause-info)))
  (is (not (s/valid? ::specs/factory-run-status :paused)))
  (is (not (s/valid? ::specs/session.permissions_changed-data {})))
  (is (nil? (ns-resolve 'github.copilot-sdk.client
                        'managed-settings-clear-cache!)))
  (is (s/valid? ::generated-events/auto-tier "fast"))
  (is (s/valid?
       ::generated-events/session.auto_tier_recommendation-data
       {:recommended-auto-tier "balance"}))
  (is (s/valid?
       ::generated-events/session.permissions_changed-data
       {}))
  (is (s/valid?
       ::generated-events/session.compaction_complete-data
       {:success true :active-factory-summary "factory still active"}))
  (is (s/valid?
       ::generated-events/factory.run_settled-data
       {:consumed-nano-aiu 0
        :consumed-subagents 0
        :elapsed-ms 0
        :run-id "run-1"
        :status "paused"})))

(deftest concurrent-startup-is-single-flight
  (doseq [[label expected]
          [[:success nil]
           [:failure (ex-info "shared startup failure" {:phase :start})]]]
    (testing (name label)
      (let [c (sdk/client {:auto-start? false})
            owner-call-count (atom 0)
            owner-entered (promise)
            release-owner (promise)
            second-claim-observed (promise)
            claim-count (atom 0)
            real-claim (var-get (var client/claim-client-start!))
            run-start (var-get (var client/run-client-start!))
            owner
            (fn [_]
              (swap! owner-call-count inc)
              (deliver owner-entered true)
              @release-owner
              (if (instance? Throwable expected)
                (throw expected)
                expected))
            start
            (fn []
              (future
                (try
                  (run-start c false owner)
                  (catch Throwable failure
                    failure))))]
        (try
          (with-redefs-fn
            {(var client/claim-client-start!)
             (fn [startup-client caller-supplied-streams?]
               (let [claim
                     (real-claim
                      startup-client caller-supplied-streams?)]
                 (when (= 2 (swap! claim-count inc))
                   (deliver second-claim-observed true))
                 claim))}
            #(let [first-start (start)]
               (await-value! owner-entered "startup owner" 1000)
               (let [second-start (start)]
                 (await-value! second-claim-observed
                               "startup waiter" 1000)
                 (deliver release-owner true)
                 (is (identical?
                      expected
                      (await-value! first-start
                                    "first startup result" 1000)))
                 (is (identical?
                      expected
                      (await-value! second-start
                                    "second startup result" 1000)))
                 (is (= 1 @owner-call-count)))))
          (finally
            (deliver release-owner true)
            (swap! (:state c)
                   assoc
                   :status :disconnected
                   :connection-start-token nil
                   :connection-start-completion nil)))))))

(deftest auth-client-id-metadata-url-preserves-omission-and-join
  (let [metadata-url "https://example.test/oauth/client-metadata.json"
        build-create (var-get (var client/build-create-session-params))
        build-resume (var-get (var client/build-resume-session-params))
        create-params
        (util/clj->wire
         (build-create {:auth-client-id-metadata-url metadata-url}))
        create-omitted (util/clj->wire (build-create {}))
        resume-params
        (util/clj->wire
         (build-resume
          "session-1"
          {:auth-client-id-metadata-url metadata-url}))
        resume-omitted
        (util/clj->wire (build-resume "session-1" {}))]
    (is (= metadata-url (:authClientIdMetadataUrl create-params)))
    (is (not (contains? create-omitted :authClientIdMetadataUrl)))
    (is (= metadata-url (:authClientIdMetadataUrl resume-params)))
    (is (not (contains? resume-omitted :authClientIdMetadataUrl)))
    (doseq [spec [::specs/session-config
                  ::specs/resume-session-config
                  ::specs/join-session-config]]
      (is (s/valid? spec {:auth-client-id-metadata-url metadata-url}))
      (is (s/valid? spec {}))
      (is (not (s/valid?
                spec {:auth-client-id-metadata-url nil}))))
    (let [c (sdk/client {:auto-start? false})
          joined-session
          (session/map->CopilotSession
           {:session-id "session-1"
            :client c
            :registration-token (Object.)})
          calls (atom [])]
      (with-redefs-fn
        {(var client/foreground-session-id) (constantly "session-1")
         (var client/client) (constantly c)
         (var client/resume-session-result*)
         (fn [joined-client session-id config]
           (swap! calls conj [joined-client session-id config])
           {:session joined-session :result {}})}
        #(do
           (client/join-session {})
           (client/join-session
            {:auth-client-id-metadata-url metadata-url})))
      (is (= 2 (count @calls)))
      (is (not (contains? (nth (first @calls) 2)
                          :auth-client-id-metadata-url)))
      (is (= metadata-url
             (get (nth (second @calls) 2)
                  :auth-client-id-metadata-url))))))

(deftest model-capability-and-fast-auto-tier-contracts
  (let [capabilities
        {:supports {:vision true}
         :limits {:max-output-tokens 8192}}
        parsed
        ((var-get (var client/parse-model-info))
         {:id "model-1"
          :name "Model 1"
          :capabilities capabilities})
        create-wire
        (util/clj->wire
         ((var-get (var client/build-create-session-params))
          {:capi {:auto-tier :fast}}))]
    (is (s/valid? ::specs/model-capabilities capabilities))
    (is (not (s/valid?
              ::specs/model-capabilities
              {:limits {:max-output-tokens "8192"}})))
    (is (= {"max_output_tokens" 8192}
           (:limits (util/model-capabilities->wire capabilities))))
    (is (= 8192
           (get-in parsed
                   [:model-capabilities :limits :max-output-tokens])))
    (is (s/valid? ::specs/auto-tier :fast))
    (is (= :fast (get-in create-wire [:capi :autoTier])))
    (is (str/includes?
         (json/write-str create-wire)
         "\"autoTier\":\"fast\""))))

(deftest stable-generated-event-fields-have-curated-idiom-contracts
  (let [subagent-started
        {:tool-call-id "call-1"
         :agent-name "research"
         :agent-display-name "Research"
         :agent-description "Research task"
         :task-model-source "task_argument"}
        subagent-completed
        {:tool-call-id "call-1"
         :agent-name "research"
         :agent-display-name "Research"
         :model-selection-source "runtime_policy"}
        subagent-failed
        {:tool-call-id "call-1"
         :agent-name "research"
         :agent-display-name "Research"
         :error "failed"
         :model-selection-source "configured_required"}
        custom-agent
        {:id "research"
         :name "research"
         :display-name "Research"
         :description "Research task"
         :source "project"
         :user-invocable? true
         :tools []
         :disable-model-invocation true}
        permission
        {:permission-kind :shell
         :request-sandbox-bypass true
         :request-sandbox-bypass-reason "blocked by sandbox"
         :request-sandbox-permissive true}
        assistant-usage
        {:model "gpt-5"
         :copilot-usage
         {:total-nano-aiu 2
          :model "gpt-5"
          :token-details
          [{:batch-size 1
            :cost-per-batch 1
            :token-count 2
            :token-type "input"
            :model "gpt-5-mini"}]}}]
    (is (s/valid? ::specs/subagent.started-data subagent-started))
    (is (s/valid? ::specs/subagent.completed-data subagent-completed))
    (is (s/valid? ::specs/subagent.failed-data subagent-failed))
    (is (s/valid? ::specs/custom-agent-info custom-agent))
    (is (s/valid? ::specs/permission-request permission))
    (is (s/valid? ::specs/assistant.usage-data assistant-usage))
    (is (s/valid?
         ::generated-events/subagent.started-data
         subagent-started))
    (is (s/valid?
         ::generated-events/subagent.completed-data
         subagent-completed))
    (is (s/valid?
         ::generated-events/subagent.failed-data
         subagent-failed))
    (is (s/valid?
         ::generated-events/custom-agents-updated-agent-shape
         (-> custom-agent
             (dissoc :user-invocable?)
             (assoc :user-invocable true))))
    (is (s/valid?
         ::generated-events/permission-request-shell-shape
         {:can-offer-session-approval true
          :commands []
          :full-command-text "echo hello"
          :has-write-file-redirection false
          :intention "test"
          :kind "shell"
          :possible-paths []
          :possible-urls []
          :request-sandbox-bypass true
          :request-sandbox-bypass-reason "blocked by sandbox"
          :request-sandbox-permissive true}))
    (is (s/valid?
         ::generated-events/permission-prompt-request-commands-shape
         {:can-offer-session-approval true
          :command-identifiers ["echo"]
          :full-command-text "echo hello"
          :intention "test"
          :kind "commands"
          :request-sandbox-bypass true
          :request-sandbox-bypass-reason "blocked by sandbox"
          :request-sandbox-permissive true}))
    (is (s/valid?
         ::generated-events/assistant-usage-copilot-usage-shape
         (:copilot-usage assistant-usage)))))
