# Clojure SDK API

Require `github.copilot-sdk` for the native client, sessions, operations, and
subscriptions. All functions below use the alias `copilot`.

```clojure
(require '[github.copilot-sdk :as copilot])

(defn reply-text [session prompt]
  (some-> (copilot/await!! (copilot/send-and-wait! session {:prompt prompt
                                                            :timeout-ms 30000}))
          :data :content))
```

## Resource lifetimes

| Call | Result and ownership |
|---|---|
| `(with-client [client options] body...)` | Block to open a native client; shut it down when the body exits. |
| `(with-session [session client options] body...)` | Create a session and detach it on exit. Detach does not delete history. |
| `(open-client!! options)` | Open an owned client; call `close!!` if not using `with-client`. |
| `(open-session!! client options)` | Open an owned new session; call `close!!` if not using `with-session`. |
| `(resume-session!! client id options)` | Open an owned handle to a saved session. Use `with-open` or `close!!` to detach it. |
| `(session-id session)` | Return the session ID immediately, not on a channel. Save it if the application will resume. |
| `(session-info session)` | Return `:session-id` and `:capabilities`, plus `:workspace-path`, `:transcript-recovery`, and `:permission-mode-result` when supplied. `:capabilities` is `nil` if the runtime supplied no capabilities at open or in a notification. Test individual flags with `true?`. |
| `(close!! resource)` | Detach a session or shut down a client. Closing a client also releases its open sessions. |
| `(force-close!! client)` | Skip the graceful detach and shutdown RPCs. Still release local resources and wait for the native callback safety barrier. |
| `(delete-session!! client id)` | Permanently remove a runtime-stored session by exact ID and invalidate its open handle. Return `nil` on success. Deletion never expands a UUID prefix. |

Keep scopes open until operations that use them finish. Blocking functions
and macros do not belong inside `go`. `with-client` and `with-session` retain
the primary failure when cleanup also fails. Treat a client close failure as
an error, not proof that the runtime released its resources.

The native image stays loaded until JVM exit. A client admits at most one
handle for a given session ID. Closing an old handle cannot detach a new
one. Store and resume with the **full ID** returned by `session-id`. Short
UUID prefixes depend on the runtime's session inventory. An eight-character
prefix can resolve a persisted session after a completed turn; opening and
detaching an unused session does not guarantee discovery. When using a
session filesystem, supply its exact ID. If the runtime refuses a session
detach, do not treat the session as closed. Interrupting a caller after it
sends a detach or delete does not undo the request. The SDK waits for its
bounded result and applies an acknowledged change to the handle before
reporting interruption. If a sent create, resume, detach, or delete times out
with a known ID but no acknowledgment, that client cannot reuse the ID:
the runtime may still apply the request. An explicit refusal of create or
resume is different: the caller can retry that ID. If necessary, reconcile
saved state from a new client. A close that cannot confirm callback
safety reports an error and can defer cleanup. A native call with an unknown outcome can leave a host
quarantined. Quarantined and active hosts count against the same bounded
registry. Do not open replacement clients in a retry loop.

For deletion, use a full UUID or an ordinary name such as `build-review`.
The public runtime API can expand a short ID to a different session, so
the SDK rejects IDs that look like abbreviated UUIDs (`:ambiguous-delete-id`),
even if the complete custom name is `deadbeef`. With `:session-fs`, deletion
is the application's responsibility: close the session, then remove its data
through the provider. Graceful client close waits for deletions already sent.

## Operations

An operation returns a core.async channel that delivers **one** `{:ok value}`
or `{:error exception}` and closes. `(copilot/await!! result)` blocks a caller
thread, returns the value (including `nil`), or throws. Inside `go`, take the
result with `<!` and inspect it. An event subscription is not an operation.

| Call | Successful value |
|---|---|
| `(status client)` | Map containing `:version` and `:protocol-version`. The runtime may also supply `:host-diagnostics` (experimental data). Reading status does not enable diagnostic collection. |
| `(ping client)` or `(ping client message)` | Runtime control response; fields include `:message`, `:timestamp`, and `:protocol-version` when reported. No model invocation. |
| `(auth-status client)` | Authentication status, including `:authenticated?` when reported. This does not sign in. |
| `(list-models client)` | Vector of model descriptions. Concurrent callers share a fetch and successful results are cached per client. Failed fetches are not cached. |
| `(list-sessions client)` or `(list-sessions client filters)` | Vector of session metadata. Optional string filters: `:cwd`, `:git-root`, `:repository`, and `:branch`. |
| `(last-session-id client)` | ID of the most recently updated session, or `nil`. |
| `(session-metadata client id)` | Metadata map, or `nil` if absent. `:start-time` and `:modified-time` are `java.time.Instant` values. |
| `(send! session message)` | Message ID when the runtime accepts the submission. It does **not** indicate completion. |
| `(send-and-wait! session message)` | Final root assistant event for this request, or `nil` if the runtime completed without an assistant response. |
| `(abort! session)` | `nil` when the runtime acknowledges the abort. This affects active session work, not just a local wait. |
| `(history session)` or `(history session view)` | Vector of events. Select `:idiom` (default) or `:raw`. |
| `(set-model! session model)` or `(set-model! session model tuning)` | `nil` after the runtime processes the model switch. `model` is a string. Omit tuning to change only the selection. |
| `(log! session message)` or `(log! session message options)` | `nil` after appending a timeline entry. Optional `:level` is `:info`, `:warning`, or `:error`; `:ephemeral? true` omits the entry from saved history. |
| `(elicit! session form)` | UI result with `:action` (`:accept`, `:decline`, or `:cancel`) and optional `:content`. Requires an advertised host capability; does not read stdin. |

`set-model!` accepts `:reasoning-effort`, `:reasoning-summary`, `:context-tier`,
and `:model-capabilities` in its tuning map. Use `[:inference :capabilities]`
for the same capability override at session open. Its `:supports` map can set
`:vision?`, `:tool-calls?`, `:reasoning-effort?`, and `:adaptive-thinking`.
Its `:limits` map can set token or vision limits. An override describes a
model; it does not install a provider, enable a UI, or grant access. Omit
unused fields rather than sending `nil`.

For `elicit!`, pass `{:message text :requested-schema schema}`. Read
`[:capabilities :ui :elicitation]` from `(copilot/session-info session)`;
capabilities can change while the session is open. Without that capability
the operation reports an error. This outbound UI request is distinct from
registering an application callback for a request **from** the runtime.
It also does not install a desktop UI scheduler. Native UI-thread scheduling
depends on the host, platform, and application event loop; the headless
examples do not test a desktop UI or an identity-provider flow.

Use that one operation for confirmation, selection, or text entry instead of
expecting three convenience functions. For example, check both the action and
the submitted boolean. Form property names in `:content` are strings:

```clojure
(require '[github.copilot-sdk :as copilot])

(defn confirmed? [session]
  (let [form {:message "Include the appendix?"
              :requested-schema {:type "object" :properties {"confirmed" {:type "boolean"}}
                                 :required ["confirmed"]}}
        answer (copilot/await!! (copilot/elicit! session form))]
    (and (= :accept (:action answer)) (true? (get-in answer [:content "confirmed"])))))
```

## Client options

| Key | Type | Default or omission |
|---|---|---|
| `:mode` | `:empty` or `:coding-agent` | Required. There is no default to start a subprocess. |
| `:base-directory` | Nonblank string | Runtime storage root. Required for `:empty` unless you supply `:session-fs`. Use a directory private to the tenant or application. |
| `:session-fs` | Map | Omitted uses the runtime's own filesystem. Requires the matching provider on each create/resume; see below. |
| `:github` | Map | Optional `:token` string and `:use-logged-in-user?` boolean. Automatic login is off by default in `:empty`; `:coding-agent` can use the logged-in user when no token is supplied. |
| `:client-info` | Map | Optional string `:application-name`, `:application-version`, `:integration-name`, and `:integration-version` identify the host at connection time. |
| `:models` | Map | Optional zero-argument `:on-list` supplies a vector of model descriptions, directly or on a core.async channel. Omit to use the runtime's list. |
| `:plugins` | Map | `:builtin-directories` is a vector of trusted absolute host paths. Omitted or `[]` registers none. This is distinct from session-level plugin discovery. |
| `:run-on-ui-thread` | Function of one action | Optional desktop integration. Have the application's UI thread invoke the supplied zero-argument action exactly once, and wait for it to finish before returning. Omit on headless hosts. |
| `:session-idle-timeout-seconds` | Nonnegative integer | Omit for the native runtime's timeout policy. |
| `:observability` | Map | Optional `:log-level`: `:none`, `:error`, `:warning`, `:info`, `:debug`, or `:all`. A zero-argument `:on-get-trace-context` can supply `:traceparent` and `:tracestate`. `:telemetry` configures the native host's OpenTelemetry exporter. |

`:coding-agent` is for a host that intends normal coding capabilities. In
`:empty` mode the SDK removes ambient GitHub/Copilot token settings **from the
runtime environment**; it does not scrub secrets from the application JVM.
It disables repository hooks, host Git operations, local
credential storage, skills, and cross-session memory by default. This is not
an OS sandbox. Do not trust repository contents, subprocesses, remote
servers, or model output merely because you selected `:empty`.

### Host telemetry and plugins

The **presence** of `[:observability :telemetry]`, even `{}`, enables
OpenTelemetry for the native host. There is no `:enabled?` key in this map.
Supply string `:source-name`, `:exporter-type`, `:file-path`, or
`:otlp-endpoint` as required. `:otlp-protocol` accepts `"http/json"` or
`"http/protobuf"`. Omitted fields leave the host's environment settings in
effect; runtime policy takes precedence.

`{:capture-content? false}` inside that map explicitly disables content capture;
`true` can put prompt and response content into spans. Use approved files or
collector endpoints only. Model or request metadata can be sensitive even
without content. Request trace context and GitHub service telemetry are
separate controls.

The SDK looks up trace context for create, resume, and message submission.
For a send, lookup starts when the session admits the request, with the
caller's dynamic bindings. Its 10-second budget includes time waiting behind
earlier requests. Model and trace providers share four workers per client.
If all four are busy, the SDK omits the optional trace headers; it does not
queue or retry the lookup.

Later sends, aborts, history reads, timeline entries, UI requests, and model
changes on the same session cannot overtake a send waiting for trace headers.
This orders submission, not completion. Each session permits 64 live
reservations; excess requests report `:admission-capacity`. A turn timeout
retires that turn's lookup, reservation, or pending reply without aborting
other work. Detaching a session retires its outstanding lookups, even for
requests that have not reached the front of the queue.

Register only built-in plugin directories the application controls. They are
registered during client startup, not installed from a marketplace, and a
registration failure prevents opening the client.

## Session options

Pass a map on create or resume. Unknown options fail rather than silently
falling back to a CLI. Omit a key to use its default; `nil` is not a general
substitute for omission. Common configuration groups are listed below.

| Key or group | Type and use | Default or omission |
|---|---|---|
| `:session-id`, `:name` | Create-only strings. Pass the ID as a separate argument on resume. `:name` is for local sessions. | Local create generates an ID if omitted; cloud create can receive one from the service. |
| `:cloud` | Create-only map; optional `:repository` takes `:owner`, `:name`, and optional `:branch` strings. | Omit for a local session. A cloud request requires the relevant service access; it is not a different SDK transport. |
| `:model`, `:client-name`, `:working-directory`, `:config-directory` | Optional strings. `:enable-config-discovery?` is a boolean. | Omit to leave selection to the runtime. |
| `:inference` | `:reasoning-effort` string, `:reasoning-summary` keyword, `:context-tier`, and named provider/model vectors. | The named catalog is experimental upstream. `:default-provider` accepts a name or explicit `nil`. See [Getting started](../getting-started.md) for a complete catalog. |
| `:events` | `:streaming?`, `:include-sub-agent-streaming-events?`, optional `(fn [event] ...)` at `:on-event`. | Streaming is not requested unless configured. Use `subscribe` to own a stream. |
| `:tools` | Vectors `:available-tools`, `:excluded-tools`, `:definitions`; optional `:search` and `:large-output` maps. | `:empty` requires an explicit `:available-tools` on create **and** resume. `[]` means no tools; a bare `"*"` is not an allowlist. |
| `:permissions` | `:on-request` takes a request and invocation context. | Omitting a handler does **not** mean approve. |
| `:github` | Session `:token` or `:token-provider`; `:capi` options include `:enabled?` and `:auto-tier`. | No provider callback unless registered. Do not specify both session token forms. |
| `:interaction` | `:commands` vector and callbacks for user input, elicitation, shell authentication, plan exit, or automatic mode changes. Boolean `:auto-mode-recovery?` requests recovery of an active request. | No implicit user decision. Recovery is not requested by default. Re-register callbacks after detach. |
| `:hooks` | `:handlers` map, `:directories` vector, `:enable-repository-hooks?` boolean. | Application callbacks and executable repository hooks are separate; repository hooks default off in `:empty`. |
| `:mcp` | `:servers` map keyed by server name strings, `:disabled-servers` vector, `:on-auth-request` callback. | Stdio MCP servers execute a command; HTTP/SSE servers contact an endpoint. Only configure trusted servers. No MCP server is needed to load the SDK. |
| `:agents` | `:definitions` vector with `:name` and `:prompt`, `:selected`, and discovery directories. | Do not infer tool permissions from a custom agent's name. |
| `:system-message` | Map with `:mode` `:append`, `:replace`, or `:customize`; `:replace` requires `:content`. | `:empty` removes the environment-context prompt section by default. |
| `:filesystem`, `:git`, `:instructions`, `:skills`, `:plugins` | Group filesystem roots, Git access, instruction or skill discovery, and plugin directories. | Grant discovery access deliberately. No separate runtime process isolates what you grant. |
| `:policy` | `:allowed-models` vector; `:managed-settings` map; managed-model policy options. | Set permissions and allowed models for the application rather than relying on a prompt. |
| `:state` | `:infinite-sessions` with `:enabled?` and optional compaction thresholds; memory, storage, and session filesystem options. | Closing a session does not delete its persisted history. |
| `:suppress-resume-event?`, `:allow-transcript-recovery?`, `:continue-pending-work?` | Resume-only booleans. | Omit to leave the runtime behavior unchanged. |

For `:system-message` customization, look up section identifiers and descriptions
in `github.copilot-sdk.specs/system-message-sections`. For example:

```clojure
(require '[github.copilot-sdk.specs :as specs])

(get-in specs/system-message-sections [:tone :description])
;; => "Response style, length, and format."
```

When creating a cloud session with no explicit ID, the SDK binds the new
handle to the ID returned by the runtime. If that ID already belongs to an
open handle, the second open fails without deleting the first. If local setup
fails after the runtime acknowledges a newly allocated cloud ID, the SDK
attempts to delete that allocation. A cleanup error remains attached to the
setup error. A graceful client close waits for rollback of an allocated
cloud session that is still opening, or reports a transition timeout.
Closing an open session normally still detaches rather than deletes.

Interrupting the caller after a create was sent does not cancel the remote
allocation. The SDK stops admitting callbacks for that open and waits up to
the request deadline for a reply so it can clean up a known allocation. If
the request times out or the connection fails before the server returns an
ID, the outcome is unknown: the SDK does not guess an ID and cannot
guarantee deletion.

If the runtime never acknowledges a cleanup detach or delete for a **known
ID**, that client will not reuse the ID for the rest of its lifetime; other
IDs on a still-connected client remain usable. An explicit error reply is
reported as a cleanup failure but does not impose that restriction. These
are tested protocol behaviors, not a live cloud service or entitlement test.

**Configuration limits.** The named BYOK catalog supports provider/model
definitions and fixed credentials, not upstream's experimental rotating
bearer-token callback. Session `[:github :token-provider]` is a different
contract. The Node create/resume types also refer to experimental types for
startup sandbox configuration, permission mode, and diagnostics. The Clojure
SDK does not accept those options. Reading an open result or status diagnostic
does not grant permission to set them. A permission handler is not a sandbox.

### MCP servers and custom agents

```clojure
(defn research-options [server-command catalog-url]
  {:mcp {:servers {"notes" {:type :stdio :command server-command
                            :args ["--stdio"] :tools ["lookup"] :timeout 5000}}}
   :agents {:selected "catalog-reader"
            :definitions [{:name "catalog-reader" :prompt "Look up the requested catalog entry."
                           :mcp-servers {"catalog" {:type :http :url catalog-url
                                                    :tools ["find_entry"] :timeout 5000}}}]}})
```

Supply an installed, trusted server executable and a trusted MCP endpoint.
Merge these options into your session configuration. The `"notes"` server is
configured for the session; `"catalog"` is configured only for this agent.
These are configuration examples, not servers bundled with the SDK.

| Scope | Keys and defaults |
|---|---|
| Local MCP server | `:command` is required. Omitted `:type`, `:stdio`, and `:local` select a subprocess. Optional `:args` is a vector of strings, `:env` a string-to-string map, and `:working-directory` a string. |
| Remote MCP server | `:type` (`:http` or `:sse`) and `:url` are required. Optional `:headers` is a string-to-string map. Do not store credentials in source. |
| Either server | `:timeout` is in milliseconds; omit it for the runtime default. Server `:tools` omitted or `["*"]` includes all its tools; `[]` includes none. This is not the session's tool allowlist or a permission grant. |
| Custom agent | `:name` and `:prompt` are required. Agent `:tools` omitted or `nil` does not restrict tools further; `[]` selects none. `:model-policy :required` requires the configured `:model`; omitted policy permits fallback. Per-agent `:hooks` maps event-name strings to vectors of declarative actions, not Clojure functions. |

### Tool and permission callbacks

Put tool definitions in `[:tools :definitions]`. Use `:name`, `:description`,
a JSON Schema in `:parameters`, and a `(fn [arguments invocation] ...)`
handler. The invocation includes the session and tool-call IDs and a
`:cancelled?` function. A handler can return text. Permission handlers should
check the operation and its arguments before returning `{:kind :approve-once}`;
use `{:kind :reject}` for other requests.

```clojure
(defn lookup-options [entries]
  {:tools
   {:available-tools ["custom:lookup"]
    :definitions [{:name "lookup" :description "Read one entry from the supplied map."
                   :parameters {:type "object" :properties {:key {:type "string"}}
                                :required ["key"] :additionalProperties false}
                   :handler (fn [{:keys [key]} _] (get entries key "not found"))}]}
   :permissions
   {:on-request
    (fn [{:keys [kind tool-name args]} _]
      (if (and (= kind "custom-tool") (= tool-name "lookup") (contains? entries (:key args)))
        {:kind :approve-once}
        {:kind :reject}))}})
```

Pass this map, together with explicit model configuration, to `with-session`.
Tool execution and permission decisions run in the application. Do not
attribute an automated decision to a person.

Each session runs up to two tool, permission, command, or other broadcast
callbacks at a time. A busy session queues up to 16 more in arrival order.
The client shares eight workers and up to 128 waiting requests across
sessions. A free worker takes the oldest request whose session has a slot.
If the SDK cannot admit more work, it returns a failure or unavailable
decision; it does not grant permission. Keep callbacks short and honor
cancellation.

If an automatic permission handler throws or returns an invalid decision,
the SDK replies `{:kind :user-not-available}`, without attributing it to a user.
If the runtime definitively rejects a different automatic decision as invalid
parameters, the SDK sends **one** `:user-not-available` reply for that request.
It does not run the handler again. If the runtime rejects that terminal reply
as invalid parameters, the still-live request is available for manual
response. A timeout, disconnect, or negative acknowledgment does not trigger
a retry. Hook-resolved requests and replay checkpoints do not invoke the
handler; cancellation suppresses stale replies. No absent handler grants
permission.

To return an MCP tool result from a Clojure handler, use the pure function
`copilot/mcp-tool-result`. Supply a `:content` vector with string block types
`"text"`, `"image"`, or `"resource"` and an optional `:is-error?` flag. Text
is joined with newlines. Image data and resource blobs use canonical Base64;
a resource blob without a MIME type defaults to `application/octet-stream`.
The result includes `:result-type`, `:text-result-for-llm`, and binary results
only when present. Conversion does not start an MCP server.

If the application resolves a pending request itself, use
`(copilot/respond!! session kind answer)`. It **blocks** until it receives
an affirmative runtime acknowledgment or fails; do not pass it to `await!!`. For `:tool`, supply the original
`:request-id` and exactly one of `:result` or a nonempty `:error` string.
For `:permission`, supply `:request-id` and a decision in `:result`. Do not
retry an already settled request or answer a request belonging to another
session. If `respond!!` throws a JSON-RPC invalid-parameters error (`-32602`),
you can correct the answer on the same live handle unless the request has
since completed or been aborted. Do not retry an ambiguous delivery. A
`{:kind :no-result}` abstention is not a manual resolution.

On create, omitting `:on-request` does not advertise an application permission
handler. On resume, the runtime can send **new** permission requests even
without that handler. Observe `:copilot/permission.requested` and pass its
`[:data :request-id]` to `respond!!` from an application thread. If nobody
answers, the tool can remain pending. A local wait timeout does not deny
permission or abort the turn.

Detach ends the original `send-and-wait!` wait. An ordinary resume interrupts
unfinished work; a permission request still visible in history need not be
actionable. To continue pending work, resume with `:continue-pending-work? true`
and answer the **saved** permission request ID. Do not wait for another
notification for that ID. `respond!!` does not enumerate pending requests.

`copilot/approve-all` is an **explicit opt-in** permission handler for trusted,
unmanaged use. It returns `{:kind :no-result}` rather than granting a request that
requires managed approval, and refuses use with managed settings enabled.
It is not the default. Prefer a handler that verifies both the operation and
its arguments, as above.

### Credentials and interactive callbacks

The offline examples do not certify rotating live credentials, all hook
types, interactive UI, or third-party MCP servers. Verify the host capabilities
and callbacks your application depends on before migrating.

A session `[:github :token-provider]` receives a map with `:host`,
`:reason` (`:initial` or `:refresh`), and `:session-id` when known.
An initial request for a cloud session can precede its server-assigned ID.
It may be called **before** open/resume returns. Return `{:kind :cancelled}`
or `{:kind :token :access-token token :expires-in seconds}` with more than
3,600 seconds remaining; `:token-type` is optional. A core.async channel of
the same result is also accepted. The runtime owns refresh timing. Supply
the provider again on resume; the SDK registers it. Do not put credentials
in logs, prompts, tool output, or a saved example.

Command handlers receive invocation context. Elicitation returns `:action`
(`:accept`, `:decline`, or `:cancel`) and optional `:content`. If the
runtime explicitly rejects an answer as invalid parameters, the SDK sends
one cancellation without repeating the form data. User input
returns `:answer` and `:was-freeform?`. Shell authentication handles
`:secret`, `:text`, or `:confirm` input; cancel with `{:action :cancel}`.
Declare `[:interaction :on-shell-authentication-request]` only if the host can
securely collect the user's response. Honor its cancellation channel and
never log secret input.

A client can wait for four human responses across all its sessions: user
input, shell authentication, plan exit, and auto-mode changes share those
four slots. Each request has five minutes from receipt. If all slots are busy
or a request times out, the SDK cancels shell authentication, rejects plan
exit, declines an auto-mode switch, or reports user input unavailable. It does
not queue another question. Filesystem, hook, token-provider, and native UI
callbacks use separate workers with a 60-second deadline.

Return `{:approved? false}` to reject a plan exit request. An auto-mode switch
handler receives `[request invocation]` and can return `:no`, `:yes`, or
`:yes-always`. Set `[:interaction :auto-mode-recovery?]` to `true` only with
`:on-auto-mode-switch-request`. Create or resume fails with
`:unsupported-auto-mode-recovery` if the runtime does not acknowledge the
capability. A recovery offer has a nested `:auto-mode-recovery` map with
`:request-id`, `:original-model`, and `:allow-always?`. If `:allow-always?`
is false, `:yes-always` is treated as `:no`. Without recovery, a switch
request can apply to future messages rather than the current one.

An MCP OAuth handler at
`[:mcp :on-auth-request]` takes a request and invocation context. It can supply
an access token or cancel; hand refresh back to the runtime only when the
request advertises `:runtime-refresh-supported`. If supplied, `:expires-in`
must be a positive integer in seconds. An invalid lifetime cancels the
request without sending the token. If the runtime explicitly rejects a token
reply as invalid parameters, the SDK sends one cancellation, not the token
again. MCP OAuth does not authenticate the Clojure-to-Rust connection.

### Session hooks

```clojure
(def hook-options
  {:hooks {:handlers
           {:on-user-prompt-transformed
            (fn [{:keys [transformed-prompt]} _invocation]
              (when (string? transformed-prompt)
                {:modified-transformed-prompt (str transformed-prompt "\nUse plain language.")}))}}})
```

Register `(fn [input invocation] ...)` on each create or resume. The
invocation contains the registered `:session-id`; the input's `:session-id`
identifies the emitting runtime session and can differ for child agents.
Common input fields include `:working-directory` and `:timestamp` (numeric
epoch milliseconds become `java.time.Instant`). The runtime can also send
`:agent-id`, `:agent-type`, `:parent-agent-id`, `:traceparent`, and
`:tracestate`. The table describes expected input, **not** a guarantee that
the SDK validates or fills missing fields. Check values your handler needs.

| Handler under `[:hooks :handlers]` | Hook-specific input | Optional result fields |
|---|---|---|
| `:on-pre-tool-use` | `:tool-name`, `:tool-args`, optional `:tool-call-id` | `:permission-decision` (`:allow`, `:deny`, `:ask`), `:permission-decision-reason`, `:modified-args` (JSON), `:additional-context`, `:suppress-output?` |
| `:on-pre-mcp-tool-call` | `:server-name`, `:tool-name`, `:arguments`, optional `:tool-call-id` and `:_meta` | `:meta-to-use`: omit to retain metadata, a map to replace it, or `nil` to clear hook-controlled metadata, not runtime-owned keys. |
| `:on-post-tool-use` | Successful `:tool-result`, `:tool-name`, `:tool-args`, optional `:tool-call-id` | `:modified-result` (structured tool result with `:text-result-for-llm` and `:result-type`), `:additional-context`, `:suppress-output?` |
| `:on-post-tool-use-failure` | `:tool-name`, `:tool-args`, `:error` string, optional `:tool-call-id`; no full tool result | `:additional-context` for the model. This hook handles `:failure`, not rejection, denial, or timeout. |
| `:on-user-prompt-submitted` | `:prompt` | `:modified-prompt`, `:additional-context`, `:suppress-output?`, or `:decision :block` with optional `:reason` |
| `:on-user-prompt-transformed` | `:prompt`, `:transformed-prompt` | `:modified-transformed-prompt` before persistence and model submission |
| `:on-session-start` | `:source` (`:startup`, `:resume`, `:new`), optional `:initial-prompt` | `:additional-context`, `:modified-config` (JSON object) |
| `:on-session-end` | `:reason` (`:complete`, `:error`, `:abort`, `:timeout`, `:user-exit`), optional `:final-message`, `:error` | `:suppress-output?`, `:cleanup-actions` (vector of strings), `:session-summary` |
| `:on-error-occurred` | `:error`, `:error-context` (`:model-call`, `:tool-execution`, `:system`, `:user-input`), `:recoverable?` | `:error-handling` (`:retry`, `:skip`, `:abort`), `:retry-count` (number), `:user-notification`, `:suppress-output?` |
| `:on-agent-stop` | Optional `:stop-reason`, `:transcript-path`, `:stop-hook-active?` | `:decision :block` with a `:reason` to continue instead of stopping. Check `:stop-hook-active?` to avoid repeatedly blocking. |
| `:on-subagent-start` | `:agent-name`, `:transcript-path`, optional `:agent-display-name`, `:agent-description` | `:additional-context` for the child's first prompt |
| `:on-subagent-stop` | Subagent-start fields plus `:agent-type`, `:stop-reason` (`"end_turn"`), `:response`, optional `:agent-id` | `:modified-response` to replace the response reported to the parent, optional `:decision :allow`; `:decision :block` with a nonempty `:reason` continues the child instead. |

Return a map or `nil`, directly or via a core.async channel. Text fields are
strings and `:suppress-output?` is boolean. Omitting a handler, returning
`nil`, or throwing an ordinary exception produces **no hook output**; do not
rely on throwing to deny an operation. An invalid non-nil result (for example
an unknown key), an interrupted callback, or a worker timeout reports a
reverse request error instead. Register only the hooks your application
handles. A subscription observes events and cannot change a hook decision.

### Session filesystem

Run the [session filesystem example](../../examples/session_filesystem.clj):

```bash
./run-all-examples.sh session-filesystem
# session-filesystem => {:restored? true, :cleaned? true}
```

The example gives a named session its own in-memory store, writes a timeline
entry, then closes the session and client. A fresh client reads that entry
from history. After both clients close, the example removes its store. No
model call is needed. The [text-only provider](../../test/github/copilot_sdk/memory_fs_fixture.clj)
is an offline fixture, not a production filesystem.

On the client, `:session-fs` requires string `:initial-cwd`,
`:session-state-path`, and `:conventions` (`"posix"` or `"windows"`).
These are provider paths, not a grant of host filesystem access. With
`:empty` and no `:base-directory`, the SDK creates a private temporary
runtime home. On **each** create or resume, supply a provider map or a
factory under `[:state :create-session-fs-provider]`. A factory receives
the session handle during setup, before it can send messages. Do not
synchronously open, close, resume or delete a session on that client from
the factory. The application owns storage, tenant isolation and access checks.

Provider functions take **positional arguments**. Path and content arguments
below are strings unless marked as bytes. Mode is an integer or `nil`;
omitted `recursive?` and `force?` flags arrive as `false`. For a missing file,
throw `NoSuchFileException` or `ex-info` with `{:code "ENOENT"}`.

| Required operation | Arguments | Result |
|---|---|---|
| `:read-file` | `[path]` | Full text |
| `:write-file`, `:append-file` | `[path content mode]` | Create missing parents and write or append text; return value ignored. |
| `:exists` | `[path]` | Boolean |
| `:stat` | `[path]` | Map with booleans `:is-file` and `:is-directory`, `:size` in bytes, and ISO timestamp strings `:mtime` and `:birthtime`. |
| `:mkdir` | `[path recursive? mode]` | Create directory; return value ignored. |
| `:readdir` | `[path]` | Vector of entry names |
| `:readdir-with-types` | `[path]` | Vector of maps, e.g. `{:name "notes" :type :directory}` or `{:name "plan.md" :type :file}` |
| `:rm` | `[path recursive? force?]` | Remove path; `force?` tolerates missing paths. Return value ignored. |
| `:rename` | `[src dest]` | Move file or directory; return value ignored. |

Optional operations are off by default. Set a flag in client
`[:session-fs :capabilities]` only if **every** session provider implements it.

| Flag | Provider functions and results |
|---|---|
| `:binary` | `:read-file-bytes [path]` returns a JVM byte array; `:write-file-bytes [path bytes mode]` receives one. The SDK handles Base64 on the wire. |
| `:read-file-range` | `:read-file-range [path offset length]` returns `{:content byte-array :byte-length total-file-size}` from the same read. Use byte offsets, not character offsets. `nil` length means through EOF; zero length or an offset past EOF returns empty content. Independent of `:binary`; implement bounded reads without downloading the full file for a slice. |
| `:sqlite` | In a nested `:sqlite` map, supply `:exists []` (boolean) and `:query [query-type sql params]` (`:exec`, `:run`, or `:query`; named bindings or `nil`). Return `nil` for an empty result or a map with `:rows` (vector of row maps), `:columns` (vector of names), `:rows-affected` (nonnegative integer), and optional `:last-insert-rowid`. Optional `:transaction [statements]` runs maps with `:query-type`, `:query`, and optional `:params` atomically and returns one result map per statement, in order. |

The [framed provider tests](../../test/github/copilot_sdk/native_fs_test.clj)
exercise binary data, byte ranges, and SQLite results without requiring a
model or database service.

## Message options

Pass a prompt string or a map. Do not infer a new application permission
from an attachment, a model response, or a message's source.

| Key | Type | Default or omission |
|---|---|---|
| `:prompt` | String | Required in a message map. |
| `:timeout-ms` | Positive integer, at most 2147483647 | `send-and-wait!` only; defaults to 60000. A timeout ends the local wait, **not** the agent's work. Do not retry blindly. |
| `:mode` | `:enqueue` or `:immediate` | Omitted uses the runtime delivery mode. Immediate steering cannot request structured output. |
| `:agent-mode` | `:interactive`, `:plan`, `:autopilot`, or `:shell` | Omitted uses the session mode. |
| `:attachments` | Vector of attachment maps | Omitted sends none. Examples: `{:type :file :path path}`, `{:type :blob :data base64-text :mime-type media-type}`. |
| `:response-schema` | JSON Schema map | Omit for normal text. A structured wait requires a nonblank final root assistant response without tool requests; otherwise it fails. The result is still an event, not a parsed JSON map. |
| `:source`, `:display-prompt`, `:client-turn-id`, `:request-headers` | Source (`:user`, `:system`, agent or automation ID map), display string, application turn ID, string-to-string headers | Omit unless needed. |

## Events and history

`(copilot/subscribe session)` returns a **read channel of event maps**, not
`{:ok event}` results. Drain it while the turn runs. An event has a type such
as `:copilot/assistant.message_delta` and content at `[:data :delta-content]`.
Each subscription buffers up to 64 events. Filtering by `:type` **after**
taking, including in a downstream channel, does not reduce admission to that
buffer. Drain promptly and check for a terminal `{:error exception}` before
filtering. On overflow, `ex-data` contains `:type :subscription-overflow`
and the first undelivered `:event`; this subscription will not resume.
Subscribe again for future events and use `history` to reconcile persisted
events by ID. History cannot restore ephemeral deltas. Do not resend the turn.
Use `clojure.core.async/close!` to unsubscribe in `finally`; this neither
aborts a turn nor detaches a session. Shutdown can also end a subscription
with an error.
As on an ordinary core.async channel, a `take!` callback must not block.
For blocking work, take with `<!!` on a thread your application owns.

An `:on-event` callback differs from a subscription: callbacks run in order
for one session, and their return values are ignored. A type check inside a
callback also runs after admission to the observer's queue. Keep callbacks short; do not
return a channel expecting the SDK to await its work. A slow observer can
overflow its bounded queue. A turn's wait deadline includes delivery of its
completion event to that observer. Overflow ends the observer with an explicit error,
not the other subscribers. A callback exception is logged; it does not turn
a successful runtime response into a failed turn. Keep blocking decisions out
of event observers.

`(copilot/lifecycle-events client)` is a separate read channel for
runtime-reported **client lifecycle** notifications about sessions. Close it
to unsubscribe without detaching those sessions. Do not look for assistant
text on the lifecycle channel. The SDK does not synthesize a notification
for each local open/close/delete. Do not use the arrival of one as evidence
that an operation completed. Use the operation's own result and query
`session-metadata` or `list-sessions` for current runtime state.

The default `history` view, like a live session event, uses a keyword `:type`
and leaves the envelope's `:timestamp` as an ISO string. Selected fields
**inside `:data`** become `java.time.Instant`: `:start-time` on
`:copilot/session.start` and `:cache-expires-at` on `:copilot/assistant.usage`.
If coercion fails, the SDK retains the original data and normalizes the type.
For archival or inspection use `(copilot/await!! (copilot/history session :raw))`: this returns
the original JSON values, with string keys, timestamp strings, explicit nulls
as `nil`, and exact integers. These are decoded values, not the original bytes.

## Historical reference links

Older architecture decisions link to these 1.x names. None is a 2.x alias.

### create-session

Use `with-session` or `open-session!!`. The [1.x create-session reference](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/reference/API.md#create-session)
describes the old API.

### query-seq

Use `subscribe` within an owned session instead of the 1.x lazy sequence.
The [1.x query-seq contract](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/reference/API.md#query-seq)
applies only to that revision.

### query-chan

Use `send-and-wait!` for a request result or `subscribe` for a session event
stream. See [the 1.x query-chan contract](https://github.com/copilot-community-sdk/copilot-sdk-clojure/blob/01246672fc5fb8e8f2b50437eea6a55145e54934/doc/reference/API.md#query-chan)
if maintaining an old application.
