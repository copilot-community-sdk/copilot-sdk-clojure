(ns ^:no-doc github.copilot-sdk.util
  "Key conversion at the native protocol boundary."
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [copilot.sdk.specs :as specs]))

(defn- convert-keys [convert unchanged value]
  (cske/transform-keys
   (fn [k]
     (if (and (keyword? k) (not (and (nil? (namespace k)) (re-matches unchanged (name k)))))
       (convert k)
       k))
   value))

(defn clj->wire
  "Convert SDK keyword keys to camelCase. String keys retain their spelling."
  [value]
  (convert-keys csk/->camelCaseKeyword #"[a-z0-9]+" value))

(defn wire->clj
  "Convert protocol keyword keys to kebab-case. String keys retain their spelling."
  [value]
  (convert-keys csk/->kebab-case-keyword #"[a-z]+" value))

(defn- member-name [k]
  (cond
    (string? k) k
    (keyword? k) (subs (str k) 1)
    :else (throw (ex-info "Opaque JSON object keys must be strings or keywords" {}))))

(defn opaque-json->wire
  "Return JSON data with string object keys, preserving case, punctuation and
  keyword namespaces. Arrays must be vectors. Reject colliding member names."
  [root]
  ;; Each frame contains a parent key, output so far, and remaining entries.
  ;; Heap frames avoid a JVM stack frame per level of caller-supplied data.
  (loop [frames (list [nil [] [[0 root]]])]
    (let [[at out entries] (first frames)
          parents (next frames)]
      (if-let [[k value] (first entries)]
        (let [k (if (vector? out) k (member-name k))
              more (next entries)]
          (when (contains? out k)
            (throw (ex-info "Duplicate opaque JSON member name" {})))
          (cond
            (map? value)
            (recur (cons [k {} (seq value)] (cons [at out more] parents)))

            (vector? value)
            (recur (cons [k [] (map-indexed vector value)] (cons [at out more] parents)))

            (s/valid? ::specs/json-scalar value)
            (recur (cons [at (assoc out k value) more] parents))

            :else (throw (ex-info "Expected a JSON scalar, map or vector" {}))))
        (if-let [[parent-key parent-out more] (first parents)]
          (recur (cons [parent-key (assoc parent-out at out) more] (next parents)))
          (first out))))))

(defn auto-tier-identifier?
  "True for nonempty identifiers without ECMAScript whitespace or control characters."
  [value]
  (and (string? value)
       (boolean (re-matches #"[^\u0000-\u0020\u007F-\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]+"
                            value))))

(def section-key->wire
  (into {} (map (fn [k] [k (str/replace (name k) "-" "_")])) (keys specs/system-message-sections)))

(defn event-type->keyword
  "Qualify a runtime event type, e.g. \"assistant.message\" becomes :copilot/assistant.message."
  [event-type]
  (keyword "copilot" (name event-type)))
