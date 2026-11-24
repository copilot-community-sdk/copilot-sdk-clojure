(ns github.copilot-sdk.native-certification-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- ledger []
  (edn/read-string (slurp (io/resource "resources/native_2_0_1_contracts.edn"))))

(defn- declared-types [{:keys [type-exports option-type-exports]}]
  (concat option-type-exports (mapcat val type-exports)))

(deftest catalog-has-one-disposition-per-declaration
  (let [{:keys [option-fields option-type-exports field-legend root-values methods reading gaps] :as data} (ledger)]
    (is (every? option-fields option-type-exports))
    (is (apply distinct? (declared-types data)))
    (doseq [[owner groups] option-fields]
      (testing (str owner)
        (is (every? field-legend (keys groups)))
        (is (apply distinct? (mapcat val groups)))))
    (doseq [[member [classification status target]] (concat root-values (mapcat val methods))]
      (testing (str member)
        (is (contains? (:classes reading) classification))
        (is (contains? (:status reading) status))
        (is (some? target))))
    (is (apply distinct? (map :id gaps)))
    (is (every? #(and (seq (:node %)) (seq (:why %))) gaps))))

(defn- output! [& cmd]
  (let [{:keys [exit out err]} (apply shell/sh cmd)]
    (when-not (zero? exit)
      (throw (ex-info (str "Cannot verify pinned inventory: " err) {:command cmd :exit exit})))
    out))

;; This is a reader for the pinned re-export and declaration syntax, not a
;; TypeScript parser. Compare names rather than trusting a changed line count.
(defn- re-exports [source]
  (reduce (fn [result [_ type-only body]]
            (reduce (fn [result raw]
                      (let [[a b] (str/split (str/trim raw) #"\s+")]
                        (update result (if (or type-only (= a "type")) :types :values)
                                conj (symbol (or b a)))))
                    result
                    (remove str/blank? (str/split body #","))))
          {:types #{} :values #{}}
          (re-seq #"(?ms)^export (type )?\{([^}]+)\} from \"\./[^\"]+\";" source)))

(defn- direct-fields [source owner indent]
  (let [pattern (re-pattern (str "(?ms)^(?:export )?(?:interface|type) " owner
                                 "\\b[^\\n{]*\\{\\n(.*?)\\n\\}"))
        body (second (re-find pattern source))]
    (into #{} (map (comp symbol second))
          (re-seq (re-pattern (str "(?m)^ {" indent "}(?:readonly )?(\\w+)\\??[:(]")) (or body "")))))

(defn- public-members [source owner]
  (let [body (second (re-find (re-pattern (str "(?ms)^export class " owner "\\b[^\\n]*\\{(.*?)\\n\\}")) source))]
    (into #{}
          (keep (fn [[_ doc member]]
                  (when-not (str/includes? (or doc "") "@internal")
                    (symbol (str/replace member #"^\[|\]$" "")))))
          (re-seq #"(?ms)(?:^    (/\*\*(?:(?!\*/).)*\*/)\s*\n)?^    (?:(?:public|async|get|set|static|readonly) +)*(\[Symbol\.asyncDispose\]|\w+)(?:<[^>\n]+>)?[(=:]"
                  (or body "")))))

(defn- discrepancies [catalog source]
  {:not-in-catalog (set/difference source catalog)
   :not-in-source (set/difference catalog source)})

(deftest pinned-upstream-declarations-match-catalog
  (when (= "true" (System/getenv "COPILOT_UPSTREAM_VALIDATION"))
    (let [{:keys [upstream root-values methods option-fields other-entrypoints type-exports gaps] :as data} (ledger)
          repo (str/trim (output! "bash" ".github/skills/update-upstream/scripts/resolve-upstream.sh"))
          git (fn [& args] (apply output! "git" "-C" repo args))
          pin (:commit upstream)
          show #(git "show" (str pin ":" %))
          index (show "nodejs/src/index.ts")
          types (show "nodejs/src/types.ts")
          exports (re-exports index)
          match! (fn [catalog source]
                   (is (= catalog source) (pr-str (discrepancies catalog source))))]
      (is (= pin (str/trim (git "rev-parse" (str (:tag upstream) "^{commit}")))))
      (match! (set (keys root-values)) (:values exports))
      (match! (set (declared-types data)) (:types exports))
      (let [{:keys [values types additional-type-family]} (:extension other-entrypoints)
            source (show "nodejs/src/extension.ts")
            exported (re-exports source)
            direct (fn [pattern] (set (map (comp symbol second) (re-seq pattern source))))]
        (match! (set values)
                (into (:values exported) (direct #"(?m)^export async function (\w+)")))
        (match! (into (set types) (get type-exports additional-type-family))
                (into (:types exported) (direct #"(?m)^export (?:type|interface) (\w+)"))))
      (let [{:keys [export source blob]} (:events other-entrypoints)]
        (is (str/includes? index export))
        (is (= blob (str/trim (git "rev-parse" (str pin ":" source))))))
      (doseq [[owner fields] option-fields]
        (testing (str owner)
          (match! (set (mapcat val fields)) (direct-fields types owner 4))))
      (let [rpc (show "nodejs/src/generated/rpc.ts")]
        (doseq [[owner members] (:types (some :capability-override gaps))]
          (testing (str owner)
            (match! (set members) (direct-fields rpc owner 2)))))
      (doseq [[owner file] {'CopilotClient "nodejs/src/client.ts" 'CopilotSession "nodejs/src/session.ts"}]
        (testing (str owner)
          (match! (set (for [[member [class]] (get methods owner) :when (not= class :internal)] member))
                  (public-members (show file) owner)))))))
