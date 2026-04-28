(ns codegen.emit-specs
  "Emit clojure.spec forms for JSON Schema object definitions.

   Output structure (for each event variant):
     :github.copilot-sdk.generated.event-specs/<prop>          ;; per-property leaf spec
     :github.copilot-sdk.generated.event-specs/<event>-data    ;; the `data` payload spec
     :github.copilot-sdk.generated.event-specs/<event>         ;; the full envelope spec
   Plus an aggregate `::event` spec that accepts any of the variants.

   Translation rules (intentionally limited; falls back to `any?` for
   anything we don't model precisely):

   - `string`            → `string?` (or set literal for enum/const)
   - `string` + format=date-time → `string?`  (we keep ISO strings as-is)
   - `string` + format=uuid      → `string?`
   - `number` / `integer`→ `number?` / `integer?`
   - `boolean`           → `boolean?`
   - `array`             → `(s/coll-of <items>)`
   - `object` w/ properties → `(s/keys :req-un [...] :opt-un [...])`
   - `anyOf` (incl. nullable)   → `(s/or ...)` or `(s/nilable ...)`
   - Otherwise           → `any?`

   Unqualified keys in `s/keys` use namespace-qualified spec keywords; the
   :req-un / :opt-un mechanism then matches the unqualified name part against
   actual map keys (e.g. `:session-id`). Since wire payloads pass through
   `util/wire->clj` before reaching specs, all keys are kebab-case at this
   point."
  (:require [codegen.core :as cc]
            [clojure.string :as str]))

(def ^:private ns-name "github.copilot-sdk.generated.event-specs")

(defn- ns-kw
  "Build a keyword in the generated namespace."
  [name-part]
  (keyword ns-name name-part))

;; ---------------------------------------------------------------------------
;; Type emission
;; ---------------------------------------------------------------------------

(declare emit-type)

(defn- emit-string [node]
  (cond
    (:enum node)  (set (:enum node))
    (:const node) #{(:const node)}
    :else         `string?))

(defn- emit-array [root node]
  (let [items (:items node)]
    (if items
      `(~'s/coll-of ~(emit-type root items))
      `(~'s/coll-of any?))))

(defn- emit-anyOf [root node]
  (let [branches  (:anyOf node)
        non-null  (remove #(= "null" (:type %)) branches)
        nullable? (some  #(= "null" (:type %)) branches)
        emit-br   (fn [b] (emit-type root b))
        union     (cond
                    (empty? non-null)       `any?
                    (= 1 (count non-null))  (emit-br (first non-null))
                    :else
                    `(~'s/or ~@(mapcat (fn [i b]
                                         [(keyword (str "branch-" i)) (emit-br b)])
                                       (range)
                                       non-null)))]
    (if nullable?
      `(~'s/nilable ~union)
      union)))

(defn emit-type
  [root node]
  (let [node (cc/deref-once root node)]
    (cond
      (:anyOf node)              (emit-anyOf root node)
      (= "string"  (:type node)) (emit-string node)
      (= "integer" (:type node)) `integer?
      (= "number"  (:type node)) `number?
      (= "boolean" (:type node)) `boolean?
      (= "null"    (:type node)) `nil?
      (= "array"   (:type node)) (emit-array root node)
      (= "object"  (:type node)) `map?            ;; nested objects → map? for now
      :else                      `any?)))

;; ---------------------------------------------------------------------------
;; Leaf-property collection
;; ---------------------------------------------------------------------------
;; Approach:
;;   - Walk every variant's envelope properties + data.properties.
;;   - For each unique kebab property name, collect all observed schema nodes.
;;   - If they all yield the same emitted spec form → use it.
;;   - Otherwise → fall back to `any?` (and warn on stderr).

(defn- walk-props
  "Collect [kebab-name schema-node] tuples from an object schema's properties."
  [props]
  (for [[wire-k node] props]
    [(cc/wire-key->kebab (name wire-k)) node]))

(defn- collect-leaf-properties
  "Return a sorted-by-name map of kebab-name → schema-node, picking a
   representative node when names collide consistently. Warns on conflicts."
  [root variants]
  (let [pairs   (mapcat (fn [{:keys [variant]}]
                          (concat (walk-props (:properties variant))
                                  (walk-props (get-in variant [:properties :data :properties]))))
                        variants)
        groups  (group-by first pairs)]
    (into (sorted-map)
          (for [[kebab pairs] groups
                :let [nodes  (mapv second pairs)
                      forms  (mapv #(emit-type root %) nodes)
                      uniq   (set forms)]]
            (if (= 1 (count uniq))
              [kebab (first nodes)]
              ;; Conflict: widen to any?
              (do
                (binding [*out* *err*]
                  (println (format "WARN: property '%s' has %d distinct schemas — widening to any?"
                                   kebab (count uniq))))
                [kebab {:type "any"}]))))))

;; ---------------------------------------------------------------------------
;; Emission
;; ---------------------------------------------------------------------------

(defn- emit-leaf-defs
  "Emit `(s/def ::<kebab> <form>)` for every leaf property."
  [root leaf-map]
  (for [[kebab node] leaf-map]
    `(~'s/def ~(ns-kw kebab) ~(emit-type root node))))

(defn- emit-data-spec
  "Emit `(s/def ::<event>-data (s/keys ...))` for one event's data payload."
  [variant]
  (let [event-type (get-in variant [:properties :type :const])
        data-node  (get-in variant [:properties :data])
        props      (:properties data-node)
        required   (set (:required data-node))
        kebab      (fn [k] (cc/wire-key->kebab (name k)))
        ;; Sort by name for deterministic emission across Clojure hash variants.
        req-keys   (->> props
                        (filter (fn [[k _]] (contains? required (name k))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        opt-keys   (->> props
                        (filter (fn [[k _]] (not (contains? required (name k)))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        keys-form  (cond-> `(~'s/keys)
                     (seq req-keys) (concat [:req-un req-keys])
                     (seq opt-keys) (concat [:opt-un opt-keys])
                     true           seq)]
    `(~'s/def ~(ns-kw (str event-type "-data")) ~keys-form)))

(defn- emit-envelope-spec
  "Emit the full envelope spec `(s/def ::<event> ...)`. Uses `s/and` to
   combine the structural `s/keys` (presence + leaf types) with predicates
   that bind every envelope property declaring a JSON Schema `const` value
   to that literal (e.g. `:type` for the variant, `:ephemeral true` for
   variants like `session.idle`), and a final predicate that delegates
   `:data` validation to the variant's `::<event>-data` spec, so envelopes
   from one event variant cannot validate against another."
  [variant]
  (let [event-type (get-in variant [:properties :type :const])
        envelope   (:properties variant)
        required   (set (:required variant))
        kebab      (fn [k] (cc/wire-key->kebab (name k)))
        req-keys   (->> envelope
                        (filter (fn [[k _]] (contains? required (name k))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        opt-keys   (->> envelope
                        (filter (fn [[k _]] (not (contains? required (name k)))))
                        (map    (fn [[k _]] (ns-kw (kebab k))))
                        (sort-by name)
                        vec)
        keys-form  (cond-> `(~'s/keys)
                     (seq req-keys) (concat [:req-un req-keys])
                     (seq opt-keys) (concat [:opt-un opt-keys])
                     true           seq)
        data-kw    (ns-kw (str event-type "-data"))
        ;; Emit one predicate per envelope property with a JSON Schema
        ;; `const` value. Sorted by property name for deterministic output.
        const-preds (->> envelope
                         (keep (fn [[k v]]
                                 (when (contains? v :const)
                                   [(kebab k) (:const v)])))
                         (sort-by first)
                         (map (fn [[prop-name const-val]]
                                `(~'fn [~'event]
                                   (= ~const-val
                                      (~(keyword prop-name) ~'event))))))]
    `(~'s/def ~(ns-kw event-type)
              (~'s/and
                ~keys-form
                ~@const-preds
                (~'fn [~'event] (~'s/valid? ~data-kw (:data ~'event)))))))

(defn- emit-event-multi-spec
  "Emit a `defmulti` + `defmethod`s + aggregate `::event` spec that dispatches
   on the `:type` field. Using `s/multi-spec` (rather than `s/or`) keeps
   error messages variant-targeted and makes adding new event types O(1).
   Unknown event types fall through to the `:default` method which returns
   nil — `s/multi-spec` treats that as invalid."
  [variants]
  (let [mm-sym       'event-mm
        defmulti-form `(~'defmulti ~mm-sym :type)
        sorted        (sort-by :type variants)
        defmethods    (for [{:keys [type]} sorted]
                        `(~'defmethod ~mm-sym ~type [~'_]
                                       (~'s/get-spec ~(ns-kw type))))
        default-method `(~'defmethod ~mm-sym :default [~'_] nil)
        aggregate     `(~'s/def ~(ns-kw "event")
                                (~'s/multi-spec ~mm-sym :type))]
    (concat [defmulti-form] defmethods [default-method aggregate])))

(defn- emit-event-types-set
  "Emit a `def` containing the sorted set of all event-type strings."
  [variants]
  `(~'def ~'event-types
          "Set of all event-type strings known to the schema."
          ~(into (sorted-set) (map :type variants))))

;; ---------------------------------------------------------------------------
;; Public entry point
;; ---------------------------------------------------------------------------

(defn emit-event-specs-ns
  "Build the form list for the generated event-specs namespace."
  [root]
  (let [variants  (cc/collect-anyOf-discriminators root)
        ;; Sort variants by event-type for deterministic emission.
        sorted    (sort-by :type variants)
        leaf-map  (collect-leaf-properties root variants)]
    (concat
      [`(~'ns ~(symbol ns-name)
              "AUTO-GENERATED. clojure.spec definitions for upstream session events.

   Each event variant's `data` payload is registered under
   `::<event-type>-data` (e.g. `::session.start-data`).
   The envelope (id/timestamp/parentId/type/data) is registered under
   `::<event-type>` (e.g. `::session.start`).

   Source: schemas/session-events.schema.json"
              (:require [clojure.spec.alpha :as ~'s]))]
      (emit-leaf-defs root leaf-map)
      (mapv #(emit-data-spec     (:variant %)) sorted)
      (mapv #(emit-envelope-spec (:variant %)) sorted)
      [(emit-event-types-set variants)]
      (emit-event-multi-spec variants))))
