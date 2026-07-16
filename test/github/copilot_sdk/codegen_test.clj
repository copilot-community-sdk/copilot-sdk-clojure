(ns github.copilot-sdk.codegen-test
  "Cross-validation tests for the schema-driven codegen pipeline.

   Four distinct purposes:

   1. **Forward correctness** — generated `*-data` specs in
      `github.copilot-sdk.generated.event-specs` must accept canonical wire
      payloads (post `util/wire->clj`). If they don't, the generator or the
      pinned schema is wrong.

   2. **Envelope discrimination** — generated full envelope specs (e.g.
      `::session.start`) and the aggregate `::event` spec must reject
      payloads with a wrong `:type` literal, mismatched `:data` shape, or
      an unknown event type. These tests pin the envelope-as-discriminator
      contract: a feature regression here would silently destroy the value
      of `::event`.

   3. **Three-tier coercion contract** (Phase 3.5) — every coercion entry
      in `script/codegen/coercions.edn` must:
        a. round-trip semantically through wire→idiom→wire (Instant
           equality, not string equality, since ISO 8601 normalization can
           reshape the textual form);
        b. be exercised by at least one fixture (no dead entries);
        c. produce a value that satisfies the hand-written idiom spec.

   4. **Drift audit** — for every event variant that has BOTH a hand-written
      spec in `github.copilot-sdk.specs` and a generated spec, the hand spec
      must accept the COERCED payload (not the raw wire payload). After
      Phase 3.5 the audit's `known-drifts` set is empty: every richer hand
      spec is either reconciled by coercion or reflected in a passthrough
      spec.

   These tests do **not** instrument any runtime path. They validate the
   generated artifacts directly against fixture payloads, so they are pure
   regression coverage for the codegen pipeline."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [github.copilot-sdk.generated.coerce :as coerce]
            [github.copilot-sdk.generated.event-specs :as gen]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.specs])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Schema introspection helpers — used by the envelope helper to honour
;; per-variant `const` properties (e.g. `ephemeral: const true` on
;; `session.idle`). Reading the schema at test time keeps the test in
;; lock-step with whatever the generator was run against; if upstream changes
;; the const value, the generated spec changes and the test agrees.
;; ---------------------------------------------------------------------------

(def ^:private schema
  (with-open [r (io/reader (io/file "schemas/session-events.schema.json"))]
    (json/read r :key-fn keyword)))

(def ^:private envelope-consts-by-event
  "Map of event-type → {envelope-property → const-value} for every variant
   property declaring a JSON Schema `const`. Used to construct envelopes
   that satisfy the generator's const predicates."
  (let [variants (-> schema :definitions :SessionEvent :anyOf)
        deref-ref (fn [node]
                    (if-let [r (:$ref node)]
                      (let [path (rest (str/split r #"/"))]
                        (get-in schema (mapv keyword path)))
                      node))]
    (into {}
          (keep (fn [variant]
                  (let [variant    (deref-ref variant)
                        props      (:properties variant)
                        event-type (get-in props [:type :const])
                        consts     (->> props
                                        (keep (fn [[k v]]
                                                (when (contains? v :const)
                                                  [(keyword (name k))
                                                   (:const v)])))
                                        (into {}))]
                    (when event-type
                      [event-type consts])))
                variants))))

(def ^:private internal-event-types
  "Event types that the protocol schema marks internal. Generated wire specs
   cover them for forward compatibility, but they are not part of the public
   SDK event surface."
  (let [variants (-> schema :definitions :SessionEvent :anyOf)]
    (into #{}
          (keep (fn [variant]
                  (let [variant (if-let [r (:$ref variant)]
                                  (get-in schema (mapv keyword (rest (str/split r #"/"))))
                                  variant)]
                    (when (= "internal" (:visibility variant))
                      (get-in variant [:properties :type :const])))))
          variants)))

;; ---------------------------------------------------------------------------
;; Canonical wire-shape fixtures (post `util/wire->clj`, i.e. kebab-case keys).
;; Keep these minimal but type-correct per the upstream JSON schema.
;; ---------------------------------------------------------------------------

(def ^:private fixtures
  "Map of event-type → minimal-but-valid `data` payload."
  {"session.start"
   {:session-id "s-1"
    :version 1                                 ;; number per schema
    :producer "test"
    :copilot-version "1.0.0"
    :start-time "2024-01-01T00:00:00Z"}        ;; ISO string per schema

   "session.resume"
   {:resume-time "2024-01-01T00:00:00Z"
    :event-count 0}

   "session.error"
   {:error-type "internal"
    :message "boom"}

   "session.idle"
   {}

   "session.info"
   {:info-type "general"
    :message "hello"}

   "session.shutdown"
   {:shutdown-type "routine"
    :total-premium-requests 0
    :total-api-duration-ms 0
    :session-start-time 1700000000
    :code-changes {}
    :model-metrics {}}

   "session.model_change"
   {:new-model "gpt-4o"}

   "session.handoff"
   {:handoff-time "2024-01-01T00:00:00Z"
    :source-type "remote"}

   "user.message"
   {:content "hello"}

   "assistant.turn_start"
   {:turn-id "t-1"}

   "assistant.reasoning"
   {:reasoning-id "r-1"
    :content "thinking"}

   "assistant.reasoning_delta"
   {:reasoning-id "r-1"
    :delta-content "thi"}

   "assistant.message"
   {:message-id "m-1"
    :content "hi"}

   "assistant.message_delta"
   {:message-id "m-1"
    :delta-content "hi"}

   "assistant.usage"
   {:model "gpt-4o"}

   "tool.execution_start"
   {:tool-call-id "tc-1"
    :tool-name "shell"}

   "tool.execution_progress"
   {:tool-call-id "tc-1"
    :progress-message "running…"}

   "tool.execution_complete"
   {:tool-call-id "tc-1"
    :success true}

   "skill.invoked"
   {:name "my-skill"
    :path "/skills/my-skill"
    :content "skill body"}

   "subagent.started"
   {:tool-call-id "tc-1"
    :agent-name "subagent"
    :agent-display-name "SubAgent"
    :agent-description "does things"}

   "subagent.completed"
   {:tool-call-id "tc-1"
    :agent-name "subagent"
    :agent-display-name "SubAgent"}

   "subagent.failed"
   {:tool-call-id "tc-1"
    :agent-name "subagent"
    :agent-display-name "SubAgent"
    :error "boom"}

   "assistant.streaming_delta"
   {:total-response-size-bytes 1024}

   "session.title_changed"
   {:title "My Title"}

   "session.warning"
   {:warning-type "general"
    :message "watch out"}

   "session.context_changed"
   {:cwd "/tmp"}

   "session.mode_changed"
   {:previous-mode "interactive"
    :new-mode "plan"}

   "session.plan_changed"
   {:operation "create"}

   "session.workspace_file_changed"
   {:path "/tmp/file"
    :operation "create"}

   "session.task_complete"
   {}

   "session.custom_agents_updated"
   {:agents []
    :warnings []
    :errors []}

   "session.mcp_servers_loaded"
   {:servers []}

   "session.mcp_server_status_changed"
   {:server-name "server-1"
    :status "connected"}

   "session.skills_loaded"
   {:skills []}

   "session.extensions_loaded"
   {:extensions []}

   "session.schedule_created"
   {:id 42 :interval-ms 1000 :prompt "ping me"}

   "session.schedule_cancelled"
   {:id 42}

   "session.custom_notification"
   {:source "my-extension"
    :name "doc.opened"
    :payload {:path "/tmp/x"}
    :subject {:doc "foo"}
    :version 1}

   ;; Round 6 additions (upstream schema 1.0.56-1).
   "session.permissions_changed"
   {:allow-all-permissions true
    :previous-allow-all-permissions false}

   "session.autopilot_objective_changed"
   {:operation "create"
    :id 7
    :status "active"}

   "hook.progress"
   {:message "extracting..."}

   ;; v1.0.1 sync: session.canvas.closed (upstream PR #1604).
   "session.canvas.closed"
   {:instance-id "i1" :extension-id "ext.x" :canvas-id "diff"}})

(defn- envelope
  "Wrap a data payload in a minimal valid envelope of the given type. Honours
   per-variant `const` envelope fields from the schema (e.g. some variants
   pin `ephemeral: const true`); fields without a const default to literals
   the schema's structural rules accept."
  [event-type data]
  (let [consts (get envelope-consts-by-event event-type {})]
    (merge {:id "evt-1"
            :timestamp "2024-01-01T00:00:00Z"
            :parent-id "p-1"
            :ephemeral false
            :type event-type
            :data data}
           consts)))

;; ---------------------------------------------------------------------------
;; Forward correctness — generated specs must accept wire-shape payloads.
;; ---------------------------------------------------------------------------

(deftest generated-data-specs-accept-wire-payloads
  (doseq [[event-type payload] fixtures]
    (testing (str "generated " event-type "-data accepts canonical payload")
      (let [spec-kw (keyword "github.copilot-sdk.generated.event-specs"
                             (str event-type "-data"))]
        (is (s/get-spec spec-kw)
            (str "generated spec missing for " event-type))
        (is (s/valid? spec-kw payload)
            (str "generated spec rejected wire payload for " event-type
                 ": " (s/explain-str spec-kw payload)))))))

(deftest event-types-set-matches-fixtures
  (testing "every fixture event-type is in the generated event-types set"
    (doseq [event-type (keys fixtures)]
      (is (contains? gen/event-types event-type)
          (str event-type " missing from gen/event-types — schema may have moved")))))

(deftest public-event-types-match-generated-schema-set
  (testing "the public curated `event-types` set covers exactly the schema's public event types
            (guards against drift without exposing protocol-internal events)"
    (let [curated (set (map name sdk/event-types))
          generated (clojure.set/difference gen/event-types internal-event-types)
          missing (clojure.set/difference generated curated)
          extra (clojure.set/difference curated generated)]
      (is (empty? missing)
          (str "schema event types missing from public event-types: " (sort missing)
               " — add them (or update the schema pin)"))
      (is (empty? extra)
          (str "public event-types not present in the schema: " (sort extra)
               " — remove them or update the schema pin")))))

(deftest generated-data-specs-reject-envelope-weakened-types
  (testing "session.schedule_created-data rejects string :id (must be positive integer)"
    (let [spec-kw :github.copilot-sdk.generated.event-specs/session.schedule_created-data]
      (is (not (s/valid? spec-kw {:id "uuid-string" :interval-ms 1000 :prompt "x"}))
          "data spec must not accept envelope-shaped UUID :id")))
  (testing "session.schedule_cancelled-data rejects string :id (must be positive integer)"
    (let [spec-kw :github.copilot-sdk.generated.event-specs/session.schedule_cancelled-data]
      (is (not (s/valid? spec-kw {:id "uuid-string"}))
          "data spec must not accept envelope-shaped UUID :id"))))

;; ---------------------------------------------------------------------------
;; Envelope discrimination — type and data binding must be tight.
;; ---------------------------------------------------------------------------

(deftest envelope-accepts-matching-type-and-data
  (doseq [[event-type payload] fixtures]
    (testing (str "::" event-type " accepts a well-formed envelope")
      (let [spec-kw (keyword "github.copilot-sdk.generated.event-specs" event-type)
            env    (envelope event-type payload)]
        (is (s/valid? spec-kw env)
            (str "envelope rejected: " (s/explain-str spec-kw env)))))))

(deftest envelope-rejects-wrong-type-literal
  (let [start-payload (get fixtures "session.start")
        wrong-env     (envelope "session.shutdown" start-payload)]
    (is (not (s/valid? :github.copilot-sdk.generated.event-specs/session.start
                       wrong-env))
        "::session.start must reject envelope whose :type is not \"session.start\"")
    (is (not (s/valid? :github.copilot-sdk.generated.event-specs/session.shutdown
                       wrong-env))
        "::session.shutdown must reject envelope whose :data does not match its data spec")))

(deftest envelope-rejects-mismatched-data-shape
  (let [bad-env (envelope "session.start" {:totally "unrelated"})]
    (is (not (s/valid? :github.copilot-sdk.generated.event-specs/session.start
                       bad-env))
        "::session.start must reject envelope when :data does not satisfy ::session.start-data")))

(deftest aggregate-event-spec-discriminates
  (testing "::event accepts known event types with matching data"
    (doseq [[event-type payload] fixtures]
      (let [env (envelope event-type payload)]
        (is (s/valid? ::gen/event env)
            (str "::event rejected " event-type ": " (s/explain-str ::gen/event env))))))
  (testing "::event rejects unknown :type"
    (let [env (envelope "definitely-not-an-event"
                        (get fixtures "session.start"))]
      (is (not (s/valid? ::gen/event env))
          "::event must reject unknown :type values")))
  (testing "::event rejects mismatched (type, data) pairs"
    (let [env (envelope "session.start" (get fixtures "session.shutdown"))]
      (is (not (s/valid? ::gen/event env))
          "::event must reject envelopes whose :data shape does not match :type"))))

;; ---------------------------------------------------------------------------
;; Three-tier coercion contract (Phase 3.5).
;; ---------------------------------------------------------------------------

(deftest coercion-table-is-exercised-by-fixtures
  (testing "every coercion entry is exercised by ≥1 fixture with a non-nil value"
    (doseq [[event-type fields] coerce/field-coercions
            [field _tag-pair]   fields]
      (is (contains? fixtures event-type)
          (str "coercion declared for " event-type "/" field
               " but no fixture covers it"))
      (is (contains? (get fixtures event-type) field)
          (str "fixture for " event-type " is missing field " field
               " required by coercion table"))
      (is (some? (get-in fixtures [event-type field]))
          (str "fixture for " event-type "/" field
               " has nil value; coverage would be vacuous")))))

(deftest coercion-is-idempotent
  (testing "applying wire->idiom twice equals applying it once"
    (doseq [[event-type payload] fixtures]
      (let [event   {:type event-type :data payload}
            once    (coerce/event-wire->idiom event)
            twice   (coerce/event-wire->idiom once)]
        (is (= once twice)
            (str "coercion not idempotent for " event-type))))))

(deftest coercion-round-trips-semantically
  (testing "wire → idiom → wire preserves field values (semantic equality)"
    (doseq [[event-type payload] fixtures]
      (let [event       {:type event-type :data payload}
            round-trip  (coerce/event-idiom->wire (coerce/event-wire->idiom event))
            fields      (get coerce/field-coercions event-type)]
        (doseq [[field [wire-tag _idiom-tag]] fields
                :let [orig-v (get-in event       [:data field])
                      rt-v   (get-in round-trip  [:data field])]
                :when (some? orig-v)]
          ;; For ISO timestamps, compare semantically (Instant equality)
          ;; rather than textually — ISO 8601 has multiple valid string
          ;; representations and Instant/toString canonicalizes.
          (case wire-tag
            :iso-string
            (is (= (Instant/parse orig-v) (Instant/parse rt-v))
                (str "round-trip lost semantic equality for "
                     event-type "/" field ": " orig-v " ⇄ " rt-v))
            ;; default: structural equality
            (is (= orig-v rt-v)
                (str "round-trip lost equality for "
                     event-type "/" field))))))))

(deftest coerced-data-satisfies-hand-spec
  (testing "after wire->idiom, hand-written spec accepts the data"
    (doseq [[event-type payload] fixtures]
      (let [hand-kw (keyword "github.copilot-sdk.specs" (str event-type "-data"))]
        (when (s/get-spec hand-kw)
          (let [coerced-data (coerce/coerce-data event-type payload :wire->idiom)]
            (is (s/valid? hand-kw coerced-data)
                (str "hand spec " hand-kw " rejected coerced data: "
                     (s/explain-str hand-kw coerced-data)))))))))

;; ---------------------------------------------------------------------------
;; Drift audit — surfaces residual disagreements between hand-written and
;; generated specs after coercion is applied.
;;
;; The audit is FIELD-PRECISE:
;;   * `known-drifts` lists [event-type field reason] tuples for any
;;     residual drift that the coercion layer does NOT reconcile.
;;   * For every fixture, we extract the set of failing fields from
;;     `s/explain-data` against the hand-written spec, AFTER coercion.
;;   * We assert that set is exactly equal to the documented drifts for
;;     that event-type — so stale entries (drift was fixed but registry
;;     still references it) AND undocumented drifts both fail the test.
;;
;; After Phase 3.5 reconciliation, this set should be empty: every
;; richer hand spec is reconciled either via coercion (Instant) or by
;; intentional omission from the hand spec (e.g. session.start :version,
;; where the global ::version is shared with ::model-info and the
;; generated wire spec is the canonical contract).
;; ---------------------------------------------------------------------------

(def ^:private known-drifts
  "Hand-written specs known to disagree with the schema even after
   coercion. Each entry is a [event-type field reason] tuple. Empty after
   Phase 3.5: the test fails if any drift is detected, forcing the
   contributor to either add a coercion entry or fix the hand spec."
  #{})

(defn- failing-fields
  "Extract the set of map keys (top-level :in path heads) where `payload`
   fails `spec`. Returns #{} when payload is valid."
  [spec payload]
  (let [data (s/explain-data spec payload)]
    (->> (:clojure.spec.alpha/problems data)
         (map (fn [{:keys [in path]}]
                ;; Prefer :in (which targets actual map keys) over :path
                ;; (which references the s/keys spec name).
                (or (first in) (last path))))
         (remove nil?)
         set)))

(deftest hand-written-specs-agree-with-generated
  (doseq [[event-type payload] fixtures]
    (testing (str "specs.clj ::" event-type "-data agrees with generated for canonical payload (post-coercion)")
      (let [hand-kw (keyword "github.copilot-sdk.specs" (str event-type "-data"))
            gen-kw  (keyword "github.copilot-sdk.generated.event-specs"
                             (str event-type "-data"))]
        (when (s/get-spec hand-kw)
          (is (s/valid? gen-kw payload)
              (str "Generator regression: " gen-kw " rejects fixture: "
                   (s/explain-str gen-kw payload)))
          (let [coerced-data    (coerce/coerce-data event-type payload :wire->idiom)
                actual-failing  (failing-fields hand-kw coerced-data)
                registered-fields (->> known-drifts
                                       (filter #(= event-type (first %)))
                                       (map second)
                                       set)]
            (is (= actual-failing registered-fields)
                (str "Drift mismatch for " event-type " (after coercion):\n"
                     "  actual failing fields:    " (sort actual-failing) "\n"
                     "  registered known-drifts:  " (sort registered-fields) "\n"
                     (cond
                       (seq (clojure.set/difference actual-failing registered-fields))
                       (str "  → undocumented drifts: "
                            (sort (clojure.set/difference actual-failing registered-fields))
                            " (add a coercion entry, fix specs.clj, or add to known-drifts)")
                       (seq (clojure.set/difference registered-fields actual-failing))
                       (str "  → stale drift entries: "
                            (sort (clojure.set/difference registered-fields actual-failing))
                            " (remove from known-drifts; the drift is no longer reproducible)")
                       :else "")))))))))

(deftest every-hand-written-event-data-spec-has-a-fixture
  ;; Coverage gate: the drift audit above only inspects event-types present
  ;; in `fixtures`. This test fails if `specs.clj` defines a richer hand-
  ;; written `::<event>-data` spec for an event that has NO fixture, so a
  ;; contributor cannot silently introduce a hand spec that the drift audit
  ;; never exercises.
  (let [hand-spec-event-types
        (->> gen/event-types
             (filter (fn [event-type]
                       (s/get-spec
                        (keyword "github.copilot-sdk.specs"
                                 (str event-type "-data")))))
             set)
        covered (set (keys fixtures))
        missing (clojure.set/difference hand-spec-event-types covered)]
    (is (empty? missing)
        (str "Hand-written `*-data` specs without a fixture (drift audit "
             "would silently skip them): " (sort missing) ". Either add a "
             "minimal valid fixture in `fixtures`, or remove the hand spec "
             "from `github.copilot-sdk.specs`."))))
