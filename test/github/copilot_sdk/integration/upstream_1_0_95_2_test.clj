(ns github.copilot-sdk.integration.upstream-1-0-95-2-test
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.coerce :as coerce]
            [github.copilot-sdk.generated.event-specs :as gen]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! await-value! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs]))

(use-fixtures :each with-mock-server)

(defn- read-result! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 15000)])]
    (when-not (= port channel)
      (throw (ex-info "Timed out reading SDK result" {})))
    (when (instance? Throwable value)
      (throw value))
    value))

(defn- open-session [operation config]
  (case operation
    :create (sdk/create-session *test-client* config)
    :create-channel (read-result! (sdk/<create-session *test-client* config))
    (let [seed (sdk/create-session *test-client* {})
          session-id (sdk/session-id seed)]
      (sdk/disconnect! seed)
      (case operation
        :resume (sdk/resume-session *test-client* session-id config)
        :resume-channel (read-result! (sdk/<resume-session *test-client* session-id config))
        :join (:session
               (with-redefs-fn
                 {#'client/foreground-session-id (constantly session-id)
                  #'client/client (constantly *test-client*)}
                 #(sdk/join-session config)))))))

(deftest managed-policy-options-preserve-omission-false-and-empty-in-every-builder
  (doseq [operation [:create :create-channel :resume :resume-channel :join]
          [config expected]
          [[{} {}]
           [{:enforce-managed-model-defaults? false
             :managed-settings {:permissions {:disable-assisted-permissions-mode? false
                                              :limit-to []}}}
            {:enforceManagedModelDefaults false
             :managedSettings {:permissions {:disableAssistedPermissionsMode false
                                             :limitTo []}}}]
           [{:enforce-managed-model-defaults? true
             :managed-settings {:permissions {:disable-assisted-permissions-mode? true
                                              :limit-to ["Domain(api.github.com)"
                                                         "Domain(*.example.com)"]}}}
            {:enforceManagedModelDefaults true
             :managedSettings {:permissions {:disableAssistedPermissionsMode true
                                             :limitTo ["Domain(api.github.com)"
                                                       "Domain(*.example.com)"]}}}]
           [{:managed-settings {:permissions {}}}
            {:managedSettings {:permissions {}}}]]]
    (testing (str operation " " config)
      (let [requests (atom [])]
        (mock/set-request-hook! *mock-server*
                                (fn [method params]
                                  (swap! requests conj [method params])))
        (let [copilot-session (open-session operation config)]
          (try
            (let [params (second
                          (last (filter #(contains? #{"session.create" "session.resume"} (first %))
                                        @requests)))]
              (is (= (select-keys params [:enforceManagedModelDefaults :managedSettings])
                     expected)))
            (is (nil? (#'client/build-session-options-update-patch *test-client* config)))
            (finally
              (sdk/disconnect! copilot-session))))))))

(deftest new-config-fields-reject-null-and-invalid-values
  (doseq [spec [::specs/session-config ::specs/resume-session-config ::specs/join-session-config]
          config [{:enforce-managed-model-defaults? nil}
                  {:enforce-managed-model-defaults? "true"}
                  {:managed-settings {:permissions {:disable-assisted-permissions-mode? nil}}}
                  {:managed-settings {:permissions {:disable-assisted-permissions-mode? 1}}}
                  {:managed-settings {:permissions {:limit-to nil}}}
                  {:managed-settings {:permissions {:limit-to [nil]}}}
                  {:managed-settings {:permissions {:limit-to "Domain(api.github.com)"}}}]]
    (is (not (s/valid? spec config)) (str spec " " config))))

(deftest provider-defined-auto-tier-identities-survive-every-builder
  (doseq [operation [:create :create-channel :resume :resume-channel :join]
          tier [::absent ::empty :efficiency :fast :premium-v2 :Vendor/Premium_v2]]
    (let [requests (atom [])]
      (mock/set-request-hook! *mock-server*
                              (fn [method params]
                                (when (contains? #{"session.create" "session.resume"} method)
                                  (swap! requests conj params))))
      (let [config (case tier
                     ::absent {:model "auto"}
                     ::empty {:model "auto" :capi {}}
                     {:model "auto" :capi {:auto-tier tier}})
            copilot-session (open-session operation config)]
        (try
          (case tier
            ::absent (is (not (contains? (last @requests) :capi)))
            ::empty (is (= (:capi (last @requests)) {}))
            (is (= (get-in (last @requests) [:capi :autoTier])
                   (subs (str tier) 1))))
          (finally
            (sdk/disconnect! copilot-session))))))
  (doseq [value [nil false true 42 "premium-v2"
                 (keyword "") (keyword "with space") (keyword "with\nnewline")
                 (keyword (str "with" (char 0x00a0) "space"))
                 (keyword (str "with" (char 0xfeff) "space"))]]
    (is (not (s/valid? ::specs/capi {:auto-tier value}))))
  (doseq [value [nil :efficiency :premium-v2 :Vendor/Premium_v2]]
    (is (= (coerce/auto-tier-string->keyword (coerce/auto-tier-keyword->string value))
           value))))

(def ^:private event-cases
  [{:type :copilot/session.start
    :spec ::specs/session.start-data
    :base {:session-id "source"}
    :wire {:sessionId "source" :autoTier "Vendor/Premium_v2" :providerId ""
           :autoTierManaged false :contextTierManaged true :reasoningEffortManaged false}
    :data {:session-id "source" :auto-tier :Vendor/Premium_v2 :provider-id ""
           :auto-tier-managed false :context-tier-managed true :reasoning-effort-managed false}
    :invalid [{:auto-tier nil} {:provider-id nil} {:auto-tier-managed nil}
              {:context-tier-managed 1} {:reasoning-effort-managed "false"}]}
   {:type :copilot/session.resume
    :spec ::specs/session.resume-data
    :base {:event-count 0}
    :wire {:eventCount 0 :autoTier "premium-v2" :providerId "Provider_A"
           :autoTierManaged true :contextTierManaged false :reasoningEffortManaged true}
    :data {:event-count 0 :auto-tier :premium-v2 :provider-id "Provider_A"
           :auto-tier-managed true :context-tier-managed false :reasoning-effort-managed true}
    :invalid [{:provider-id nil} {:auto-tier-managed nil}
              {:context-tier-managed "false"} {:reasoning-effort-managed nil}]}
   {:type :copilot/session.model_change
    :spec ::specs/session.model_change-data
    :base {:new-model "model"}
    :wire {:newModel "model" :providerId "Provider_A" :previousProviderId ""
           :autoTierManaged false :contextTierManaged false :reasoningEffortManaged false}
    :data {:new-model "model" :provider-id "Provider_A" :previous-provider-id ""
           :auto-tier-managed false :context-tier-managed false :reasoning-effort-managed false}
    :invalid [{:provider-id nil} {:previous-provider-id nil}
              {:auto-tier-managed nil} {:context-tier-managed nil} {:reasoning-effort-managed nil}]}
   {:type :copilot/assistant.message
    :spec ::specs/assistant.message-data
    :base {:message-id "message" :content ""}
    :wire {:messageId "message" :content "" :providerId "Provider_A"}
    :data {:message-id "message" :content "" :provider-id "Provider_A"}
    :invalid [{:provider-id nil} {:provider-id 1}]}
   {:type :copilot/assistant.usage
    :spec ::specs/assistant.usage-data
    :base {:model "model"}
    :wire {:model "model" :modelDisplayName "" :aiCreditsStatus "partial"
           :accounting {:sourceSessionId "Source_Session" :sequence 1 :usageId ""}
           :requestBodyBytes 0 :websocketFallbackAfterMs 0 :websocketFallbackReason "transport_failed"}
    :data {:model "model" :model-display-name "" :ai-credits-status "partial"
           :accounting {:source-session-id "Source_Session" :sequence 1 :usage-id ""}
           :request-body-bytes 0 :websocket-fallback-after-ms 0 :websocket-fallback-reason "transport_failed"}
    :invalid [{:model-display-name nil} {:ai-credits-status "unknown"} {:accounting {}}
              {:accounting {:source-session-id "s" :sequence 1}}
              {:accounting {:source-session-id nil :sequence 1 :usage-id ""}}
              {:accounting {:source-session-id "s" :sequence 0 :usage-id ""}}
              {:accounting {:source-session-id "s" :sequence 1.5 :usage-id ""}}
              {:accounting {:source-session-id "s" :sequence 9007199254740992 :usage-id ""}}
              {:request-body-bytes nil} {:request-body-bytes -1} {:request-body-bytes 0.5}
              {:websocket-fallback-after-ms "0"} {:websocket-fallback-after-ms 0.5}
              {:websocket-fallback-reason "unknown"}]}
   {:type :copilot/model.call_failure
    :spec ::specs/model.call_failure-data
    :base {:source "top_level"}
    :wire {:source "top_level" :requestBodyBytes 1 :retryAttempt 0
           :websocketFallbackAfterMs 1 :websocketFallbackReason "connect_failed"
           :requestFingerprint {:imagePartCount 0 :imagePartsMissingMediaType 0 :messageCount 1
                                :namelessToolCallCount 0 :toolCallCount 0 :toolResultMessageCount 0
                                :encryptedContentBytes 0 :imageBytes 0 :reasoningItemCount 0}}
    :data {:source "top_level" :request-body-bytes 1 :retry-attempt 0
           :websocket-fallback-after-ms 1 :websocket-fallback-reason "connect_failed"
           :request-fingerprint {:image-part-count 0 :image-parts-missing-media-type 0 :message-count 1
                                 :nameless-tool-call-count 0 :tool-call-count 0 :tool-result-message-count 0
                                 :encrypted-content-bytes 0 :image-bytes 0 :reasoning-item-count 0}}
    :invalid [{:request-body-bytes nil} {:retry-attempt "0"} {:retry-attempt -1} {:retry-attempt 0.5}
              {:websocket-fallback-after-ms nil}
              {:websocket-fallback-reason nil} {:request-fingerprint {:image-bytes "0"}}]}
   {:type :copilot/session.compaction_complete
    :spec ::specs/session.compaction_complete-data
    :base {:success true}
    :wire {:success true :compactionTokensUsed {:aiCreditsStatus "unavailable" :modelDisplayName ""}}
    :data {:success true :compaction-tokens-used {:ai-credits-status "unavailable" :model-display-name ""}}
    :invalid [{:compaction-tokens-used nil}
              {:compaction-tokens-used {:ai-credits-status nil}}
              {:compaction-tokens-used {:model-display-name nil}}]}
   {:type :copilot/session.shutdown
    :spec ::specs/session.shutdown-data
    :base {:shutdown-type "routine" :total-api-duration-ms 0 :session-start-time 0
           :code-changes {} :model-metrics {}}
    :wire {:shutdownType "routine" :totalApiDurationMs 0 :sessionStartTime 0
           :codeChanges {} :modelMetrics {} :usageAccountingWatermarks {:Source_Session 0 :Source.Session 2}}
    :data {:shutdown-type "routine" :total-api-duration-ms 0 :session-start-time 0
           :code-changes {} :model-metrics {} :usage-accounting-watermarks {:Source_Session 0 :Source.Session 2}}
    :invalid [{:usage-accounting-watermarks nil} {:usage-accounting-watermarks {:Source_Session nil}}
              {:usage-accounting-watermarks {:Source_Session -1}}
              {:usage-accounting-watermarks {:Source_Session 0.5}}]}
   {:type :copilot/tool.execution_start
    :spec ::specs/tool.execution_start-data
    :base {:tool-call-id "tool" :tool-name "mcp"}
    :wire {:toolCallId "tool" :toolName "mcp" :mcpConfigSource "account"}
    :data {:tool-call-id "tool" :tool-name "mcp" :mcp-config-source "account"}
    :invalid [{:mcp-config-source nil}]}
   {:type :copilot/session.usage_checkpoint
    :spec ::specs/session.usage_checkpoint-data
    :base {:total-nano-aiu 0}
    :wire {:totalNanoAiu 0 :usageAccountingWatermarks {:Source_Session 0 :Source.Session 2}}
    :data {:total-nano-aiu 0 :usage-accounting-watermarks {:Source_Session 0 :Source.Session 2}}
    :invalid [{:usage-accounting-watermarks nil} {:usage-accounting-watermarks {:Source_Session -1}}]}
   {:type :copilot/session.mcp_servers_loaded
    :spec ::specs/session.mcp_servers_loaded-data
    :base {:servers []}
    :wire {:servers [{:name "mcp" :status "connected" :source "account"}]}
    :data {:servers [{:name "mcp" :status "connected" :source "account"}]}
    :invalid [{:servers [{:name "mcp" :status "connected" :source nil}]}]}])

(deftest stable-event-fields-have-optional-idiom-contracts
  (doseq [{:keys [type spec base data invalid]} event-cases]
    (testing (str type)
      (is (s/valid? spec base))
      (is (s/valid? spec data))
      (is (s/valid? spec (assoc data :future-field true)))
      (doseq [patch invalid]
        (is (not (s/valid? spec (merge data patch))) (str patch))))))

(deftest premium-request-accounting-preserves-fractional-costs
  (doseq [[wire-spec idiom-spec base]
          [[::gen/session.usage_checkpoint-data ::specs/session.usage_checkpoint-data
            {:total-nano-aiu 0}]
           [::gen/session.shutdown-data ::specs/session.shutdown-data
            {:shutdown-type "routine" :total-api-duration-ms 0 :session-start-time 0
             :code-changes {:lines-added 0 :lines-removed 0 :files-modified []}
             :model-metrics {}}]]]
    (doseq [value [0 0.5 1.25]
            spec [wire-spec idiom-spec]]
      (is (s/valid? spec (assoc base :total-premium-requests value))))
    (doseq [value [nil -0.1 "0.5" true ##NaN ##Inf]
            spec [wire-spec idiom-spec]]
      (is (not (s/valid? spec (assoc base :total-premium-requests value)))))))

(deftest stable-event-fields-round-trip-through-live-and-history-paths
  (let [started (promise)
        copilot-session
        (sdk/create-session *test-client*
                            {:on-event (fn [event]
                                         (when (= (:type event) :copilot/session.start)
                                           (deliver started true)))})
        session-id (sdk/session-id copilot-session)]
    (await-value! started "initial session.start" 5000)
    (let [events (sdk/subscribe-events copilot-session)]
      (try
        (doseq [{:keys [type wire data]} event-cases]
          (mock/send-session-event! *mock-server* session-id type wire)
          (is (= (:data (await-event-type! events type 5000)) data) (str type))
          (mock/set-session-messages! *mock-server* session-id [{:type (name type) :data wire}])
          (is (= (:data (first (sdk/get-messages copilot-session))) data) (str type)))
        (finally
          (sdk/unsubscribe-events! copilot-session events)
          (sdk/disconnect! copilot-session))))))

(deftest malformed-auto-tier-events-retain-raw-values-on-coercion-failure
  (let [started (promise)
        copilot-session
        (sdk/create-session *test-client*
                            {:on-event (fn [event]
                                         (when (= (:type event) :copilot/session.start)
                                           (deliver started true)))})
        session-id (sdk/session-id copilot-session)]
    (await-value! started "initial session.start" 5000)
    (let [events (sdk/subscribe-events copilot-session)]
      (try
        (doseq [[type base] [[:copilot/session.start {:sessionId session-id}]
                             [:copilot/session.resume {:eventCount 0}]]
                tier ["" "with space" (str "tier" (char 0x00a0) "suffix") "premium-v2"]
                :let [wire (assoc base :autoTier tier)
                      expected (if (= tier "premium-v2") :premium-v2 tier)]]
          (mock/send-session-event! *mock-server* session-id type wire)
          (is (= (get-in (await-event-type! events type 5000) [:data :auto-tier]) expected))
          (mock/set-session-messages! *mock-server* session-id [{:type (name type) :data wire}])
          (is (= (get-in (first (sdk/get-messages copilot-session)) [:data :auto-tier]) expected)))
        (finally
          (sdk/unsubscribe-events! copilot-session events)
          (sdk/disconnect! copilot-session))))))

(def ^:private write-previews
  [[{:after {:path "/new" :content ""}}]
   [{:before {:path "/old" :content ""}}]
   [{:before {:path "/before" :content "before"}
     :after {:path "/after" :content "after"}}]
   [{:before {:path "/source" :content "source"}}
    {:before {:path "/destination" :content "destination"}
     :after {:path "/destination" :content "source"}}]])

(deftest write-permission-previews-validate-complete-snapshots
  (let [callback {:permission-kind :write :file-name "/file" :diff "" :intention ""}
        event {:request-id "request"
               :permission-request {:kind "write" :file-name "/file" :diff "" :intention ""}}]
    (doseq [previews write-previews]
      (is (s/valid? ::specs/permission-request (assoc callback :file-edits previews)))
      (is (s/valid? ::specs/permission.requested-data
                    (-> event
                        (assoc-in [:permission-request :file-edits] previews)
                        (assoc :prompt-request (assoc (:permission-request event) :file-edits previews))))))
    (doseq [previews [nil [] [{}] [{:before nil}] [{:before {:path "/file"}}]
                      [{:after {:path "/file" :content nil}}] [{:after {:path nil :content ""}}]]]
      (is (not (s/valid? ::specs/permission-request (assoc callback :file-edits previews))))
      (doseq [field [:permission-request :prompt-request]]
        (is (not (s/valid? ::specs/permission.requested-data
                           (assoc event field (assoc (:permission-request event) :file-edits previews)))))))))

(deftest write-permission-previews-reach-handlers-and-both-event-paths
  (doseq [previews write-previews]
    (let [handled (promise)
          copilot-session (sdk/create-session
                           *test-client*
                           {:on-permission-request (fn [request _]
                                                     (deliver handled request)
                                                     {:kind :approve-once})})
          session-id (sdk/session-id copilot-session)
          events (sdk/subscribe-events copilot-session)
          request {:kind "write" :fileName "/file" :diff "" :intention "" :fileEdits previews}
          wire {:requestId "write-preview" :permissionRequest request :promptRequest request}]
      (try
        (mock/send-v3-broadcast-event! *mock-server* session-id :copilot/permission.requested wire)
        (let [request (await-value! handled "write permission callback" 5000)
              data (:data (await-event-type! events :copilot/permission.requested 5000))]
          (is (= (:kind request) "write"))
          (is (= (:file-edits request) previews))
          (doseq [field [:permission-request :prompt-request]]
            (is (= (get-in data [field :file-edits]) previews)))
          (mock/set-session-messages! *mock-server* session-id
                                      [{:type "permission.requested" :data wire}])
          (is (= (:data (first (sdk/get-messages copilot-session))) data)))
        (finally
          (sdk/unsubscribe-events! copilot-session events)
          (sdk/disconnect! copilot-session))))))

(deftest retired-session-event-subscriptions-fail-explicitly
  (doseq [retire! [sdk/disconnect! #(sdk/force-stop! (:client %))]
          :let [copilot-session (sdk/create-session *test-client* {})]]
    (retire! copilot-session)
    (doseq [subscribe [sdk/subscribe-events session/events->chan session/events]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Session has been disconnected"
                            (subscribe copilot-session))))))

(deftest event-subscriptions-reject-replaced-session-handles
  (doseq [disconnect-first? [false true]]
    (let [original (sdk/create-session *test-client* {})
          session-id (sdk/session-id original)]
      (when disconnect-first?
        (sdk/disconnect! original))
      (let [replacement (sdk/resume-session *test-client* session-id {})
            events (sdk/subscribe-events replacement)]
        (try
          (is (not (identical? (session/registration-token original)
                               (session/registration-token replacement))))
          (doseq [subscribe [sdk/subscribe-events session/events->chan session/events]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Session has been disconnected"
                                  (subscribe original))))
          (mock/send-session-event! *mock-server* session-id :copilot/session.info
                                    {:infoType "registration" :message "replacement"})
          (is (= (get-in (await-event-type! events :copilot/session.info 5000) [:data :message])
                 "replacement"))
          (finally
            (sdk/unsubscribe-events! replacement events)
            (sdk/disconnect! replacement)))))))

(deftest failed-resume-preserves-original-subscription-admission
  (let [original (sdk/create-session *test-client* {})
        session-id (sdk/session-id original)]
    (try
      (mock/set-request-hook!
       *mock-server*
       (fn [method _]
         (when (= method "session.resume")
           (throw (ex-info "resume rejected" {:code -32000})))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"resume rejected"
                            (sdk/resume-session *test-client* session-id {})))
      (mock/set-request-hook! *mock-server* nil)
      (let [events (sdk/subscribe-events original)]
        (try
          (mock/send-session-event! *mock-server* session-id :copilot/session.info
                                    {:infoType "registration" :message "original"})
          (is (= (get-in (await-event-type! events :copilot/session.info 5000) [:data :message])
                 "original"))
          (finally
            (sdk/unsubscribe-events! original events))))
      (finally
        (mock/set-request-hook! *mock-server* nil)
        (sdk/disconnect! original)))))

(deftest subscription-admission-racing-retirement-does-not-leak-a-channel
  (doseq [subscribe [sdk/subscribe-events session/events->chan]]
    (let [copilot-session (sdk/create-session *test-client* {})
          session-id (sdk/session-id copilot-session)
          observer (sdk/subscribe-events copilot-session)
          original (session/events copilot-session)
          entered (promise)
          release (promise)
          pending-channel (atom nil)
          delayed-mult
          (reify async/Mult
            (tap* [_ channel close?]
              (reset! pending-channel channel)
              (deliver entered true)
              @release
              (async/tap original channel close?))
            (untap* [_ channel] (async/untap original channel))
            (untap-all* [_] (async/untap-all original)))
          _ (swap! (:state *test-client*) assoc-in [:session-io session-id :event-mult] delayed-mult)
          subscribing (future
                        (try (subscribe copilot-session)
                             (catch Throwable error error)))]
      (try
        (await-value! entered "subscription admission" 5000)
        (sdk/disconnect! copilot-session)
        (read-result! (async/into [] observer))
        (deliver release true)
        (let [result (await-value! subscribing "retired subscription" 5000)]
          (is (instance? clojure.lang.ExceptionInfo result))
          (is (= (ex-message result) "Session has been disconnected"))
          (is (async-protocols/closed? @pending-channel)))
        (finally
          (deliver release true)
          (await-value! subscribing "subscription worker exit" 5000)
          (sdk/unsubscribe-events! copilot-session observer)
          (sdk/disconnect! copilot-session))))))

(deftest experimental-additions-stay-outside-the-curated-api
  (doseq [spec [::specs/session-config ::specs/resume-session-config ::specs/join-session-config]
          config [{:skill-provider {:list-skills (fn [_] []) :read-skill (fn [_ _] "")}}
                  {:image-generation {:enabled true}}]]
    (is (not (s/valid? spec config))))
  (doseq [event [:copilot/session.quota_observation :copilot/session.managed_plugin_progress]]
    (is (not (contains? sdk/event-types event)))))
