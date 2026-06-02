;; AUTO-GENERATED — do not edit. Run `bb codegen`.
;; Source: schemas/  (schema version pinned in .copilot-schema-version)

(ns github.copilot-sdk.generated.event-specs "AUTO-GENERATED. clojure.spec definitions for upstream session events.\n\n   Each event variant's `data` payload is registered under\n   `::<event-type>-data` (e.g. `::session.start-data`).\n   The envelope (id/timestamp/parentId/type/data) is registered under\n   `::<event-type>` (e.g. `::session.start`).\n\n   Source: schemas/session-events.schema.json" (:require [clojure.spec.alpha :as s]))

(s/def :github.copilot-sdk.generated.event-specs/aborted clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/action #{"cancel" "accept" "decline"})

(s/def :github.copilot-sdk.generated.event-specs/actions (s/coll-of #{"interactive" "autopilot_fleet" "exit_only" "autopilot"}))

(s/def :github.copilot-sdk.generated.event-specs/agent-description clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/agent-display-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/agent-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/agent-mode #{"interactive" "shell" "autopilot" "plan"})

(s/def :github.copilot-sdk.generated.event-specs/agent-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/agents (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/allow-all-permissions clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/allow-freeform clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/allowed-tools (s/coll-of clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/already-in-use clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/answer clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/anthropic-advisor-blocks (s/coll-of clojure.core/any?))

(s/def :github.copilot-sdk.generated.event-specs/anthropic-advisor-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/api-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/api-endpoint #{"/v1/messages" "/chat/completions" "/responses" "ws:/responses"})

(s/def :github.copilot-sdk.generated.event-specs/approved clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/args clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/arguments (s/spec (fn [v950] (or (s/valid? clojure.core/any? v950) (s/valid? clojure.core/map? v950)))))

(s/def :github.copilot-sdk.generated.event-specs/attachments (s/coll-of (s/or :branch-0 clojure.core/map? :branch-1 clojure.core/map? :branch-2 clojure.core/map? :branch-3 clojure.core/map? :branch-4 clojure.core/map? :branch-5 clojure.core/map?)))

(s/def :github.copilot-sdk.generated.event-specs/auto-approve-edits clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/availability #{"ready" "stale"})

(s/def :github.copilot-sdk.generated.event-specs/base-commit clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/branch clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/cache-read-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/cache-write-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/canvas-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/canvases (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/cause clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/checkpoint-number clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/checkpoint-path clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/choices (s/coll-of clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/code-changes clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/command clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/command-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/commands (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/compaction-tokens-used clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/content (s/spec (fn [v947] (or (s/valid? clojure.core/string? v947) (s/valid? clojure.core/map? v947)))))

(s/def :github.copilot-sdk.generated.event-specs/context (s/spec (fn [v944] (or (s/valid? clojure.core/map? v944) (s/valid? clojure.core/string? v944)))))

(s/def :github.copilot-sdk.generated.event-specs/context-tier (s/nilable #{"long_context" "default"}))

(s/def :github.copilot-sdk.generated.event-specs/continue-pending-work clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/conversation-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/copilot-usage clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/copilot-version clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/cost clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/current-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/current-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/custom-instructions clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/cwd clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/data clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/delta-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/description clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/detached-from-spawning-parent-session-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/display-prompt clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/display-verbatim clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/duration clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/duration-ms (s/spec (fn [v939] (or (s/valid? clojure.core/integer? v939) (s/valid? clojure.core/number? v939)))))

(s/def :github.copilot-sdk.generated.event-specs/elicitation-source clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/eligible-for-auto-switch clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/encrypted-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/ephemeral clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/error (s/spec (fn [v941] (or (s/valid? clojure.core/string? v941) (s/valid? clojure.core/map? v941)))))

(s/def :github.copilot-sdk.generated.event-specs/error-code clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/error-message clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/error-reason clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/error-type clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/errors (s/coll-of clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/event-count clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/events-removed clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/extension-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/extension-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/extensions (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/feedback clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/git-root clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/handoff-time clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/head-commit clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/hook-invocation-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/hook-type clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/host clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/host-type #{"github" "ado"})

(s/def :github.copilot-sdk.generated.event-specs/id (s/spec (fn [v942] (or (s/valid? clojure.core/string? v942) (s/valid? clojure.core/integer? v942)))))

(s/def :github.copilot-sdk.generated.event-specs/info-type clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/initiator clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/input clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/input-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/instance-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/intent clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/inter-token-latency-ms clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/interaction-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/interval-ms clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/is-autopilot-continuation clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/is-initial clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/is-user-requested clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/kind (s/or :branch-0 clojure.core/map? :branch-1 clojure.core/map? :branch-2 clojure.core/map? :branch-3 clojure.core/map? :branch-4 clojure.core/map? :branch-5 clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/mcp-request-id clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/mcp-server-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/mcp-tool-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/message clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/message-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/messages-length clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/messages-removed clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/messages-removed-during-truncation clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/metadata clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/mode #{"url" "form"})

(s/def :github.copilot-sdk.generated.event-specs/model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/model-metrics clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/native-document-path-fallback-paths (s/coll-of clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/new-mode #{"interactive" "autopilot" "plan"})

(s/def :github.copilot-sdk.generated.event-specs/new-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/operation (s/spec (fn [v946] (or (s/valid? #{"delete" "update" "create"} v946) (s/valid? #{"update" "create"} v946)))))

(s/def :github.copilot-sdk.generated.event-specs/output clojure.core/any?)

(s/def :github.copilot-sdk.generated.event-specs/output-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/parent-agent-task-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/parent-id (s/nilable clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/parent-tool-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/partial-output clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/path clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/payload (s/nilable (s/or :branch-0 clojure.core/string? :branch-1 clojure.core/number? :branch-2 clojure.core/boolean? :branch-3 (s/coll-of clojure.core/any?) :branch-4 clojure.core/map?)))

(s/def :github.copilot-sdk.generated.event-specs/performed-by clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/permission-request (s/or :branch-0 clojure.core/map? :branch-1 clojure.core/map? :branch-2 clojure.core/map? :branch-3 clojure.core/map? :branch-4 clojure.core/map? :branch-5 clojure.core/map? :branch-6 clojure.core/map? :branch-7 clojure.core/map? :branch-8 clojure.core/map? :branch-9 clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/phase clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/plan-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/plugin-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/plugin-version clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/post-compaction-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/post-truncation-messages-length clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/post-truncation-tokens-in-messages clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/pre-compaction-messages-length clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/pre-compaction-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/pre-truncation-messages-length clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/pre-truncation-tokens-in-messages clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/previous-allow-all-permissions clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/previous-mode #{"interactive" "autopilot" "plan"})

(s/def :github.copilot-sdk.generated.event-specs/previous-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/previous-reasoning-effort clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/previous-reasoning-summary #{"detailed" "none" "concise"})

(s/def :github.copilot-sdk.generated.event-specs/producer clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/progress-message clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/prompt clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/prompt-request (s/or :branch-0 clojure.core/map? :branch-1 clojure.core/map? :branch-2 clojure.core/map? :branch-3 clojure.core/map? :branch-4 clojure.core/map? :branch-5 clojure.core/map? :branch-6 clojure.core/map? :branch-7 clojure.core/map? :branch-8 clojure.core/map? :branch-9 clojure.core/map? :branch-10 clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/provider-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/question clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/quota-snapshots clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/reason #{"user_abort" "remote_command" "user_initiated"})

(s/def :github.copilot-sdk.generated.event-specs/reasoning-effort (s/spec (fn [v943] (or (s/valid? clojure.core/string? v943) (s/valid? clojure.core/any? v943)))))

(s/def :github.copilot-sdk.generated.event-specs/reasoning-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/reasoning-opaque clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/reasoning-summary #{"detailed" "none" "concise"})

(s/def :github.copilot-sdk.generated.event-specs/reasoning-text clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/reasoning-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/recommended-action #{"interactive" "autopilot_fleet" "exit_only" "autopilot"})

(s/def :github.copilot-sdk.generated.event-specs/recurring clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/remote-session-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/remote-steerable clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/reopen clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/repository (s/spec (fn [v940] (or (s/valid? clojure.core/map? v940) (s/valid? clojure.core/string? v940)))))

(s/def :github.copilot-sdk.generated.event-specs/repository-host clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/request-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/requested-schema clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/resolved-by-hook clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/response #{"yes_always" "yes" "no"})

(s/def :github.copilot-sdk.generated.event-specs/result (s/spec (fn [v951] (or (s/valid? clojure.core/map? v951) (s/valid? (s/or :branch-0 clojure.core/map? :branch-1 clojure.core/map? :branch-2 clojure.core/map? :branch-3 clojure.core/map? :branch-4 clojure.core/map? :branch-5 clojure.core/map? :branch-6 clojure.core/map? :branch-7 clojure.core/map? :branch-8 clojure.core/map?) v951)))))

(s/def :github.copilot-sdk.generated.event-specs/resume-time clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/retry-after-seconds clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/role #{"developer" "system"})

(s/def :github.copilot-sdk.generated.event-specs/sandboxed clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/selected-action #{"interactive" "autopilot_fleet" "exit_only" "autopilot"})

(s/def :github.copilot-sdk.generated.event-specs/selected-model clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/server-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/server-url clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/servers (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/service-request-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/session-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/session-start-time clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/session-was-active clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/shutdown-type #{"error" "routine"})

(s/def :github.copilot-sdk.generated.event-specs/skills (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/source (s/spec (fn [v948] (or (s/valid? clojure.core/string? v948) (s/valid? #{"top_level" "subagent" "mcp_sampling"} v948)))))

(s/def :github.copilot-sdk.generated.event-specs/source-type #{"remote" "local"})

(s/def :github.copilot-sdk.generated.event-specs/stack clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/start-time clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/static-client-config clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/status (s/spec (fn [v945] (or (s/valid? #{"cap_reached" "completed" "paused" "active"} v945) (s/valid? #{"failed" "not_configured" "needs-auth" "connected" "disabled" "pending"} v945) (s/valid? clojure.core/string? v945)))))

(s/def :github.copilot-sdk.generated.event-specs/status-code clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/subject clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/success clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/summary clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/summary-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/supported-native-document-mime-types (s/coll-of clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/system-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/time-to-first-token-ms clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/timestamp clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/tip clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/title clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/token-details clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/token-limit clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/tokens-removed clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/tokens-removed-during-truncation clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/tool-call-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/tool-definitions-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/tool-description clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/tool-meta clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/tool-name clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/tool-requests (s/coll-of clojure.core/map?))

(s/def :github.copilot-sdk.generated.event-specs/tool-telemetry clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/tools (s/nilable (s/coll-of clojure.core/string?)))

(s/def :github.copilot-sdk.generated.event-specs/total-api-duration-ms clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/total-nano-aiu clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/total-premium-requests clojure.core/number?)

(s/def :github.copilot-sdk.generated.event-specs/total-response-size-bytes clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/total-tokens clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/total-tool-calls clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/traceparent clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/tracestate clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/transformed-content clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/trigger #{"agent-invoked" "user-invoked" "context-load"})

(s/def :github.copilot-sdk.generated.event-specs/turn-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/type (s/spec (fn [v949] (or (s/valid? #{"session.start"} v949) (s/valid? #{"session.resume"} v949) (s/valid? #{"session.remote_steerable_changed"} v949) (s/valid? #{"session.error"} v949) (s/valid? #{"session.idle"} v949) (s/valid? #{"session.title_changed"} v949) (s/valid? #{"session.schedule_created"} v949) (s/valid? #{"session.schedule_cancelled"} v949) (s/valid? #{"session.autopilot_objective_changed"} v949) (s/valid? #{"session.info"} v949) (s/valid? #{"session.warning"} v949) (s/valid? #{"session.model_change"} v949) (s/valid? #{"session.mode_changed"} v949) (s/valid? #{"session.permissions_changed"} v949) (s/valid? #{"session.plan_changed"} v949) (s/valid? #{"session.workspace_file_changed"} v949) (s/valid? #{"session.handoff"} v949) (s/valid? #{"session.truncation"} v949) (s/valid? #{"session.snapshot_rewind"} v949) (s/valid? #{"session.shutdown"} v949) (s/valid? #{"session.context_changed"} v949) (s/valid? #{"session.usage_info"} v949) (s/valid? #{"session.compaction_start"} v949) (s/valid? #{"session.compaction_complete"} v949) (s/valid? #{"session.task_complete"} v949) (s/valid? #{"user.message"} v949) (s/valid? #{"pending_messages.modified"} v949) (s/valid? #{"assistant.turn_start"} v949) (s/valid? #{"assistant.intent"} v949) (s/valid? #{"assistant.reasoning"} v949) (s/valid? #{"assistant.reasoning_delta"} v949) (s/valid? #{"assistant.streaming_delta"} v949) (s/valid? #{"assistant.message"} v949) (s/valid? #{"assistant.message_start"} v949) (s/valid? #{"assistant.message_delta"} v949) (s/valid? #{"assistant.turn_end"} v949) (s/valid? #{"assistant.usage"} v949) (s/valid? #{"model.call_failure"} v949) (s/valid? #{"abort"} v949) (s/valid? #{"tool.user_requested"} v949) (s/valid? #{"tool.execution_start"} v949) (s/valid? #{"tool.execution_partial_result"} v949) (s/valid? #{"tool.execution_progress"} v949) (s/valid? #{"tool.execution_complete"} v949) (s/valid? #{"skill.invoked"} v949) (s/valid? #{"subagent.started"} v949) (s/valid? #{"subagent.completed"} v949) (s/valid? #{"subagent.failed"} v949) (s/valid? #{"subagent.selected"} v949) (s/valid? #{"subagent.deselected"} v949) (s/valid? #{"hook.start"} v949) (s/valid? #{"hook.end"} v949) (s/valid? #{"hook.progress"} v949) (s/valid? #{"system.message"} v949) (s/valid? #{"system.notification"} v949) (s/valid? #{"permission.requested"} v949) (s/valid? #{"permission.completed"} v949) (s/valid? #{"user_input.requested"} v949) (s/valid? #{"user_input.completed"} v949) (s/valid? #{"elicitation.requested"} v949) (s/valid? #{"elicitation.completed"} v949) (s/valid? #{"sampling.requested"} v949) (s/valid? #{"sampling.completed"} v949) (s/valid? #{"mcp.oauth_required"} v949) (s/valid? #{"mcp.oauth_completed"} v949) (s/valid? #{"session.custom_notification"} v949) (s/valid? #{"external_tool.requested"} v949) (s/valid? #{"external_tool.completed"} v949) (s/valid? #{"command.queued"} v949) (s/valid? #{"command.execute"} v949) (s/valid? #{"command.completed"} v949) (s/valid? #{"auto_mode_switch.requested"} v949) (s/valid? #{"auto_mode_switch.completed"} v949) (s/valid? #{"commands.changed"} v949) (s/valid? #{"capabilities.changed"} v949) (s/valid? #{"exit_plan_mode.requested"} v949) (s/valid? #{"exit_plan_mode.completed"} v949) (s/valid? #{"session.tools_updated"} v949) (s/valid? #{"session.background_tasks_changed"} v949) (s/valid? #{"session.skills_loaded"} v949) (s/valid? #{"session.custom_agents_updated"} v949) (s/valid? #{"session.mcp_servers_loaded"} v949) (s/valid? #{"session.mcp_server_status_changed"} v949) (s/valid? #{"session.extensions_loaded"} v949) (s/valid? #{"session.canvas.opened"} v949) (s/valid? #{"session.canvas.registry_changed"} v949) (s/valid? #{"session.extensions.attachments_pushed"} v949) (s/valid? #{"mcp_app.tool_call_complete"} v949)))))

(s/def :github.copilot-sdk.generated.event-specs/ui clojure.core/map?)

(s/def :github.copilot-sdk.generated.event-specs/up-to-event-id clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/url clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/version clojure.core/integer?)

(s/def :github.copilot-sdk.generated.event-specs/warning-type clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/warnings (s/coll-of clojure.core/string?))

(s/def :github.copilot-sdk.generated.event-specs/was-freeform clojure.core/boolean?)

(s/def :github.copilot-sdk.generated.event-specs/working-directory clojure.core/string?)

(s/def :github.copilot-sdk.generated.event-specs/abort-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/reason]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.intent-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/intent]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/message-id] :opt-un [:github.copilot-sdk.generated.event-specs/anthropic-advisor-blocks :github.copilot-sdk.generated.event-specs/anthropic-advisor-model :github.copilot-sdk.generated.event-specs/encrypted-content :github.copilot-sdk.generated.event-specs/interaction-id :github.copilot-sdk.generated.event-specs/model :github.copilot-sdk.generated.event-specs/output-tokens :github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/phase :github.copilot-sdk.generated.event-specs/reasoning-opaque :github.copilot-sdk.generated.event-specs/reasoning-text :github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/service-request-id :github.copilot-sdk.generated.event-specs/tool-requests :github.copilot-sdk.generated.event-specs/turn-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message_delta-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/delta-content :github.copilot-sdk.generated.event-specs/message-id] :opt-un [:github.copilot-sdk.generated.event-specs/parent-tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message_start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/message-id] :opt-un [:github.copilot-sdk.generated.event-specs/phase]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/reasoning-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/delta-content :github.copilot-sdk.generated.event-specs/reasoning-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.streaming_delta-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/total-response-size-bytes]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_end-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/turn-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/turn-id] :opt-un [:github.copilot-sdk.generated.event-specs/interaction-id]))

(s/def :github.copilot-sdk.generated.event-specs/assistant.usage-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/model] :opt-un [:github.copilot-sdk.generated.event-specs/api-call-id :github.copilot-sdk.generated.event-specs/api-endpoint :github.copilot-sdk.generated.event-specs/cache-read-tokens :github.copilot-sdk.generated.event-specs/cache-write-tokens :github.copilot-sdk.generated.event-specs/copilot-usage :github.copilot-sdk.generated.event-specs/cost :github.copilot-sdk.generated.event-specs/duration :github.copilot-sdk.generated.event-specs/initiator :github.copilot-sdk.generated.event-specs/input-tokens :github.copilot-sdk.generated.event-specs/inter-token-latency-ms :github.copilot-sdk.generated.event-specs/output-tokens :github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/provider-call-id :github.copilot-sdk.generated.event-specs/quota-snapshots :github.copilot-sdk.generated.event-specs/reasoning-effort :github.copilot-sdk.generated.event-specs/reasoning-tokens :github.copilot-sdk.generated.event-specs/service-request-id :github.copilot-sdk.generated.event-specs/time-to-first-token-ms]))

(s/def :github.copilot-sdk.generated.event-specs/auto_mode_switch.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/response]))

(s/def :github.copilot-sdk.generated.event-specs/auto_mode_switch.requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id] :opt-un [:github.copilot-sdk.generated.event-specs/error-code :github.copilot-sdk.generated.event-specs/retry-after-seconds]))

(s/def :github.copilot-sdk.generated.event-specs/capabilities.changed-data (s/keys :opt-un [:github.copilot-sdk.generated.event-specs/ui]))

(s/def :github.copilot-sdk.generated.event-specs/command.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id]))

(s/def :github.copilot-sdk.generated.event-specs/command.execute-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/args :github.copilot-sdk.generated.event-specs/command :github.copilot-sdk.generated.event-specs/command-name :github.copilot-sdk.generated.event-specs/request-id]))

(s/def :github.copilot-sdk.generated.event-specs/command.queued-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/command :github.copilot-sdk.generated.event-specs/request-id]))

(s/def :github.copilot-sdk.generated.event-specs/commands.changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/commands]))

(s/def :github.copilot-sdk.generated.event-specs/elicitation.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id] :opt-un [:github.copilot-sdk.generated.event-specs/action :github.copilot-sdk.generated.event-specs/content]))

(s/def :github.copilot-sdk.generated.event-specs/elicitation.requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/message :github.copilot-sdk.generated.event-specs/request-id] :opt-un [:github.copilot-sdk.generated.event-specs/elicitation-source :github.copilot-sdk.generated.event-specs/mode :github.copilot-sdk.generated.event-specs/requested-schema :github.copilot-sdk.generated.event-specs/tool-call-id :github.copilot-sdk.generated.event-specs/url]))

(s/def :github.copilot-sdk.generated.event-specs/exit_plan_mode.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id] :opt-un [:github.copilot-sdk.generated.event-specs/approved :github.copilot-sdk.generated.event-specs/auto-approve-edits :github.copilot-sdk.generated.event-specs/feedback :github.copilot-sdk.generated.event-specs/selected-action]))

(s/def :github.copilot-sdk.generated.event-specs/exit_plan_mode.requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/actions :github.copilot-sdk.generated.event-specs/plan-content :github.copilot-sdk.generated.event-specs/recommended-action :github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/summary]))

(s/def :github.copilot-sdk.generated.event-specs/external_tool.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id]))

(s/def :github.copilot-sdk.generated.event-specs/external_tool.requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/session-id :github.copilot-sdk.generated.event-specs/tool-call-id :github.copilot-sdk.generated.event-specs/tool-name] :opt-un [:github.copilot-sdk.generated.event-specs/arguments :github.copilot-sdk.generated.event-specs/traceparent :github.copilot-sdk.generated.event-specs/tracestate :github.copilot-sdk.generated.event-specs/working-directory]))

(s/def :github.copilot-sdk.generated.event-specs/hook.end-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/hook-invocation-id :github.copilot-sdk.generated.event-specs/hook-type :github.copilot-sdk.generated.event-specs/success] :opt-un [:github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/output]))

(s/def :github.copilot-sdk.generated.event-specs/hook.progress-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/message]))

(s/def :github.copilot-sdk.generated.event-specs/hook.start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/hook-invocation-id :github.copilot-sdk.generated.event-specs/hook-type] :opt-un [:github.copilot-sdk.generated.event-specs/input]))

(s/def :github.copilot-sdk.generated.event-specs/mcp.oauth_completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id]))

(s/def :github.copilot-sdk.generated.event-specs/mcp.oauth_required-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/server-name :github.copilot-sdk.generated.event-specs/server-url] :opt-un [:github.copilot-sdk.generated.event-specs/static-client-config]))

(s/def :github.copilot-sdk.generated.event-specs/mcp_app.tool_call_complete-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/duration-ms :github.copilot-sdk.generated.event-specs/server-name :github.copilot-sdk.generated.event-specs/success :github.copilot-sdk.generated.event-specs/tool-name] :opt-un [:github.copilot-sdk.generated.event-specs/arguments :github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/result :github.copilot-sdk.generated.event-specs/tool-meta]))

(s/def :github.copilot-sdk.generated.event-specs/model.call_failure-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/source] :opt-un [:github.copilot-sdk.generated.event-specs/api-call-id :github.copilot-sdk.generated.event-specs/duration-ms :github.copilot-sdk.generated.event-specs/error-message :github.copilot-sdk.generated.event-specs/initiator :github.copilot-sdk.generated.event-specs/model :github.copilot-sdk.generated.event-specs/provider-call-id :github.copilot-sdk.generated.event-specs/service-request-id :github.copilot-sdk.generated.event-specs/status-code]))

(s/def :github.copilot-sdk.generated.event-specs/pending_messages.modified-data (s/keys))

(s/def :github.copilot-sdk.generated.event-specs/permission.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/result] :opt-un [:github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/permission.requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/permission-request :github.copilot-sdk.generated.event-specs/request-id] :opt-un [:github.copilot-sdk.generated.event-specs/prompt-request :github.copilot-sdk.generated.event-specs/resolved-by-hook]))

(s/def :github.copilot-sdk.generated.event-specs/sampling.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id]))

(s/def :github.copilot-sdk.generated.event-specs/sampling.requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/mcp-request-id :github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/server-name]))

(s/def :github.copilot-sdk.generated.event-specs/session.autopilot_objective_changed-data (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/operation] :opt-un [:github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/status]) (fn [data] (or (not (contains? data :id)) (s/valid? clojure.core/integer? (:id data))))))

(s/def :github.copilot-sdk.generated.event-specs/session.background_tasks_changed-data (s/keys))

(s/def :github.copilot-sdk.generated.event-specs/session.canvas.opened-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/availability :github.copilot-sdk.generated.event-specs/canvas-id :github.copilot-sdk.generated.event-specs/extension-id :github.copilot-sdk.generated.event-specs/instance-id :github.copilot-sdk.generated.event-specs/reopen] :opt-un [:github.copilot-sdk.generated.event-specs/extension-name :github.copilot-sdk.generated.event-specs/input :github.copilot-sdk.generated.event-specs/status :github.copilot-sdk.generated.event-specs/title :github.copilot-sdk.generated.event-specs/url]))

(s/def :github.copilot-sdk.generated.event-specs/session.canvas.registry_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/canvases]))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_complete-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/success] :opt-un [:github.copilot-sdk.generated.event-specs/checkpoint-number :github.copilot-sdk.generated.event-specs/checkpoint-path :github.copilot-sdk.generated.event-specs/compaction-tokens-used :github.copilot-sdk.generated.event-specs/conversation-tokens :github.copilot-sdk.generated.event-specs/custom-instructions :github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/messages-removed :github.copilot-sdk.generated.event-specs/post-compaction-tokens :github.copilot-sdk.generated.event-specs/pre-compaction-messages-length :github.copilot-sdk.generated.event-specs/pre-compaction-tokens :github.copilot-sdk.generated.event-specs/request-id :github.copilot-sdk.generated.event-specs/service-request-id :github.copilot-sdk.generated.event-specs/summary-content :github.copilot-sdk.generated.event-specs/system-tokens :github.copilot-sdk.generated.event-specs/tokens-removed :github.copilot-sdk.generated.event-specs/tool-definitions-tokens]))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_start-data (s/keys :opt-un [:github.copilot-sdk.generated.event-specs/conversation-tokens :github.copilot-sdk.generated.event-specs/system-tokens :github.copilot-sdk.generated.event-specs/tool-definitions-tokens]))

(s/def :github.copilot-sdk.generated.event-specs/session.context_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/cwd] :opt-un [:github.copilot-sdk.generated.event-specs/base-commit :github.copilot-sdk.generated.event-specs/branch :github.copilot-sdk.generated.event-specs/git-root :github.copilot-sdk.generated.event-specs/head-commit :github.copilot-sdk.generated.event-specs/host-type :github.copilot-sdk.generated.event-specs/repository :github.copilot-sdk.generated.event-specs/repository-host]))

(s/def :github.copilot-sdk.generated.event-specs/session.custom_agents_updated-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agents :github.copilot-sdk.generated.event-specs/errors :github.copilot-sdk.generated.event-specs/warnings]))

(s/def :github.copilot-sdk.generated.event-specs/session.custom_notification-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/name :github.copilot-sdk.generated.event-specs/payload :github.copilot-sdk.generated.event-specs/source] :opt-un [:github.copilot-sdk.generated.event-specs/subject :github.copilot-sdk.generated.event-specs/version]))

(s/def :github.copilot-sdk.generated.event-specs/session.error-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/error-type :github.copilot-sdk.generated.event-specs/message] :opt-un [:github.copilot-sdk.generated.event-specs/eligible-for-auto-switch :github.copilot-sdk.generated.event-specs/error-code :github.copilot-sdk.generated.event-specs/provider-call-id :github.copilot-sdk.generated.event-specs/service-request-id :github.copilot-sdk.generated.event-specs/stack :github.copilot-sdk.generated.event-specs/status-code :github.copilot-sdk.generated.event-specs/url]))

(s/def :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/attachments]))

(s/def :github.copilot-sdk.generated.event-specs/session.extensions_loaded-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/extensions]))

(s/def :github.copilot-sdk.generated.event-specs/session.handoff-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/handoff-time :github.copilot-sdk.generated.event-specs/source-type] :opt-un [:github.copilot-sdk.generated.event-specs/context :github.copilot-sdk.generated.event-specs/host :github.copilot-sdk.generated.event-specs/remote-session-id :github.copilot-sdk.generated.event-specs/repository :github.copilot-sdk.generated.event-specs/summary]))

(s/def :github.copilot-sdk.generated.event-specs/session.idle-data (s/keys :opt-un [:github.copilot-sdk.generated.event-specs/aborted]))

(s/def :github.copilot-sdk.generated.event-specs/session.info-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/info-type :github.copilot-sdk.generated.event-specs/message] :opt-un [:github.copilot-sdk.generated.event-specs/tip :github.copilot-sdk.generated.event-specs/url]))

(s/def :github.copilot-sdk.generated.event-specs/session.mcp_server_status_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/server-name :github.copilot-sdk.generated.event-specs/status] :opt-un [:github.copilot-sdk.generated.event-specs/error]))

(s/def :github.copilot-sdk.generated.event-specs/session.mcp_servers_loaded-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/servers]))

(s/def :github.copilot-sdk.generated.event-specs/session.mode_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/new-mode :github.copilot-sdk.generated.event-specs/previous-mode]))

(s/def :github.copilot-sdk.generated.event-specs/session.model_change-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/new-model] :opt-un [:github.copilot-sdk.generated.event-specs/cause :github.copilot-sdk.generated.event-specs/context-tier :github.copilot-sdk.generated.event-specs/previous-model :github.copilot-sdk.generated.event-specs/previous-reasoning-effort :github.copilot-sdk.generated.event-specs/previous-reasoning-summary :github.copilot-sdk.generated.event-specs/reasoning-effort :github.copilot-sdk.generated.event-specs/reasoning-summary]))

(s/def :github.copilot-sdk.generated.event-specs/session.permissions_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/allow-all-permissions :github.copilot-sdk.generated.event-specs/previous-allow-all-permissions]))

(s/def :github.copilot-sdk.generated.event-specs/session.plan_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/operation]))

(s/def :github.copilot-sdk.generated.event-specs/session.remote_steerable_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/remote-steerable]))

(s/def :github.copilot-sdk.generated.event-specs/session.resume-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/event-count :github.copilot-sdk.generated.event-specs/resume-time] :opt-un [:github.copilot-sdk.generated.event-specs/already-in-use :github.copilot-sdk.generated.event-specs/context :github.copilot-sdk.generated.event-specs/context-tier :github.copilot-sdk.generated.event-specs/continue-pending-work :github.copilot-sdk.generated.event-specs/reasoning-effort :github.copilot-sdk.generated.event-specs/reasoning-summary :github.copilot-sdk.generated.event-specs/remote-steerable :github.copilot-sdk.generated.event-specs/selected-model :github.copilot-sdk.generated.event-specs/session-was-active]))

(s/def :github.copilot-sdk.generated.event-specs/session.schedule_cancelled-data (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/id]) (fn [data] (s/valid? clojure.core/integer? (:id data)))))

(s/def :github.copilot-sdk.generated.event-specs/session.schedule_created-data (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/interval-ms :github.copilot-sdk.generated.event-specs/prompt] :opt-un [:github.copilot-sdk.generated.event-specs/display-prompt :github.copilot-sdk.generated.event-specs/recurring]) (fn [data] (s/valid? clojure.core/integer? (:id data)))))

(s/def :github.copilot-sdk.generated.event-specs/session.shutdown-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/code-changes :github.copilot-sdk.generated.event-specs/model-metrics :github.copilot-sdk.generated.event-specs/session-start-time :github.copilot-sdk.generated.event-specs/shutdown-type :github.copilot-sdk.generated.event-specs/total-api-duration-ms] :opt-un [:github.copilot-sdk.generated.event-specs/conversation-tokens :github.copilot-sdk.generated.event-specs/current-model :github.copilot-sdk.generated.event-specs/current-tokens :github.copilot-sdk.generated.event-specs/error-reason :github.copilot-sdk.generated.event-specs/system-tokens :github.copilot-sdk.generated.event-specs/token-details :github.copilot-sdk.generated.event-specs/tool-definitions-tokens :github.copilot-sdk.generated.event-specs/total-nano-aiu :github.copilot-sdk.generated.event-specs/total-premium-requests]))

(s/def :github.copilot-sdk.generated.event-specs/session.skills_loaded-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/skills]))

(s/def :github.copilot-sdk.generated.event-specs/session.snapshot_rewind-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/events-removed :github.copilot-sdk.generated.event-specs/up-to-event-id]))

(s/def :github.copilot-sdk.generated.event-specs/session.start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/copilot-version :github.copilot-sdk.generated.event-specs/producer :github.copilot-sdk.generated.event-specs/session-id :github.copilot-sdk.generated.event-specs/start-time :github.copilot-sdk.generated.event-specs/version] :opt-un [:github.copilot-sdk.generated.event-specs/already-in-use :github.copilot-sdk.generated.event-specs/context :github.copilot-sdk.generated.event-specs/context-tier :github.copilot-sdk.generated.event-specs/detached-from-spawning-parent-session-id :github.copilot-sdk.generated.event-specs/reasoning-effort :github.copilot-sdk.generated.event-specs/reasoning-summary :github.copilot-sdk.generated.event-specs/remote-steerable :github.copilot-sdk.generated.event-specs/selected-model]))

(s/def :github.copilot-sdk.generated.event-specs/session.task_complete-data (s/keys :opt-un [:github.copilot-sdk.generated.event-specs/success :github.copilot-sdk.generated.event-specs/summary]))

(s/def :github.copilot-sdk.generated.event-specs/session.title_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/title]))

(s/def :github.copilot-sdk.generated.event-specs/session.tools_updated-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/model]))

(s/def :github.copilot-sdk.generated.event-specs/session.truncation-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/messages-removed-during-truncation :github.copilot-sdk.generated.event-specs/performed-by :github.copilot-sdk.generated.event-specs/post-truncation-messages-length :github.copilot-sdk.generated.event-specs/post-truncation-tokens-in-messages :github.copilot-sdk.generated.event-specs/pre-truncation-messages-length :github.copilot-sdk.generated.event-specs/pre-truncation-tokens-in-messages :github.copilot-sdk.generated.event-specs/token-limit :github.copilot-sdk.generated.event-specs/tokens-removed-during-truncation]))

(s/def :github.copilot-sdk.generated.event-specs/session.usage_info-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/current-tokens :github.copilot-sdk.generated.event-specs/messages-length :github.copilot-sdk.generated.event-specs/token-limit] :opt-un [:github.copilot-sdk.generated.event-specs/conversation-tokens :github.copilot-sdk.generated.event-specs/is-initial :github.copilot-sdk.generated.event-specs/system-tokens :github.copilot-sdk.generated.event-specs/tool-definitions-tokens]))

(s/def :github.copilot-sdk.generated.event-specs/session.warning-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/message :github.copilot-sdk.generated.event-specs/warning-type] :opt-un [:github.copilot-sdk.generated.event-specs/url]))

(s/def :github.copilot-sdk.generated.event-specs/session.workspace_file_changed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/operation :github.copilot-sdk.generated.event-specs/path]))

(s/def :github.copilot-sdk.generated.event-specs/skill.invoked-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/name :github.copilot-sdk.generated.event-specs/path] :opt-un [:github.copilot-sdk.generated.event-specs/allowed-tools :github.copilot-sdk.generated.event-specs/description :github.copilot-sdk.generated.event-specs/plugin-name :github.copilot-sdk.generated.event-specs/plugin-version :github.copilot-sdk.generated.event-specs/source :github.copilot-sdk.generated.event-specs/trigger]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-display-name :github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/tool-call-id] :opt-un [:github.copilot-sdk.generated.event-specs/duration-ms :github.copilot-sdk.generated.event-specs/model :github.copilot-sdk.generated.event-specs/total-tokens :github.copilot-sdk.generated.event-specs/total-tool-calls]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.deselected-data (s/keys))

(s/def :github.copilot-sdk.generated.event-specs/subagent.failed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-display-name :github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/tool-call-id] :opt-un [:github.copilot-sdk.generated.event-specs/duration-ms :github.copilot-sdk.generated.event-specs/model :github.copilot-sdk.generated.event-specs/total-tokens :github.copilot-sdk.generated.event-specs/total-tool-calls]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.selected-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-display-name :github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/tools]))

(s/def :github.copilot-sdk.generated.event-specs/subagent.started-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/agent-description :github.copilot-sdk.generated.event-specs/agent-display-name :github.copilot-sdk.generated.event-specs/agent-name :github.copilot-sdk.generated.event-specs/tool-call-id] :opt-un [:github.copilot-sdk.generated.event-specs/model]))

(s/def :github.copilot-sdk.generated.event-specs/system.message-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/role] :opt-un [:github.copilot-sdk.generated.event-specs/metadata :github.copilot-sdk.generated.event-specs/name]))

(s/def :github.copilot-sdk.generated.event-specs/system.notification-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content :github.copilot-sdk.generated.event-specs/kind]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_complete-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/success :github.copilot-sdk.generated.event-specs/tool-call-id] :opt-un [:github.copilot-sdk.generated.event-specs/error :github.copilot-sdk.generated.event-specs/interaction-id :github.copilot-sdk.generated.event-specs/is-user-requested :github.copilot-sdk.generated.event-specs/model :github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/result :github.copilot-sdk.generated.event-specs/sandboxed :github.copilot-sdk.generated.event-specs/tool-description :github.copilot-sdk.generated.event-specs/tool-telemetry :github.copilot-sdk.generated.event-specs/turn-id]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_partial_result-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/partial-output :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_progress-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/progress-message :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_start-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/tool-call-id :github.copilot-sdk.generated.event-specs/tool-name] :opt-un [:github.copilot-sdk.generated.event-specs/arguments :github.copilot-sdk.generated.event-specs/display-verbatim :github.copilot-sdk.generated.event-specs/mcp-server-name :github.copilot-sdk.generated.event-specs/mcp-tool-name :github.copilot-sdk.generated.event-specs/model :github.copilot-sdk.generated.event-specs/parent-tool-call-id :github.copilot-sdk.generated.event-specs/turn-id]))

(s/def :github.copilot-sdk.generated.event-specs/tool.user_requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/tool-call-id :github.copilot-sdk.generated.event-specs/tool-name] :opt-un [:github.copilot-sdk.generated.event-specs/arguments]))

(s/def :github.copilot-sdk.generated.event-specs/user.message-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/content] :opt-un [:github.copilot-sdk.generated.event-specs/agent-mode :github.copilot-sdk.generated.event-specs/attachments :github.copilot-sdk.generated.event-specs/interaction-id :github.copilot-sdk.generated.event-specs/is-autopilot-continuation :github.copilot-sdk.generated.event-specs/native-document-path-fallback-paths :github.copilot-sdk.generated.event-specs/parent-agent-task-id :github.copilot-sdk.generated.event-specs/source :github.copilot-sdk.generated.event-specs/supported-native-document-mime-types :github.copilot-sdk.generated.event-specs/transformed-content]))

(s/def :github.copilot-sdk.generated.event-specs/user_input.completed-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/request-id] :opt-un [:github.copilot-sdk.generated.event-specs/answer :github.copilot-sdk.generated.event-specs/was-freeform]))

(s/def :github.copilot-sdk.generated.event-specs/user_input.requested-data (s/keys :req-un [:github.copilot-sdk.generated.event-specs/question :github.copilot-sdk.generated.event-specs/request-id] :opt-un [:github.copilot-sdk.generated.event-specs/allow-freeform :github.copilot-sdk.generated.event-specs/choices :github.copilot-sdk.generated.event-specs/tool-call-id]))

(s/def :github.copilot-sdk.generated.event-specs/abort (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "abort" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/abort-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.intent (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.intent" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.intent-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.message" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.message-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message_delta (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.message_delta" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.message_delta-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.message_start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.message_start" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.message_start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.reasoning" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.reasoning-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.reasoning_delta" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.streaming_delta (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.streaming_delta" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.streaming_delta-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_end (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.turn_end" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.turn_end-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.turn_start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "assistant.turn_start" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.turn_start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/assistant.usage (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "assistant.usage" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/assistant.usage-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/auto_mode_switch.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "auto_mode_switch.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/auto_mode_switch.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/auto_mode_switch.requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "auto_mode_switch.requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/auto_mode_switch.requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/capabilities.changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "capabilities.changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/capabilities.changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/command.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "command.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/command.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/command.execute (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "command.execute" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/command.execute-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/command.queued (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "command.queued" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/command.queued-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/commands.changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "commands.changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/commands.changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/elicitation.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "elicitation.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/elicitation.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/elicitation.requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "elicitation.requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/elicitation.requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/exit_plan_mode.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "exit_plan_mode.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/exit_plan_mode.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/exit_plan_mode.requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "exit_plan_mode.requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/exit_plan_mode.requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/external_tool.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "external_tool.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/external_tool.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/external_tool.requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "external_tool.requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/external_tool.requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/hook.end (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "hook.end" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/hook.end-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/hook.progress (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "hook.progress" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/hook.progress-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/hook.start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "hook.start" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/hook.start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/mcp.oauth_completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "mcp.oauth_completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/mcp.oauth_completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/mcp.oauth_required (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "mcp.oauth_required" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/mcp.oauth_required-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/mcp_app.tool_call_complete (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "mcp_app.tool_call_complete" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/mcp_app.tool_call_complete-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/model.call_failure (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "model.call_failure" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/model.call_failure-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/pending_messages.modified (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "pending_messages.modified" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/pending_messages.modified-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/permission.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "permission.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/permission.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/permission.requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "permission.requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/permission.requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/sampling.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "sampling.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/sampling.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/sampling.requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "sampling.requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/sampling.requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.autopilot_objective_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.autopilot_objective_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.autopilot_objective_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.background_tasks_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.background_tasks_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.background_tasks_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.canvas.opened (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.canvas.opened" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.canvas.opened-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.canvas.registry_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.canvas.registry_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.canvas.registry_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_complete (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.compaction_complete" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.compaction_complete-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.compaction_start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.compaction_start" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.compaction_start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.context_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.context_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.context_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.custom_agents_updated (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.custom_agents_updated" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.custom_agents_updated-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.custom_notification (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.custom_notification" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.custom_notification-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.error (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.error" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.error-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.extensions.attachments_pushed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.extensions_loaded (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.extensions_loaded" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.extensions_loaded-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.handoff (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.handoff" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.handoff-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.idle (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.idle" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.idle-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.info (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.info" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.info-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.mcp_server_status_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.mcp_server_status_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.mcp_server_status_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.mcp_servers_loaded (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.mcp_servers_loaded" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.mcp_servers_loaded-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.mode_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.mode_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.mode_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.model_change (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.model_change" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.model_change-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.permissions_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.permissions_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.permissions_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.plan_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.plan_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.plan_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.remote_steerable_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.remote_steerable_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.remote_steerable_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.resume (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.resume" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.resume-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.schedule_cancelled (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.schedule_cancelled" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.schedule_cancelled-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.schedule_created (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.schedule_created" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.schedule_created-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.shutdown (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.shutdown" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.shutdown-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.skills_loaded (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.skills_loaded" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.skills_loaded-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.snapshot_rewind (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.snapshot_rewind" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.snapshot_rewind-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.start" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.task_complete (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.task_complete" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.task_complete-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.title_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.title_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.title_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.tools_updated (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.tools_updated" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.tools_updated-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.truncation (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.truncation" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.truncation-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.usage_info (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "session.usage_info" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.usage_info-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.warning (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.warning" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.warning-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/session.workspace_file_changed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "session.workspace_file_changed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/session.workspace_file_changed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/skill.invoked (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "skill.invoked" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/skill.invoked-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.deselected (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.deselected" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.deselected-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.failed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.failed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.failed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.selected (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.selected" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.selected-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/subagent.started (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "subagent.started" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/subagent.started-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/system.message (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "system.message" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/system.message-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/system.notification (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "system.notification" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/system.notification-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_complete (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "tool.execution_complete" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_complete-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_partial_result (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "tool.execution_partial_result" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_partial_result-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_progress (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "tool.execution_progress" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_progress-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.execution_start (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "tool.execution_start" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.execution_start-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/tool.user_requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "tool.user_requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/tool.user_requested-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/user.message (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id :github.copilot-sdk.generated.event-specs/ephemeral]) (fn [event] (clojure.core/= "user.message" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/user.message-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/user_input.completed (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "user_input.completed" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/user_input.completed-data (:data event)))))

(s/def :github.copilot-sdk.generated.event-specs/user_input.requested (s/and (s/keys :req-un [:github.copilot-sdk.generated.event-specs/data :github.copilot-sdk.generated.event-specs/ephemeral :github.copilot-sdk.generated.event-specs/id :github.copilot-sdk.generated.event-specs/parent-id :github.copilot-sdk.generated.event-specs/timestamp :github.copilot-sdk.generated.event-specs/type] :opt-un [:github.copilot-sdk.generated.event-specs/agent-id]) (fn [event] (clojure.core/= true (:ephemeral event))) (fn [event] (clojure.core/= "user_input.requested" (:type event))) (fn [event] (s/valid? clojure.core/string? (:id event))) (fn [event] (s/valid? :github.copilot-sdk.generated.event-specs/user_input.requested-data (:data event)))))

(def event-types "Set of all event-type strings known to the schema." #{"abort" "assistant.intent" "assistant.message" "assistant.message_delta" "assistant.message_start" "assistant.reasoning" "assistant.reasoning_delta" "assistant.streaming_delta" "assistant.turn_end" "assistant.turn_start" "assistant.usage" "auto_mode_switch.completed" "auto_mode_switch.requested" "capabilities.changed" "command.completed" "command.execute" "command.queued" "commands.changed" "elicitation.completed" "elicitation.requested" "exit_plan_mode.completed" "exit_plan_mode.requested" "external_tool.completed" "external_tool.requested" "hook.end" "hook.progress" "hook.start" "mcp.oauth_completed" "mcp.oauth_required" "mcp_app.tool_call_complete" "model.call_failure" "pending_messages.modified" "permission.completed" "permission.requested" "sampling.completed" "sampling.requested" "session.autopilot_objective_changed" "session.background_tasks_changed" "session.canvas.opened" "session.canvas.registry_changed" "session.compaction_complete" "session.compaction_start" "session.context_changed" "session.custom_agents_updated" "session.custom_notification" "session.error" "session.extensions.attachments_pushed" "session.extensions_loaded" "session.handoff" "session.idle" "session.info" "session.mcp_server_status_changed" "session.mcp_servers_loaded" "session.mode_changed" "session.model_change" "session.permissions_changed" "session.plan_changed" "session.remote_steerable_changed" "session.resume" "session.schedule_cancelled" "session.schedule_created" "session.shutdown" "session.skills_loaded" "session.snapshot_rewind" "session.start" "session.task_complete" "session.title_changed" "session.tools_updated" "session.truncation" "session.usage_info" "session.warning" "session.workspace_file_changed" "skill.invoked" "subagent.completed" "subagent.deselected" "subagent.failed" "subagent.selected" "subagent.started" "system.message" "system.notification" "tool.execution_complete" "tool.execution_partial_result" "tool.execution_progress" "tool.execution_start" "tool.user_requested" "user.message" "user_input.completed" "user_input.requested"})

(defmulti event-mm :type)

(defmethod event-mm "abort" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/abort))

(defmethod event-mm "assistant.intent" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.intent))

(defmethod event-mm "assistant.message" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.message))

(defmethod event-mm "assistant.message_delta" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.message_delta))

(defmethod event-mm "assistant.message_start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.message_start))

(defmethod event-mm "assistant.reasoning" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.reasoning))

(defmethod event-mm "assistant.reasoning_delta" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.reasoning_delta))

(defmethod event-mm "assistant.streaming_delta" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.streaming_delta))

(defmethod event-mm "assistant.turn_end" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.turn_end))

(defmethod event-mm "assistant.turn_start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.turn_start))

(defmethod event-mm "assistant.usage" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/assistant.usage))

(defmethod event-mm "auto_mode_switch.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/auto_mode_switch.completed))

(defmethod event-mm "auto_mode_switch.requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/auto_mode_switch.requested))

(defmethod event-mm "capabilities.changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/capabilities.changed))

(defmethod event-mm "command.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/command.completed))

(defmethod event-mm "command.execute" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/command.execute))

(defmethod event-mm "command.queued" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/command.queued))

(defmethod event-mm "commands.changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/commands.changed))

(defmethod event-mm "elicitation.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/elicitation.completed))

(defmethod event-mm "elicitation.requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/elicitation.requested))

(defmethod event-mm "exit_plan_mode.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/exit_plan_mode.completed))

(defmethod event-mm "exit_plan_mode.requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/exit_plan_mode.requested))

(defmethod event-mm "external_tool.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/external_tool.completed))

(defmethod event-mm "external_tool.requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/external_tool.requested))

(defmethod event-mm "hook.end" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/hook.end))

(defmethod event-mm "hook.progress" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/hook.progress))

(defmethod event-mm "hook.start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/hook.start))

(defmethod event-mm "mcp.oauth_completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/mcp.oauth_completed))

(defmethod event-mm "mcp.oauth_required" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/mcp.oauth_required))

(defmethod event-mm "mcp_app.tool_call_complete" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/mcp_app.tool_call_complete))

(defmethod event-mm "model.call_failure" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/model.call_failure))

(defmethod event-mm "pending_messages.modified" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/pending_messages.modified))

(defmethod event-mm "permission.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/permission.completed))

(defmethod event-mm "permission.requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/permission.requested))

(defmethod event-mm "sampling.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/sampling.completed))

(defmethod event-mm "sampling.requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/sampling.requested))

(defmethod event-mm "session.autopilot_objective_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.autopilot_objective_changed))

(defmethod event-mm "session.background_tasks_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.background_tasks_changed))

(defmethod event-mm "session.canvas.opened" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.canvas.opened))

(defmethod event-mm "session.canvas.registry_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.canvas.registry_changed))

(defmethod event-mm "session.compaction_complete" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.compaction_complete))

(defmethod event-mm "session.compaction_start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.compaction_start))

(defmethod event-mm "session.context_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.context_changed))

(defmethod event-mm "session.custom_agents_updated" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.custom_agents_updated))

(defmethod event-mm "session.custom_notification" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.custom_notification))

(defmethod event-mm "session.error" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.error))

(defmethod event-mm "session.extensions.attachments_pushed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.extensions.attachments_pushed))

(defmethod event-mm "session.extensions_loaded" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.extensions_loaded))

(defmethod event-mm "session.handoff" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.handoff))

(defmethod event-mm "session.idle" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.idle))

(defmethod event-mm "session.info" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.info))

(defmethod event-mm "session.mcp_server_status_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.mcp_server_status_changed))

(defmethod event-mm "session.mcp_servers_loaded" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.mcp_servers_loaded))

(defmethod event-mm "session.mode_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.mode_changed))

(defmethod event-mm "session.model_change" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.model_change))

(defmethod event-mm "session.permissions_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.permissions_changed))

(defmethod event-mm "session.plan_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.plan_changed))

(defmethod event-mm "session.remote_steerable_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.remote_steerable_changed))

(defmethod event-mm "session.resume" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.resume))

(defmethod event-mm "session.schedule_cancelled" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.schedule_cancelled))

(defmethod event-mm "session.schedule_created" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.schedule_created))

(defmethod event-mm "session.shutdown" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.shutdown))

(defmethod event-mm "session.skills_loaded" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.skills_loaded))

(defmethod event-mm "session.snapshot_rewind" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.snapshot_rewind))

(defmethod event-mm "session.start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.start))

(defmethod event-mm "session.task_complete" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.task_complete))

(defmethod event-mm "session.title_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.title_changed))

(defmethod event-mm "session.tools_updated" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.tools_updated))

(defmethod event-mm "session.truncation" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.truncation))

(defmethod event-mm "session.usage_info" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.usage_info))

(defmethod event-mm "session.warning" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.warning))

(defmethod event-mm "session.workspace_file_changed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/session.workspace_file_changed))

(defmethod event-mm "skill.invoked" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/skill.invoked))

(defmethod event-mm "subagent.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.completed))

(defmethod event-mm "subagent.deselected" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.deselected))

(defmethod event-mm "subagent.failed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.failed))

(defmethod event-mm "subagent.selected" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.selected))

(defmethod event-mm "subagent.started" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/subagent.started))

(defmethod event-mm "system.message" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/system.message))

(defmethod event-mm "system.notification" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/system.notification))

(defmethod event-mm "tool.execution_complete" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_complete))

(defmethod event-mm "tool.execution_partial_result" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_partial_result))

(defmethod event-mm "tool.execution_progress" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_progress))

(defmethod event-mm "tool.execution_start" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.execution_start))

(defmethod event-mm "tool.user_requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/tool.user_requested))

(defmethod event-mm "user.message" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/user.message))

(defmethod event-mm "user_input.completed" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/user_input.completed))

(defmethod event-mm "user_input.requested" [_] (s/get-spec :github.copilot-sdk.generated.event-specs/user_input.requested))

(defmethod event-mm :default [_] nil)

(s/def :github.copilot-sdk.generated.event-specs/event (s/multi-spec event-mm :type))

