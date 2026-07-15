(ns github.copilot-sdk.api-surface
  "API-surface drift guard support.

   Computes a deterministic snapshot of the public contract:

   - every public var in the `github.copilot-sdk` facade namespace, tagged
     with its kind (`:fn` / `:macro` / `:value`) and, when the var carries it,
     its `:arglists` (plain `def` re-exports have no `:arglists` and omit the key)
   - every registered spec key in the curated `github.copilot-sdk.specs`
     namespace (the idiom spec surface)

   The snapshot is checked in at `resources/github/copilot_sdk/api_surface.edn`
   and compared against the live surface by `api-surface-test`. When the
   surface changes intentionally, regenerate the snapshot with:

       bb api-surface:update

   Regenerating is a deliberate, reviewable step: the diff on the EDN file
   is the record of every public-contract change."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            ;; Loading the facade pulls in the specs namespace, populating the
            ;; spec registry so `spec-keys` sees the full idiom surface.
            [github.copilot-sdk]))

(def ^:private facade-ns 'github.copilot-sdk)
(def ^:private spec-ns-prefix "github.copilot-sdk.specs")

(def snapshot-resource
  "Classpath resource path of the checked-in snapshot."
  "github/copilot_sdk/api_surface.edn")

(defn- snapshot-source-file
  "Writable source location of the snapshot (under `resources/`)."
  []
  (io/file "resources" snapshot-resource))

(defn- var-kind [v]
  (let [m (meta v)]
    (cond
      (:macro m) :macro
      (fn? @v) :fn
      :else :value)))

(defn- var-entry [v]
  (let [m (meta v)]
    (cond-> {:kind (var-kind v)}
      (:arglists m) (assoc :arglists (:arglists m)))))

(defn public-vars
  "Map of `symbol -> {:kind ...}` for the facade namespace. Entries also
   carry `:arglists` when the underlying var has it; plain `def` re-exports
   have no `:arglists` metadata, so the key is omitted for those."
  []
  (into (sorted-map)
        (map (fn [[sym v]] [sym (var-entry v)]))
        (ns-publics facade-ns)))

(defn spec-keys
  "Sorted vector of registered spec keys in the curated specs namespace."
  []
  (->> (s/registry)
       keys
       (filter keyword?)
       (filter #(some-> (namespace %) (str/starts-with? spec-ns-prefix)))
       (sort)
       (vec)))

(defn current-surface
  "The live public surface as a plain data snapshot."
  []
  {:vars (public-vars)
   :spec-keys (spec-keys)})

(defn read-snapshot
  "Read the checked-in snapshot, or nil if it is missing."
  []
  (when-let [r (io/resource snapshot-resource)]
    (with-open [rdr (io/reader r)]
      (edn/read (java.io.PushbackReader. rdr)))))

(defn- edn-string [surface]
  ;; Deterministic, human-diffable EDN: one spec key per line, vars sorted.
  (let [vars (:vars surface)
        spec-keys (:spec-keys surface)]
    (str ";; AUTO-GENERATED public API-surface snapshot. Do not edit by hand.\n"
         ";; Regenerate with `bb api-surface:update` after an intentional\n"
         ";; public-contract change. See github.copilot-sdk.api-surface.\n"
         "{:vars\n {"
         (str/join "\n  "
                   (for [[sym entry] vars]
                     (str (pr-str sym) " " (pr-str entry))))
         "}\n"
         " :spec-keys\n ["
         (str/join "\n  " (map pr-str spec-keys))
         "]}\n")))

(defn write-snapshot!
  "Write the current live surface to the snapshot source file."
  []
  (let [surface (current-surface)
        file (snapshot-source-file)]
    (io/make-parents file)
    (spit file (edn-string surface))
    (println "Wrote" (str file)
             (str "(" (count (:vars surface)) " vars, "
                  (count (:spec-keys surface)) " spec keys)"))))

(defn -main [& _]
  (write-snapshot!))
