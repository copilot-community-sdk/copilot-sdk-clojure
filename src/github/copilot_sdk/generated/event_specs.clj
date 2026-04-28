;; AUTO-GENERATED — do not edit. Run `bb codegen`.
;; Source: schemas/  (schema version pinned in .copilot-schema-version)

(ns github.copilot-sdk.generated.event-specs "AUTO-GENERATED. clojure.spec definitions for upstream session events.\n\n   Each event variant's `data` payload is registered under\n   `::<event-type>-data` (e.g. `::session.start-data`).\n   The envelope (id/timestamp/parentId/type/data) is registered under\n   `::<event-type>` (e.g. `::session.start`).\n\n   Source: schemas/session-events.schema.json" (:require [clojure.spec.alpha :as s]))

(s/def :github.copilot-sdk.generated.event-specs/agent-description clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/agent-display-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/agent-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/allowed-tools (s/coll-of clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/api-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/arguments clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/attachments (s/coll-of (s/or :branch-0 clojure.core/map? :branch-1 clojure.core/map? :branch-2 clojure.core/map?)))

(s/def :github.copilot-sdk.generated.event-specs/cache-read-tokens clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/cache-write-tokens clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/checkpoint-number clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/checkpoint-path clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/code-changes clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/compaction-tokens-used clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/context clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/copilot-version clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/cost clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/current-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/current-tokens clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/data clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/delta-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/duration clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/encrypted-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/ephemeral clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/error clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/error-reason clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/error-type clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/event-count clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/events-removed clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/handoff-time clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/hook-invocation-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/hook-type clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/import-time clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/info-type clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/initiator clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/input clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/input-tokens clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/intent clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/is-user-requested clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/legacy-session clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/mcp-server-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/mcp-tool-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/message clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/message-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/messages-length clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/messages-removed clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/messages-removed-during-truncation clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/metadata clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/model-metrics clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/new-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/output clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/output-tokens clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/parent-id (s/nilable clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/parent-tool-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/partial-output clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/path clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/performed-by clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/post-compaction-tokens clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/post-truncation-messages-length clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/post-truncation-tokens-in-messages clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/pre-compaction-messages-length clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/pre-compaction-tokens clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/pre-truncation-messages-length clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/pre-truncation-tokens-in-messages clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/previous-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/producer clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/progress-message clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/provider-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/quota-snapshots clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/reason clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/reasoning-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/reasoning-opaque clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/reasoning-text clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/remote-session-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/repository clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/request-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/result clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/resume-time clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/role #{"developer" "system"})

(s/def :github.copilot-sdk.generated.event-specs/selected-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/session-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/session-start-time clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/shutdown-type #{"error" "routine"})

(s/def :github.copilot-sdk.generated.event-specs/source clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/source-file clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/source-type #{"remote" "local"})

(s/def :github.copilot-sdk.generated.event-specs/stack clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/start-time clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/status-code clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/success clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/summary clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/summary-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/timestamp clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/token-limit clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/tokens-removed clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/tokens-removed-during-truncation clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/tool-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/tool-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/tool-requests (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/tool-telemetry clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/tools (s/nilable (s/coll-of clojure.core/string?)))

(s/def :github.copilot-sdk.generated.event-specs/total-api-duration-ms clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/total-premium-requests clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/total-response-size-bytes clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/transformed-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/turn-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/type clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/up-to-event-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/version clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/abort-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/reason]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.intent-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/intent]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/message-id] :opt-un [:github.copilot-sdk.generated.event-specs/encrypted-content :github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/reasoning-opaque :github.copilot-sdk.generated.event-specs/reasoning-text :github.copilot-sdk.generated.event-specs/tool-requests]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message_delta-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/delta-content :github.copilot-sdk.generated.event-specs/message-id] :opt-un [:github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/total-response-size-bytes]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/reasoning-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/delta-content :github.copilot-sdk.generated.event-specs/reasoning-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_end-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/turn-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/turn-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.usage-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/model] :opt-un [:github.copilot-sdk.generated.event-specs/api-call-id :github.copilot-sdk.generated.event-specs/cache-read-tokens :github.copilot-sdk.generated.event-specs/cache-write-tokens :github.copilot-sdk.generated.event-specs/cost :github.copilot-sdk.generated.event-specs/duration :github.copilot-sdk.generated.event-specs/initiator :github.copilot-sdk.generated.event-specs/input-tokens :github.copilot-sdk.generated.event-specs/output-tokens :github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/provider-call-id :github.copilot-sdk.generated.event-specs/quota-snapshots]))

(s/def :github.copilot-sdk.generated.event-specs/hook.end-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/hook-invocation-id :github.copilot-sdk.generated.event-specs/hook-type :github.copilot-sdk.generated.event-specs/success] :opt-un [:github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/output]))

(s/def :github.copilot-sdk.generated.event-specs/hook.start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/hook-invocation-id :github.copilot-sdk.generated.event-specs/hook-type] :opt-un [:github.copilot-sdk.generated.event-specs/input]))

(s/def :github.copilot-sdk.generated.event-specs/pending_messages.modified-data (s/keys))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_complete-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/success] :opt-un [:github.copilot-sdk.generated.event-specs/checkpoint-number :github.copilot-sdk.generated.event-specs/checkpoint-path :github.copilot-sdk.generated.event-specs/compaction-tokens-used :github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/messages-removed :github.copilot-sdk.generated.event-specs/post-compaction-tokens :github.copilot-sdk.generated.event-specs/pre-compaction-messages-length :github.copilot-sdk.generated.event-specs/pre-compaction-tokens :github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/summary-content :github.copilot-sdk.generated.event-specs/tokens-removed]))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_start-data (s/keys))

(s/def :github.copilot-sdk.generated.event-specs/session.error-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/error-type :github.copilot-sdk.generated.event-specs/message] :opt-un [:github.copilot-sdk.generated.event-specs/provider-call-id :github.copilot-sdk.generated.event-specs/stack :github.copilot-sdk.generated.event-specs/status-code]))

(s/def :github.copilot-sdk.generated.event-specs/session.handoff-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/handoff-time :github.copilot-sdk.generated.event-specs/source-type] :opt-un [:github.copilot-sdk.generated.event-specs/context :github.copilot-sdk.generated.event-specs/remote-session-id :github.copilot-sdk.generated.event-specs/repository :github.copilot-sdk.generated.event-specs/summary]))

(s/def :github.copilot-sdk.generated.event-specs/session.idle-data (s/keys))

(s/def :github.copilot-sdk.generated.event-specs/session.import_legacy-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/import-time :github.copilot-sdk.generated.event-specs/legacy-session :github.copilot-sdk.generated.event-specs/source-file]))

(s/def :github.copilot-sdk.generated.event-specs/session.info-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/info-type :github.copilot-sdk.generated.event-specs/message]))

(s/def :github.copilot-sdk.generated.event-specs/session.model_change-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/new-model] :opt-un [:github.copilot-sdk.generated.event-specs/previous-model]))

(s/def :github.copilot-sdk.generated.event-specs/session.resume-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/event-count :github.copilot-sdk.generated.event-specs/resume-time] :opt-un [:github.copilot-sdk.generated.event-specs/context]))

(s/def :github.copilot-sdk.generated.event-specs/session.shutdown-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/code-changes :github.copilot-sdk.generated.event-specs/model-metrics :github.copilot-sdk.generated.event-specs/session-start-time :github.copilot-sdk.generated.event-specs/shutdown-type :github.copilot-sdk.generated.event-specs/total-api-duration-ms :github.copilot-sdk.generated.event-specs/total-premium-requests] :opt-un [:github.copilot-sdk.generated.event-specs/current-model :github.copilot-sdk.generated.event-specs/error-reason]))

(s/def :github.copilot-sdk.generated.event-specs/session.snapshot_rewind-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/events-removed :github.copilot-sdk.generated.event-specs/up-to-event-id]))

(s/def :github.copilot-sdk.generated.event-specs/session.start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/copilot-version :github.copilot-sdk.generated.event-specs/producer :github.copilot-sdk.generated.event-specs/session-id :github.copilot-sdk.generated.event-specs/start-time :github.copilot-sdk.generated.event-specs/version] :opt-un [:github.copilot-sdk.generated.event-specs/context :github.copilot-sdk.generated.event-specs/selected-model]))

(s/def :github.copilot-sdk.generated.event-specs/session.truncation-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/messages-removed-during-truncation :github.copilot-sdk.generated.event-specs/performed-by :github.copilot-sdk.generated.event-specs/post-truncation-messages-length :github.copilot-sdk.generated.event-specs/post-truncation-tokens-in-messages :github.copilot-sdk.generated.event-specs/pre-truncation-messages-length :github.copilot-sdk.generated.event-specs/pre-truncation-tokens-in-messages :github.copilot-sdk.generated.event-specs/token-limit :github.copilot-sdk.generated.event-specs/tokens-removed-during-truncation]))

(s/def :github.copilot-sdk.generated.event-specs/session.usage_info-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/current-tokens :github.copilot-sdk.generated.event-specs/messages-length :github.copilot-sdk.generated.event-specs/token-limit]))

(s/def :github.copilot-sdk.generated.event-specs/skill.invoked-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/name :github.copilot-sdk.generated.event-specs/path] :opt-un [:github.copilot-sdk.generated.event-specs/allowed-tools]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.failed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.selected-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-display-name :github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/tools]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.started-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-description :github.copilot-sdk.generated.event-specs/agent-display-name :github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/system.message-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/role] :opt-un [:github.copilot-sdk.generated.event-specs/metadata :github.copilot-sdk.generated.event-specs/name]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_complete-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/success :github.copilot-sdk.generated.event-specs/tool-call-id] :opt-un [:github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/is-user-requested :github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/result :github.copilot-sdk.generated.event-specs/tool-telemetry]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_partial_result-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/partial-output :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_progress-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/progress-message :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/tool-call-id :github.copilot-sdk.generated.event-specs/tool-name] :opt-un [:github.copilot-sdk.generated.event-specs/arguments :github.copilot-sdk.generated.event-specs/mcp-server-name :github.copilot-sdk.generated.event-specs/mcp-tool-name :github.copilot-sdk.generated.event-specs/parent-tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/tool.user_requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/tool-call-id :github.copilot-sdk.generated.event-specs/tool-name] :opt-un [:github.copilot-sdk.generated.event-specs/arguments]))

(s/def :github.copilot-sdk.generated.event-specs/user.message-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content] :opt-un [:github.copilot-sdk.generated.event-specs/attachments :github.copilot-sdk.generated.event-specs/source :github.copilot-sdk.generated.event-specs/transformed-content]))

(s/def :github.copilot-sdk.generated.event-specs/abort (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "abort" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/abort-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.intent (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.intent" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.intent-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.message" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.message-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message_delta (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.message_delta" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.message_delta-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.reasoning" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.reasoning-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.reasoning_delta" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_end (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.turn_end" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.turn_end-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.turn_start" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.turn_start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.usage (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.usage" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.usage-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/hook.end (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "hook.end" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/hook.end-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/hook.start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "hook.start" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/hook.start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/pending_messages.modified (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "pending_messages.modified" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/pending_messages.modified-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_complete (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.compaction_complete" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.compaction_complete-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.compaction_start" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.compaction_start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.error (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.error" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.error-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.handoff (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.handoff" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.handoff-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.idle (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.idle" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.idle-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.import_legacy (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.import_legacy" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.import_legacy-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.info (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.info" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.info-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.model_change (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.model_change" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.model_change-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.resume (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.resume" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.resume-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.shutdown (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.shutdown" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.shutdown-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.snapshot_rewind (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.snapshot_rewind" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.snapshot_rewind-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.start" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.truncation (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.truncation" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.truncation-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.usage_info (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.usage_info" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.usage_info-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/skill.invoked (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "skill.invoked" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/skill.invoked-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.completed" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.failed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.failed" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.failed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.selected (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.selected" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.selected-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.started (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.started" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.started-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/system.message (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "system.message" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/system.message-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_complete (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "tool.execution_complete" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_complete-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_partial_result (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "tool.execution_partial_result" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_partial_result-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_progress (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "tool.execution_progress" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_progress-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "tool.execution_start" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.user_requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "tool.user_requested" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.user_requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/user.message (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "user.message" (:type event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/user.message-data (:data event)))))

(def event-types "Set of all event-type strings known to the schema." #{"abort" "assistant.intent" "assistant.message" "assistant.message_delta" "assistant.reasoning" "assistant.reasoning_delta" "assistant.turn_end" "assistant.turn_start" "assistant.usage" "hook.end" "hook.start" "pending_messages.modified" "session.compaction_complete" "session.compaction_start" "session.error" "session.handoff" "session.idle" "session.import_legacy" "session.info" "session.model_change" "session.resume" "session.shutdown" "session.snapshot_rewind" "session.start" "session.truncation" "session.usage_info" "skill.invoked" "subagent.completed" "subagent.failed" "subagent.selected" "subagent.started" "system.message" "tool.execution_complete" "tool.execution_partial_result" "tool.execution_progress" "tool.execution_start" "tool.user_requested" "user.message"})

(defmulti event-mm :type)

(defmethod event-mm "abort" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/abort))

(defmethod event-mm "assistant.intent" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.intent))

(defmethod event-mm "assistant.message" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.message))

(defmethod event-mm "assistant.message_delta" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.message_delta))

(defmethod event-mm "assistant.reasoning" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.reasoning))

(defmethod event-mm "assistant.reasoning_delta" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta))

(defmethod event-mm "assistant.turn_end" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.turn_end))

(defmethod event-mm "assistant.turn_start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.turn_start))

(defmethod event-mm "assistant.usage" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.usage))

(defmethod event-mm "hook.end" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/hook.end))

(defmethod event-mm "hook.start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/hook.start))

(defmethod event-mm "pending_messages.modified" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/pending_messages.modified))

(defmethod event-mm "session.compaction_complete" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.compaction_complete))

(defmethod event-mm "session.compaction_start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.compaction_start))

(defmethod event-mm "session.error" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.error))

(defmethod event-mm "session.handoff" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.handoff))

(defmethod event-mm "session.idle" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.idle))

(defmethod event-mm "session.import_legacy" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.import_legacy))

(defmethod event-mm "session.info" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.info))

(defmethod event-mm "session.model_change" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.model_change))

(defmethod event-mm "session.resume" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.resume))

(defmethod event-mm "session.shutdown" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.shutdown))

(defmethod event-mm "session.snapshot_rewind" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.snapshot_rewind))

(defmethod event-mm "session.start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.start))

(defmethod event-mm "session.truncation" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.truncation))

(defmethod event-mm "session.usage_info" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.usage_info))

(defmethod event-mm "skill.invoked" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/skill.invoked))

(defmethod event-mm "subagent.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.completed))

(defmethod event-mm "subagent.failed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.failed))

(defmethod event-mm "subagent.selected" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.selected))

(defmethod event-mm "subagent.started" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.started))

(defmethod event-mm "system.message" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/system.message))

(defmethod event-mm "tool.execution_complete" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_complete))

(defmethod event-mm "tool.execution_partial_result" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_partial_result))

(defmethod event-mm "tool.execution_progress" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_progress))

(defmethod event-mm "tool.execution_start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_start))

(defmethod event-mm "tool.user_requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.user_requested))

(defmethod event-mm "user.message" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/user.message))

(defmethod event-mm :default [_] nil)

(s/def :github.copilot-sdk.generated.event-specs/event (s/multi-spec event-mm :type))

