(ns github.copilot-sdk.integration.stable-sync-support
  "Shared helpers for exact-pin upstream certification tests."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files Paths)
           (java.security MessageDigest)))

(def upstream-validation-enabled?
  (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION")))

(defn read-resource
  [resource]
  (some-> resource io/resource slurp edn/read-string))

(defn- resolve-upstream
  []
  (let [{:keys [exit out err]}
        (sh/sh "bash"
               ".github/skills/update-upstream/scripts/resolve-upstream.sh")]
    (when-not (zero? exit)
      (throw (ex-info "Could not resolve the upstream checkout"
                      {:exit exit :stderr err})))
    (str/trim out)))

(def upstream-repo
  (delay
    (when upstream-validation-enabled?
      (resolve-upstream))))

(defn upstream-repo-or-skip
  [scope]
  (if-let [upstream @upstream-repo]
    upstream
    (do
      (println
       (format
        "SKIP external %s: set COPILOT_UPSTREAM_VALIDATION=true; committed oracle checks still ran"
        scope))
      nil)))

(defn shell-output
  [& args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (when-not (zero? exit)
      (throw (ex-info "Command failed"
                      {:args args :exit exit :stderr err})))
    (str/trim out)))

(defn git-output
  [upstream & args]
  (apply shell-output "git" "-C" upstream args))

(defn git-lines
  [upstream & args]
  (->> (str/split-lines (apply git-output upstream args))
       (remove str/blank?)
       vec))

(defn sha256-bytes
  [bytes]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn sha256-file
  [path]
  (sha256-bytes
   (Files/readAllBytes (Paths/get path (make-array String 0)))))

(defn sha256-resource
  [resource]
  (with-open [stream (io/input-stream (io/resource resource))]
    (sha256-bytes (.readAllBytes stream))))

(defn sha256-lines
  [lines]
  (sha256-bytes
   (.getBytes (str (str/join "\n" lines) "\n")
              StandardCharsets/UTF_8)))

(defn sha256-items
  [items]
  (sha256-bytes
   (.getBytes (str/join "\n" items)
              StandardCharsets/UTF_8)))

(defn git-file-sha256
  [commit path]
  (let [{:keys [exit out err]} (sh/sh "git" "show" (str commit ":" path))]
    (when-not (zero? exit)
      (throw (ex-info "Could not read historical artifact"
                      {:commit commit :path path :exit exit :stderr err})))
    (sha256-bytes (.getBytes out StandardCharsets/UTF_8))))

(defn classify-path
  [{:keys [exact-classifications
           language-specific-prefixes
           prefix-classifications]}
   path]
  (or (get exact-classifications path)
      (some (fn [{:keys [prefix classification]}]
              (when (str/starts-with? path prefix)
                classification))
            prefix-classifications)
      (when (some #(str/starts-with? path %) language-specific-prefixes)
        :language-specific)))

(declare strip-typescript-comments)

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
    (mapcat #(str/split % #","))
    (map str/trim)
    (remove str/blank?)
    (map #(str/replace % #"^type\s+" ""))
    (map #(last (str/split % #"\s+as\s+")))
    (map str/trim)
    (map
     (fn [symbol]
       (when-not (re-matches #"[A-Za-z_$][A-Za-z0-9_$]*" symbol)
         (throw (ex-info "Parsed invalid TypeScript export symbol"
                         {:symbol symbol})))
       symbol)))
   (re-seq
    #"(?s)export(?:\s+type)?\s*\{(.*?)\}\s*(?:from\s+[\"'][^\"']+[\"'])?\s*;"
    source)))

(defn exported-symbols
  [source]
  (let [source (strip-typescript-comments source)]
    (set/union (declaration-symbols source)
               (export-list-symbols source))))

(defn star-export-modules
  [source]
  (into #{}
        (map second)
        (re-seq
         #"(?m)^export\s+(?:type\s+)?\*\s+from\s+\"([^\"]+)\";"
         source)))

(defn interface-fields
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

(defn added-interface-fields
  [upstream base target path interface-name]
  (set/difference
   (interface-fields
    (git-output upstream "show" (str target ":" path))
    interface-name)
   (interface-fields
    (git-output upstream "show" (str base ":" path))
    interface-name)))

(defn string-union-values
  [source type-name]
  (let [pattern
        (re-pattern
         (str "(?ms)^export type "
              (java.util.regex.Pattern/quote type-name)
              "\\s*=\\s*(.*?);"))
        body (second (re-find pattern source))]
    (when-not body
      (throw (ex-info "Expected exported string union was not found"
                      {:type-name type-name})))
    (->> (str/replace body #"(?s)/\*.*?\*/|//[^\n]*" "")
         (re-seq #"[\"']([^\"']+)[\"']")
         (map second)
         set)))

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

(defn changed-exported-declarations
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

(defn public-class-methods
  [source class-name]
  (->> (re-seq
        #"(?m)^    ((?:(?:private|public|protected|async|static|override|readonly)\s+)*)(?:(?:get|set)\s+)?(\[Symbol\.[A-Za-z_$][A-Za-z0-9_$]*\]|[A-Za-z_$][A-Za-z0-9_$]*)\s*\("
        (class-source source class-name))
       (remove #(re-find #"\b(?:private|protected)\b" (second %)))
       (map #(nth % 2))
       set))

(def ^:private public-class-method-start-pattern
  #"(?m)^    ((?:(?:private|public|protected|async|static|override|readonly)\s+)*)(?:(?:get|set)\s+)?(?:\[Symbol\.[A-Za-z_$][A-Za-z0-9_$]*\]|[A-Za-z_$][A-Za-z0-9_$]*)(?:<[^(){};]*>)?\s*\(")

(defn- previous-non-whitespace-character
  [source index]
  (loop [index (dec index)]
    (when (>= index 0)
      (let [ch (.charAt source index)]
        (if (Character/isWhitespace ch)
          (recur (dec index))
          ch)))))

(defn- internal-jsdoc?
  [source start]
  (let [prefix (str/trimr (subs source 0 start))
        comment-start (.lastIndexOf prefix "/*")]
    (and (>= comment-start 0)
         (str/starts-with? (subs prefix comment-start) "/**")
         (str/ends-with? prefix "*/")
         (re-find #"(?m)(?:^|\s)@internal(?:\s|$)"
                  (subs prefix comment-start)))))

(defn- method-signature-end
  [source start]
  (loop [index start
         quote-char nil
         escaped? false
         paren-depth 0
         bracket-depth 0
         brace-depth 0
         angle-depth 0
         parameters-opened? false
         parameters-closed? false
         return-type? false]
    (when (>= index (count source))
      (throw (ex-info "Unterminated public TypeScript method signature"
                      {:start start})))
    (let [ch (.charAt source index)]
      (cond
        quote-char
        (cond
          escaped?
          (recur (inc index) quote-char false
                 paren-depth bracket-depth brace-depth angle-depth
                 parameters-opened? parameters-closed? return-type?)

          (= ch \\)
          (recur (inc index) quote-char true
                 paren-depth bracket-depth brace-depth angle-depth
                 parameters-opened? parameters-closed? return-type?)

          (= ch quote-char)
          (recur (inc index) nil false
                 paren-depth bracket-depth brace-depth angle-depth
                 parameters-opened? parameters-closed? return-type?)

          :else
          (recur (inc index) quote-char false
                 paren-depth bracket-depth brace-depth angle-depth
                 parameters-opened? parameters-closed? return-type?))

        (#{\" \' \`} ch)
        (recur (inc index) ch false
               paren-depth bracket-depth brace-depth angle-depth
               parameters-opened? parameters-closed? return-type?)

        (= ch \()
        (recur (inc index) nil false
               (inc paren-depth) bracket-depth brace-depth angle-depth
               true parameters-closed? return-type?)

        (= ch \))
        (let [next-depth (dec paren-depth)]
          (recur (inc index) nil false
                 next-depth bracket-depth brace-depth angle-depth
                 parameters-opened?
                 (or parameters-closed?
                     (and parameters-opened? (zero? next-depth)))
                 return-type?))

        (= ch \[)
        (recur (inc index) nil false
               paren-depth (inc bracket-depth) brace-depth angle-depth
               parameters-opened? parameters-closed? return-type?)

        (= ch \])
        (recur (inc index) nil false
               paren-depth (dec bracket-depth) brace-depth angle-depth
               parameters-opened? parameters-closed? return-type?)

        (= ch \<)
        (recur (inc index) nil false
               paren-depth bracket-depth brace-depth (inc angle-depth)
               parameters-opened? parameters-closed? return-type?)

        (= ch \>)
        (recur (inc index) nil false
               paren-depth bracket-depth brace-depth
               (max 0 (dec angle-depth))
               parameters-opened? parameters-closed? return-type?)

        (and (= ch \:)
             parameters-closed?
             (every? zero?
                     [paren-depth bracket-depth brace-depth angle-depth]))
        (recur (inc index) nil false
               paren-depth bracket-depth brace-depth angle-depth
               parameters-opened? parameters-closed? true)

        (= ch \{)
        (if (and (every? zero?
                         [paren-depth bracket-depth brace-depth angle-depth])
                 (not
                  (and return-type?
                       (#{\: \| \& \?}
                        (previous-non-whitespace-character source index)))))
          index
          (recur (inc index) nil false
                 paren-depth bracket-depth (inc brace-depth) angle-depth
                 parameters-opened? parameters-closed? return-type?))

        (= ch \})
        (recur (inc index) nil false
               paren-depth bracket-depth (dec brace-depth) angle-depth
               parameters-opened? parameters-closed? return-type?)

        (and (= ch \;)
             (every? zero?
                     [paren-depth bracket-depth brace-depth angle-depth]))
        index

        :else
        (recur (inc index) nil false
               paren-depth bracket-depth brace-depth angle-depth
               parameters-opened? parameters-closed? return-type?)))))

(defn public-class-method-signatures
  [source class-name]
  (let [body (class-source source class-name)
        masked-body (strip-typescript-comments body)
        matcher (re-matcher public-class-method-start-pattern masked-body)]
    (loop [signatures []]
      (if (.find matcher)
        (let [modifiers (.group matcher 1)
              start (.start matcher)]
          (if (or (re-find #"\b(?:private|protected)\b" modifiers)
                  (internal-jsdoc? body start))
            (recur signatures)
            (let [end (method-signature-end masked-body start)
                  signature
                  (-> (subs masked-body start end)
                      (str/replace #"\s+" " ")
                      str/trim
                      (str/replace #"^(?:(?:public|override)\s+)+" "")
                      (str/replace #"\(\s+" "(")
                      (str/replace #"\s+\)" ")"))]
              (recur (conj signatures signature)))))
        (vec (sort signatures))))))

(defn changed-source-lines
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

(defn referenced-evidence
  [report]
  (set
   (concat
    (mapcat :evidence (:stable-deltas report))
    (mapcat :evidence (:intentional-exclusions report)))))
