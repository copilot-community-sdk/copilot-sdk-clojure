(ns github.copilot-sdk.integration.canvas-events-test
  "Focused integration tests using the mock JSON-RPC server."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.core.async :as async :refer [<!! >!! chan close! go timeout alts!!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.test :as log-test]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.factory :as factory]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.tools :as tools]
            [github.copilot-sdk.util :as util]
            [github.copilot-sdk.generated.event-specs :as generated-events]
            [github.copilot-sdk.integration.support
             :refer [*mock-server*
                     *test-client*
                     await-value!
                     await-atom!
                     await-event-type!
                     observe-take-attempts
                     with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]))

(use-fixtures :each with-mock-server)

(deftest test-canvas-closed-event-in-public-event-types
  (testing "session.canvas.closed registered (upstream PR #1604)"
    (is (contains? sdk/event-types :copilot/session.canvas.closed))
    (is (s/valid? :github.copilot-sdk.specs/event-type :copilot/session.canvas.closed)))
  (testing "::session.canvas.closed-data idiom spec accepts well-formed payload"
    (is (s/valid? :github.copilot-sdk.specs/session.canvas.closed-data
                  {:instance-id "i1" :extension-id "ext-a" :canvas-id "c1"}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.canvas.closed-data
                       {:extension-id "ext-a" :canvas-id "c1"})))))

(deftest test-open-canvases-initialized-from-resume
  (testing "session.resume populates open-canvases snapshot (upstream PR #1604)"
    (mock/set-resume-response-extras!
     *mock-server*
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"
                      :title "Hello"}]})
    ;; create-session does NOT populate open-canvases
    (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (is (= [] (sdk/open-canvases created)))
      (let [session-id (sdk/session-id created)
            resumed (sdk/resume-session *test-client* session-id
                                        {:on-permission-request sdk/approve-all})
            canvases (sdk/open-canvases resumed)]
        (is (= 1 (count canvases)))
        (is (= "i1" (:instance-id (first canvases))))
        (is (= "ext-a" (:extension-id (first canvases))))
        (is (= "c1" (:canvas-id (first canvases))))
        (is (= "Hello" (:title (first canvases))))))))

(deftest test-open-canvases-resume-sanitizes-invalid-entries
  (testing "session.resume openCanvases drops only structurally-invalid entries"
    (mock/set-resume-response-extras!
     *mock-server*
     ;; Two valid entries (3 required ids; extra optional fields), then three
     ;; structurally-invalid ones: missing :canvasId, blank :instanceId, and a
     ;; non-map. Under v1.0.4 the snapshot shape requires only the three ids;
     ;; :reopen/:availability were removed from OpenCanvasInstance entirely.
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"}
                     {:instanceId "i2"
                      :extensionId "ext-a"
                      :canvasId "c2"
                      :title "Second"}
                     {:instanceId "i3"
                      :extensionId "ext-a"}
                     {:instanceId ""
                      :extensionId "ext-a"
                      :canvasId "c4"}
                     "not-a-map"]})
    (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id created)
          resumed (sdk/resume-session *test-client* session-id
                                      {:on-permission-request sdk/approve-all})
          canvases (sdk/open-canvases resumed)]
      (is (= 2 (count canvases)) "both fully-valid entries are kept")
      (is (= ["i1" "i2"] (mapv :instance-id canvases))))))

(deftest test-create-session-no-open-canvases-snapshot
  (testing "session.create does NOT populate open-canvases (resume-only)"
    (mock/set-resume-response-extras!
     *mock-server*
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"
                      :reopen false
                      :availability "ready"}]})
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})]
      (is (= [] (sdk/open-canvases session))
          "create response is ignored even if mock injects openCanvases"))))

(deftest test-canvas-opened-upserts-snapshot
  (testing "session.canvas.opened upserts by instanceId"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)]
      (try
        (is (= [] (sdk/open-canvases session)))
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i1"
                                   :extensionId "ext-a"
                                   :canvasId "c1"
                                   :reopen false
                                   :availability "ready"
                                   :status "loading"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session))))
        (is (= "loading" (:status (first (sdk/open-canvases session)))))
        ;; Re-emit with same instanceId, different status — replace in place
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i1"
                                   :extensionId "ext-a"
                                   :canvasId "c1"
                                   :reopen false
                                   :availability "ready"
                                   :status "ready"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session))) "length unchanged on upsert")
        (is (= "ready" (:status (first (sdk/open-canvases session)))) "entry updated in place")
        ;; Append a new instance
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i2"
                                   :extensionId "ext-a"
                                   :canvasId "c2"
                                   :reopen false
                                   :availability "ready"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 2 (count (sdk/open-canvases session))))
        (is (= ["i1" "i2"] (mapv :instance-id (sdk/open-canvases session))))
        (finally
          (sdk/unsubscribe-events! session events-ch))))))

(deftest test-canvas-closed-removes-from-snapshot
  (testing "session.canvas.closed removes by instanceId (upstream PR #1604)"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)]
      (try
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i1"
                                   :extensionId "ext-a"
                                   :canvasId "c1"
                                   :reopen false
                                   :availability "ready"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session))))
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.closed"
                                  {:instanceId "i1"
                                   :extensionId "ext-a"
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.closed 1000)
        (is (= [] (sdk/open-canvases session)))
        ;; Idempotent — closing absent instanceId is a no-op
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.closed"
                                  {:instanceId "i-missing"
                                   :extensionId "ext-a"
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.closed 1000)
        (is (= [] (sdk/open-canvases session)))
        (finally
          (sdk/unsubscribe-events! session events-ch))))))

(deftest test-canvas-events-malformed-payload-no-op
  (testing "missing/blank instanceId on opened/closed warns and no-ops"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)]
      (try
        ;; Seed one entry so we can verify no mutation
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i1"
                                   :extensionId "ext-a"
                                   :canvasId "c1"
                                   :reopen false
                                   :availability "ready"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session))))
        ;; Closed with empty instanceId — no-op
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.closed"
                                  {:instanceId ""
                                   :extensionId "ext-a"
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.closed 1000)
        (is (= 1 (count (sdk/open-canvases session))))
        ;; Opened with blank instanceId — no-op
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId ""
                                   :extensionId "ext-a"
                                   :canvasId "c1"
                                   :reopen false
                                   :availability "ready"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session))))
        ;; Closed with non-string instanceId (numeric) — no-op (matches strict
        ;; ::instance-id non-blank-string spec used elsewhere)
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.closed"
                                  {:instanceId 42
                                   :extensionId "ext-a"
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.closed 1000)
        (is (= 1 (count (sdk/open-canvases session))))
        (finally
          (sdk/unsubscribe-events! session events-ch))))))

(deftest test-schedule-data-cron-and-at
  (testing "schedule_created accepts cron-only payload (no :interval-ms)"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :prompt "ping" :cron "0 * * * *" :tz "UTC"})))
  (testing "schedule_created accepts :at one-shot payload"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :prompt "ping" :at 1700000000000})))
  (testing "schedule_created still requires :id"
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:prompt "ping" :cron "0 * * * *"})))))

(deftest test-events-file-size-bytes-on-resume-and-shutdown
  (testing ":events-file-size-bytes accepted on resume idiom data"
    (is (s/valid? :github.copilot-sdk.specs/session.resume-data
                  {:event-count 0 :events-file-size-bytes 1024})))
  (testing ":events-file-size-bytes accepted on shutdown idiom data"
    (is (s/valid? :github.copilot-sdk.specs/session.shutdown-data
                  {:shutdown-type "routine"
                   :total-api-duration-ms 10
                   :session-start-time 0
                   :code-changes {}
                   :model-metrics {}
                   :events-file-size-bytes 1024}))))

(deftest test-api-call-id-on-assistant-message
  (testing ":api-call-id accepted on assistant.message idiom data"
    (is (s/valid? :github.copilot-sdk.specs/assistant.message-data
                  {:message-id "m1" :content "hi" :api-call-id "call_1"}))))

(deftest test-temporary-on-hook-progress
  (testing ":temporary accepted on hook.progress idiom data"
    (is (s/valid? :github.copilot-sdk.specs/hook.progress-data
                  {:message "in progress" :temporary true}))
    (is (s/valid? :github.copilot-sdk.specs/hook.progress-data
                  {:message "in progress" :temporary false}))))

(deftest test-canvas-opened-event-input-preserved-verbatim
  (testing "session.canvas.opened :input keys round-trip without kebab-casing"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)]
      (try
        ;; Caller-defined opaque input map with snake_case AND camelCase AND
        ;; nested keys — none should be re-cased by wire->clj.
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i1"
                                   :extensionId "ext-a"
                                   :canvasId "c1"
                                   :reopen false
                                   :availability "ready"
                                   :input {:user_id 42
                                           :myKey "v"
                                           :nested {:secondKey "v2"
                                                    :third_key 3}}})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (let [snap (first (sdk/open-canvases session))
              input (:input snap)]
          (is (some? snap) "snapshot was upserted")
          (is (= 42 (:user_id input)) "snake_case caller key preserved")
          (is (= "v" (:myKey input)) "camelCase caller key preserved")
          (let [nested (:nested input)]
            (is (= "v2" (:secondKey nested)) "nested camelCase preserved")
            (is (= 3 (:third_key nested)) "nested snake_case preserved")))
        (finally
          (sdk/unsubscribe-events! session events-ch))))))

(deftest test-resume-response-open-canvases-input-preserved
  (testing "session.resume openCanvases[].input keys round-trip verbatim"
    (mock/set-resume-response-extras!
     *mock-server*
     {:openCanvases [{:instanceId "i1"
                      :extensionId "ext-a"
                      :canvasId "c1"
                      :reopen false
                      :availability "ready"
                      :input {:user_id 7
                              :nested {:keepThis "v"
                                       :and_this 1}}}]})
    (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id created)
          resumed (sdk/resume-session *test-client* session-id
                                      {:on-permission-request sdk/approve-all})
          canvases (sdk/open-canvases resumed)]
      (is (= 1 (count canvases)))
      (let [input (-> canvases first :input)]
        (is (= 7 (:user_id input)) "top-level snake_case preserved")
        (is (= "v" (get-in input [:nested :keepThis])) "nested camelCase preserved")
        (is (= 1 (get-in input [:nested :and_this])) "nested snake_case preserved")))
    ;; Cleanup: clear the stub so subsequent tests see a clean resume response.
    (mock/set-resume-response-extras! *mock-server* {})))

(deftest test-canvas-upsert-strict-validation
  (testing "upsert requires the three ids (matches v1.0.4 isOpenCanvasInstance)"
    (let [session (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          session-id (sdk/session-id session)
          events-ch (sdk/subscribe-events session)]
      (try
        ;; Seed a valid entry first (3 required ids only)
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i1"
                                   :extensionId "ext-a"
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session))))
        ;; Missing :canvasId — guard rejects, no upsert
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i2"
                                   :extensionId "ext-a"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session)))
            "missing :canvas-id rejected")
        ;; Blank :extensionId — guard rejects
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i3"
                                   :extensionId ""
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session)))
            "blank :extension-id rejected")
        ;; Non-string :instanceId — guard rejects
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId 42
                                   :extensionId "ext-a"
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 1 (count (sdk/open-canvases session)))
            ":instance-id must be a non-blank string")
        ;; Sanity: a fully valid second entry is still admitted
        (mock/send-session-event! *mock-server* session-id
                                  "session.canvas.opened"
                                  {:instanceId "i2"
                                   :extensionId "ext-a"
                                   :canvasId "c1"})
        (await-event-type! events-ch :copilot/session.canvas.opened 1000)
        (is (= 2 (count (sdk/open-canvases session))) "valid entry admitted")
        (finally
          (sdk/unsubscribe-events! session events-ch))))))

(deftest test-resume-config-open-canvases-outbound-wire
  (testing "resume-session :open-canvases sends camelCase wire shape with verbatim :input"
    (let [captured (atom nil)
          hook (fn [method params]
                 (when (= "session.resume" method)
                   (reset! captured params))
                 nil)]
      (mock/set-request-hook! *mock-server* hook)
      (try
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all
            :open-canvases [{:instance-id "i1"
                             :extension-id "ext-a"
                             :canvas-id "c1"
                             :extension-name "Ext A"
                             :title "Hello"
                             :status "loading"
                             :url "https://example.test/c1"
                             :input {:user_id 99
                                     :nested {:keepCase "v"
                                              :snake_field 1}}}]})
          (let [params @captured]
            (is (some? params) "session.resume request was captured")
            (let [oc (first (:openCanvases params))]
              (is (some? oc) "openCanvases array was sent")
              (is (= "i1"   (:instanceId oc)))
              (is (= "ext-a" (:extensionId oc)))
              (is (= "c1"   (:canvasId oc)))
              (is (= "Ext A" (:extensionName oc)))
              (is (= "Hello" (:title oc)))
              (is (= "loading" (:status oc)))
              (is (= "https://example.test/c1" (:url oc)))
              ;; Input keys must NOT have been camelCased.
              (let [input (:input oc)]
                ;; Keys may be strings (preserved through clj->wire) or
                ;; symbols of the original name; verify by string lookup.
                (is (= 99 (or (get input "user_id") (get input :user_id))))
                (let [nested (or (get input "nested") (get input :nested))]
                  (is (= "v" (or (get nested "keepCase") (get nested :keepCase))))
                  (is (= 1   (or (get nested "snake_field") (get nested :snake_field)))))))))
        (finally
          (mock/set-request-hook! *mock-server* nil))))))

(deftest test-resume-config-open-canvases-namespaced-input-keys
  (testing "Namespaced keyword keys in :input preserve their full ns/name when stringified"
    (let [captured (atom nil)
          hook (fn [method params]
                 (when (= "session.resume" method)
                   (reset! captured params))
                 nil)]
      (mock/set-request-hook! *mock-server* hook)
      (try
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all
            :open-canvases [{:instance-id "i1"
                             :extension-id "ext-a"
                             :canvas-id "c1"
                             :reopen false
                             :availability "ready"
                             :input {:my.app/user_id 42
                                     :nested {:other.ns/key "v"}}}]})
          (let [params @captured
                oc (first (:openCanvases params))
                input (:input oc)]
            (is (some? input))
            ;; Namespaced keys must be preserved as "ns/name", NOT silently
            ;; truncated to just the local name. Keys may surface as strings
            ;; or as preserved keywords through clj->wire — check both.
            (is (= 42 (or (get input "my.app/user_id")
                          (get input :my.app/user_id)))
                "namespaced keyword key preserved as full ns/name")
            (is (nil? (get input "user_id")) "local-name-only must not appear")
            (is (nil? (get input :user_id)))
            (let [nested (or (get input "nested") (get input :nested))]
              (is (= "v" (or (get nested "other.ns/key")
                             (get nested :other.ns/key)))
                  "nested namespaced key also preserved"))))
        (finally
          (mock/set-request-hook! *mock-server* nil))))))

(deftest test-resume-config-open-canvases-explicit-empty-and-omitted
  (testing "resume-session forwards explicit empty :open-canvases (parity with upstream config.openCanvases) and omits the param when the key is absent"
    (let [captured (atom nil)
          hook (fn [method params]
                 (when (= "session.resume" method)
                   (reset! captured params))
                 nil)]
      (mock/set-request-hook! *mock-server* hook)
      (try
        ;; Explicit empty vector — must be sent as []
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all
            :open-canvases []})
          (let [params @captured]
            (is (some? params))
            (is (contains? params :openCanvases) "explicit empty vector forwarded as openCanvases param")
            (is (= [] (:openCanvases params)))))
        (reset! captured nil)
        ;; Key absent — param must be omitted entirely
        (let [created (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
              session-id (sdk/session-id created)]
          (sdk/resume-session
           *test-client* session-id
           {:on-permission-request sdk/approve-all})
          (let [params @captured]
            (is (some? params))
            (is (not (contains? params :openCanvases)) "missing :open-canvases key omits openCanvases param")))
        (finally
          (mock/set-request-hook! *mock-server* nil))))))

(deftest test-schedule-at-spec-rejects-non-positive
  (testing "::at requires a positive integer (epoch ms)"
    (is (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                  {:id 1 :prompt "p" :at 1700000000000}))
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :prompt "p" :at 0}))
        "zero is not a valid epoch-ms timestamp")
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :prompt "p" :at -1}))
        "negative is rejected")
    (is (not (s/valid? :github.copilot-sdk.specs/session.schedule_created-data
                       {:id 1 :prompt "p" :at 1.5}))
        "non-integer (double) is rejected")))

(deftest test-memory-config-on-wire
  (testing ":memory is forwarded to session.create as {:enabled bool}"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :memory {:enabled true}})
          create-params (get @seen "session.create")]
      (is (= {:enabled true} (:memory create-params)))))

  (testing ":memory {:enabled false} is forwarded (not stripped)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :memory {:enabled false}})
          create-params (get @seen "session.create")]
      (is (= {:enabled false} (:memory create-params)))))

  (testing ":memory is omitted from session.create when unset (never null)"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client* {:on-permission-request sdk/approve-all})
          create-params (get @seen "session.create")]
      (is (not (contains? create-params :memory)))))

  (testing ":memory is forwarded to session.resume (parity with create)"
    (let [seen (atom {})
          session-id (sdk/session-id (sdk/create-session *test-client* {:on-permission-request sdk/approve-all}))
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.resume"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/resume-session *test-client* session-id
                                {:on-permission-request sdk/approve-all
                                 :memory {:enabled true}})
          resume-params (get @seen "session.resume")]
      (is (= {:enabled true} (:memory resume-params))))))

(deftest test-mcp-defer-tools-on-wire
  (testing ":mcp-defer-tools keyword is forwarded as deferTools string"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :mcp-servers {"srv-http" {:mcp-server-type :http
                                                           :mcp-url "https://mcp.test"
                                                           :mcp-tools ["*"]
                                                           :mcp-defer-tools :auto}
                                               "srv-stdio" {:mcp-command "node"
                                                            :mcp-args ["server.js"]
                                                            :mcp-tools ["*"]
                                                            :mcp-defer-tools :never}}})
          create-params (get @seen "session.create")]
      (is (= "auto" (get-in create-params [:mcpServers :srv-http :deferTools])))
      (is (= "never" (get-in create-params [:mcpServers :srv-stdio :deferTools])))))

  (testing "deferTools is omitted when :mcp-defer-tools unset"
    (let [seen (atom {})
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (when (#{"session.create"} method)
                                        (swap! seen assoc method params))))
          _ (sdk/create-session *test-client*
                                {:on-permission-request sdk/approve-all
                                 :mcp-servers {"srv-http" {:mcp-server-type :http
                                                           :mcp-url "https://mcp.test"
                                                           :mcp-tools ["*"]}}})
          create-params (get @seen "session.create")]
      (is (not (contains? (get-in create-params [:mcpServers :srv-http]) :deferTools))))))
