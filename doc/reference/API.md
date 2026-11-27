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
| `(with-resumed-session [session client id options] body...)` | Resume a saved session and detach it on exit. If the body and detach both fail, the body error remains primary. |
| `(open-client!! options)` | Open an owned client; call `close!!` if not using `with-client`. |
| `(open-session!! client options)` | Open an owned new session; call `close!!` if not using `with-session`. |
| `(resume-session!! client id options)` | Open an owned handle to a saved session. Use `close!!` to detach it if not using `with-resumed-session`. |
| `(session-id session)` | Return the session ID immediately, not on a channel. Save it if the application will resume. |
| `(session-info session)` | Return `:session-id` and `:capabilities`, plus `:workspace-path`, `:transcript-recovery`, and `:permission-mode-result` when supplied. A refused permission mode can include a string `:reason`. `:capabilities` is `nil` if the runtime supplied no capabilities at open or in a notification. Test individual flags with `true?`. |
| `(close!! resource)` | Detach a session or shut down a client. Client close cancels session decision and event callbacks before waiting for detach acknowledgments. A refused detach does not reopen a closing client. |
| `(force-close!! client)` | Stop waiting for detach and shutdown acknowledgments, even if graceful client close is in progress. Wait for local cleanup and the native callback safety barrier. |
| `(delete-session!! client id)` | Permanently remove an exactly listed session from the client's default runtime store and invalidate its matching open handle. Return `nil` on success. Deletion never expands a UUID prefix. |

Keep scopes open until operations that use them finish. Blocking functions
and macros do not belong inside `go`. The three scope macros retain the
primary failure when cleanup also fails. Treat a client close failure as
an error, not proof that the runtime released its resources.

Force close cannot confirm what the runtime did with an unanswered request.
Graceful close reports an error if force close interrupts a pending lifecycle
transition or prevents the shutdown acknowledgment, even when local release succeeds.
A graceful caller that joins an active force close also receives an error:
the runtime did not acknowledge graceful shutdown. Either close is a no-op
after successful release. Both callers receive an error if native release fails.

Graceful client close gives its session detaches one shared ten-second wait.
If a delete's inventory check fails before it sends the delete, close detaches
that live session without waiting for the other sessions to reply. A delete
that was already sent does not trigger another request. Close waits for the
delete result even if `session.deleted` has already made the ID available
again. The shared wait is not a deadline for later shutdown or native cleanup.

Do not shut down a client synchronously from a worker it owns. The SDK
rejects `close!!` and `force-close!!` before changing client state. The
error is `:reverse-callback-client-close` for reverse requests such as
user input or hooks, and `:callback-client-close` for tool, permission,
form, command, MCP OAuth, and `:on-event` handlers, as well as model-list
and trace-context providers. A decision handler cannot detach or delete
its own session from that worker (`:callback-session-close`). This also
applies when the SDK calls a model or trace provider on behalf of the
active handler. An ordinary `:on-event` observer can detach or delete
its session; it has no decision to return.

To hand shutdown off from an ordinary handler, signal the client owner
or start a separate `future` and return. Shutdown can cancel the handler
instead of sending its answer. Filesystem operations that supply data for
teardown must not close their own client or session, even from a
continuation carrying the provider's bindings. They can normally detach a
sibling session, but not during client shutdown: that call would wait
for the very shutdown that needs their response. An independent client
does not share these restrictions.

During graceful close, the runtime can still call the session's initialized
filesystem provider to persist data before detach or to clean up a failed
open. If the runtime refuses to detach an open session, it can still use
that provider during `runtime.shutdown`. This also applies if the runtime
accepted the session but a later setup step failed: the original error
retains the failed detach, and only the initialized filesystem stays
available. A session-end hook becomes eligible only after all required
setup requests succeed, not when the initial create or resume is acknowledged.
A successful detach or shutdown acknowledgment ends this access. The SDK
requests interruption of pending file operations without running application
stream cleanup on the response reader. It cannot stop code that ignores
interruption. Client close gives its reverse-request
workers a shared 500 ms to stop. If work still runs, `close!!` reports
`:reverse-worker-timeout` and still attempts native release. The error
describes local cleanup, not a refusal of the runtime shutdown. Use
interruptible or independently bounded waits in your providers.

The runtime can also invoke a registered `:on-session-end` hook when
it detaches a session that finished opening. If the detach wait expires,
the SDK interrupts the running end hook, stops accepting new end hooks, and
discards any unsent hook output. This does not interrupt an application
filesystem write that the runtime still needs. An end hook can close its own
session handle without joining the detach that is waiting for that hook.
This applies to the hook body and continuations that convey Clojure's
dynamic bindings (for example, `future`). If you submit a continuation to
a Java executor, construct its task with `bound-fn` **inside the hook**.
Do not synchronously close the session on an unbound executor and return
its result to the hook: the SDK cannot distinguish it from an independent
caller that must wait for detach. Do not start another lifecycle operation
on that client from the hook. Other hooks, event observers, permission
handlers, and token providers do not stay active. If an ordinary handler
has finished but its reply is still being prepared when close starts,
the SDK discards the result. This does not recall replies already submitted.

When you detach a session or delete it directly, a successful acknowledgment
stops new decisions and events for that handle before the next runtime frame.
Events accepted before the acknowledgment still go to that handle's subscriptions
and ordinary `:on-event` observer, not to a later handle with the same ID.
Close waits for publication to the channels, not for user code. The observer
can finish after close returns, subject to its queue capacity and 500 ms drain
limit. Repeating an ordinary close on the same handle does not discard those
events. If the observer itself initiated the detach, a later explicit close
from another thread can cancel that observer without affecting a replacement
session. A decision callback already running cannot submit a new decision.
A refusal leaves the handle active. Closing the whole client cancels observers
instead of requesting a session drain.

The native image stays loaded until JVM exit. A client admits at most one
handle for a given session ID. Closing an old handle cannot detach a new
one. Store and resume with the **full ID** returned by `session-id`. Short
UUID prefixes depend on the runtime's session inventory. Interrupting a
prefix lookup releases the pending lookup without sending a resume request.
An eight-character prefix can resolve a persisted session after a completed
turn; opening and detaching an unused session does not guarantee discovery.
When using a session filesystem, supply its exact ID. If the runtime refuses a session
detach, the SDK reports the refusal instead of repeating the detach. Do not
treat the session as closed. If a detach or delete result includes a refusal
reason, it is available under `:reason` in the exception data. Interrupting a caller after it
sends a detach or delete does not undo the request. The SDK waits for its
bounded result and applies an acknowledged change to the handle before
reporting interruption. If a sent create, resume, detach, or delete times out
with a known ID but no acknowledgment, that client cannot reuse the ID:
the runtime may still apply the request. A `session.deleted` notification
received after a written delete times out does not clear this restriction.
If the notification arrived first, the ID can be reused even if the
delete caller later reports a timeout waiting for its own reply. That timeout
does not reopen the old handle or discard a turn result the SDK already
received with its matching event on a session without an observer.
If a delete of an open session
times out, closing the client also closes that handle's subscriptions
before returning; this does not establish whether the data was deleted.
For application-owned storage,
the already initialized filesystem stays available for that ID, not for
other sessions. A late positive detach, a late refusal or ID mismatch on
the open, a shutdown acknowledgment, or closing the connection ends this
access. A late positive detach or delete acknowledgment also ends any
unfinished turn wait on the original handle. It does not undo the reported
timeout, make the ID reusable, or trigger another release request. An explicit
refusal of create or resume is different: the caller can retry that ID. If necessary, reconcile
saved state from a new client. A close that cannot confirm callback
safety reports an error and can defer cleanup. A native call with an unknown outcome can leave a host
quarantined. Quarantined and active hosts count against the same bounded
registry. Do not open replacement clients in a retry loop.

If native shutdown succeeded but removal of the SDK-created temporary home
failed, the host slot is already free. The SDK reports `:private-home-cleanup`
with the generated directory in `:path`. It submits one directory-only retry
to a bounded worker, including when startup failed before it returned a client.
If the retry also fails, it logs the generated directory name and exception
class. If the queue is full, the cleanup error carries the scheduling failure
as a suppressed exception. A caller with a client can close it again to retry
without repeating native shutdown. If the private home is already absent,
cleanup is complete. The SDK does not remove an application-supplied
`:base-directory`.

For deletion, use a full UUID or an ordinary name such as `build-review`.
The public runtime API can expand a short ID to a different session, so
the SDK rejects IDs that look like abbreviated UUIDs (`:ambiguous-delete-id`),
even if the complete custom name is `deadbeef`. For runtime-owned storage,
a client rejects create, resume, or delete with `:session-id-case-conflict`
if it already reserves another spelling of that ID that differs only in
letter case. A case-insensitive store can treat the two as the same session;
the SDK does not redirect a delete to the other spelling.

**Delete targets the client's default store.** The runtime's public delete
accepts an ID, not a directory. A handle opened with `:config-directory`
can be deleted by that client only if the override exactly matches its
explicit `:base-directory`. Otherwise the SDK reports
`:session-delete-store-mismatch` without sending a delete or closing the
handle. To delete a saved session in another directory, detach it, then use
a client whose `:base-directory` is that directory. With `:session-fs`,
close the session and remove its data through the application provider.

Before sending a public delete, the SDK checks the default store's
`session.list` response for one exact spelling, even when it has a handle.
If a case variant is present, it reports `:session-id-case-conflict`.
If the listing does not confirm one exact ID, it reports
`:session-delete-unconfirmed` and leaves the files intact. This also
applies to an open handle. An unnamed local session with no saved turn can
already have workspace files but no entry in `list-sessions`. Detaching
it preserves those files. A handle or `session-metadata` is not a spelling
check: the runtime can echo the ID supplied by the caller.

For a local session you might discard without sending a prompt, set
`:name` **when creating it**, detach it, then delete its exact listed ID.
The pinned runtime lists such a named session after detach without needing
a model turn. For disposable sessions, another option is an
application-owned filesystem or a dedicated `:base-directory` created
solely for that workload. Close the client before the application disposes
of its own storage. Do not remove a shared runtime home. The SDK does
not bypass an unconfirmed listing by editing the runtime's files directly.

Listing and deletion are separate runtime requests, not a transaction
with other clients; coordinate external writers that share a store.
Graceful client close waits for deletions already sent.

## Operations

An operation returns a core.async channel that delivers **one** `{:ok value}`
or `{:error exception}` and closes. `(copilot/await!! result)` blocks a caller
thread, returns the value (including `nil`), or throws. Inside `go`, take the
result with `<!` and inspect it. An event subscription is not an operation.

Interrupting `await!!` does not cancel the operation. If interruption
withdraws the wait, it throws `InterruptedException`; keep the result
channel and await it again instead of sending the operation twice. If the
wait already claimed the result, it delivers that result and preserves the
thread's interrupt status.

| Call | Successful value |
|---|---|
| `(status client)` | Map containing `:version` and `:protocol-version`. The runtime may also supply `:host-diagnostics` (experimental data). Reading status does not enable diagnostic collection. |
| `(ping client)` or `(ping client message)` | Runtime control response; fields include `:message`, `:timestamp`, and `:protocol-version` when reported. No model invocation. |
| `(auth-status client)` | Authentication status, including `:authenticated?` when reported. This does not sign in. |
| `(list-models client)` | Vector of model descriptions. Concurrent callers share one fetch, but each gets a separate result channel. Closing one channel does not cancel the fetch for other callers. Successful results are cached per client. New calls fail once shutdown starts. |
| `(list-sessions client)` or `(list-sessions client filters)` | Vector of session metadata. Optional string filters: `:cwd`, `:git-root`, `:repository`, and `:branch`. |
| `(last-session-id client)` | ID of the most recently updated session, or `nil`. |
| `(session-metadata client id)` | Pass a nonblank session ID string, not a session handle. Returns a metadata map, or `nil` if absent. An invalid ID fails only this lookup. `:start-time` and `:modified-time` are `java.time.Instant` values. |
| `(send! session message)` | Message ID when the runtime accepts the submission. It does **not** indicate completion. |
| `(send-and-wait! session message)` | Final root assistant event for this request, or `nil` if the runtime completed without an assistant response. |
| `(abort! session)` | `nil` when the runtime acknowledges the abort. This affects active session work, not just a local wait. |
| `(history session)` or `(history session view)` | Vector of events. Select `:idiom` (default) or `:raw`. |
| `(set-model! session model)` or `(set-model! session model tuning)` | `nil` after the runtime processes the model switch. `model` is a string. Omit tuning to change only the selection. |
| `(log! session message)` or `(log! session message options)` | `nil` after appending a timeline entry. Optional `:level` is `:info`, `:warning`, or `:error`; `:ephemeral? true` omits the entry from saved history. |
| `(elicit! session form)` | UI result with `:action` (`:accept`, `:decline`, or `:cancel`), optional `:content`, and optional `:_meta` with the host's original JSON keys. Requires an advertised host capability; does not read stdin. |

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
an open session reports an unsupported-host error. If the request waits
behind a trace lookup, the SDK checks again before sending the form.
Withdrawing the capability does not recall a form already submitted. A closed or replaced
handle reports `:session-closed` instead. This outbound UI request is distinct from
registering an application callback for a request **from** the runtime.
It also does not install a desktop UI scheduler. Native UI-thread scheduling
depends on the host, platform, and application event loop; the headless
examples do not test a desktop UI or an identity-provider flow.

There is no fixed SDK timeout for a human answer. Each client admits at most
64 unanswered outbound forms across its sessions. `:elicitation-capacity`
means the excess form was not sent. An answer or session retirement frees
capacity; closing a caller's result channel does not dismiss the form or
free its slot. These slots are separate from incoming form callbacks.
The result channel settles
when the host answers, the session closes or fails, or the connection ends.
A reply the SDK received first keeps its result even if you read it later.
If the session fails before detach is acknowledged, unanswered forms receive
the same failure as pending turns and event subscriptions.
A reply received after session failure or an acknowledged detach or delete
cannot turn a pending form into an acceptance. This also applies when
the release acknowledgment arrives after the caller's wait timed out,
with or without an application filesystem. Close the owning session
to abandon pending forms and cancel their application
handlers; merely stopping your local wait does not dismiss a form. An
unanswered form can occupy a form worker while the session is open.
Use a local deadline or cancellation control in the application handler if
the form needs one, and return `{:action :cancel}` to settle it.

Use that one operation for confirmation, selection, or text entry instead of
expecting three convenience functions. For example, check both the action and
the submitted boolean. Form property names in `:content` are strings for all
three actions. Content accompanying `:decline` or `:cancel` is not consent:

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
| `:base-directory` | Nonblank string | Copilot session and cache root, not a boundary for all process writes. Required for `:empty` unless you supply `:session-fs`. Use a directory private to the tenant or application; see [deployment limits](../getting-started.md#add-the-sdk-to-another-application). |
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
Do not read a Java `ThreadLocal` or `InheritableThreadLocal` in a provider
to find the caller's active span. Read the headers on the submitting thread
and store the immutable map in a Clojure dynamic var:

```clojure
(require '[github.copilot-sdk :as copilot])

(def ^:dynamic *trace-headers* {})
(def tracing {:observability {:on-get-trace-context (fn [] *trace-headers*)}})

(defn send-traced! [session headers prompt]
  (binding [*trace-headers* headers]
    (copilot/send! session prompt)))
```

Use `tracing` as part of the client options. The provider sees the binding
for that request, not the Java thread-local state of a previous request.
If all four are busy, the SDK omits the optional trace headers; it does not
queue or retry the lookup. Interrupting a create or resume during trace
lookup cancels that lookup without sending the session request. A provider
that ignores interruption still occupies its worker until it returns.
Callers sharing a model lookup receive `:provider-timeout` without waiting
for an interrupted application stream to finish closing.
Closing discovery discards the model cache and
releases the SDK's references to both application providers.

Later sends, aborts, history reads, timeline entries, UI requests, and model
changes on the same session cannot overtake a send waiting for trace headers.
If that send's trace provider tries to queue one of these operations on the
same session, it receives `:trace-provider-reentry` instead of waiting for
its own headers. This also applies to `future` calls that carry the provider's
bindings. A history read on a different session can proceed.
This orders submission, not completion. Each session permits 64 live
reservations; excess requests report `:admission-capacity`. A reservation
is not a submitted RPC. It cannot submit while detach or deletion is in
progress. A refusal leaves the handle usable for a new call; it does not
replay an unsent request. A confirmed release cancels outstanding trace
lookups and unsent reservations without waiting for event delivery or a
separate delete reply. If connection loss retires the session, its
existing reservations report that loss. New calls on the closed handle
report `:session-closed`.

The `send-and-wait!` budget starts after the session registers the wait,
before message preparation. With or without tracing, the SDK checks the
deadline under the client lock after wire conversion, when registering the
RPC as pending. This registration admits the request; writing the frame can
happen later. If the deadline has passed, the SDK sends neither the prompt
nor a cancellation. After admission, a timeout cancels only that request's
pending wait, not the agent's work.
The deadline does not interrupt preparation or lock acquisition; the call
can return its result channel after the deadline.

Explicitly closing the `send-and-wait!` result channel abandons only that
caller's wait and frees its slot. If the prompt is still waiting for trace
context, it will not be sent. If the request was already admitted, the SDK
cancels its pending reply, not the underlying turn. Use `abort!` to ask the
runtime to stop work. A finished or abandoned wait also releases its deadline
timer without waiting for the original timeout. Interrupting `await!!` alone
does not close the channel.

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
| `:extension-paths` | Vector of host-trusted standalone extension entry files. Relative paths resolve against this request's working directory. | Omission or `[]` on owning resume removes previous entries. Supplying a path does not enable extensions or grant permissions. |
| `:inference` | `:reasoning-effort` string, `:reasoning-summary` keyword, `:context-tier`, and named provider/model vectors. | The named catalog is experimental upstream. `:default-provider` accepts a name or explicit `nil`. See [Getting started](../getting-started.md) for a complete catalog. |
| `:events` | `:streaming?`, `:include-sub-agent-streaming-events?`, optional `(fn [event] ...)` at `:on-event`. | Streaming is not requested unless configured. Each client has 16 `:on-event` workers; [subscriptions](#events-and-history) do not use them. |
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

For a cloud create with no explicit ID, the SDK holds events received after
it submits the request. When the runtime assigns an ID, the `:on-event`
callback receives the retained events for that ID in order, before live
events. A subscription attached after open does not receive this batch.
Neither automatic nor manual responders can answer a request known to be
completed, hook-resolved, or aborted. A pending automatic decision waits
for any required session filesystem provider to initialize. Before starting
the handler, the SDK processes completions already queued when storage became
ready. A failed factory does not run the handler. Up to 16 decisions can wait
for storage on a session; exceeding that limit fails the session rather than
starting work without storage.
Direct host requests, such as user input or a pre-tool hook, are refused if
the required provider is not ready at receipt. They do not queue a user
decision or invoke a hook. Initial credential requests are separate: they
can precede the server-assigned ID.
Before a manual `respond!!` claims a request or a queued handler starts,
the SDK processes received events that affect decisions, including storage
readiness. Informational messages and replayed requests or aborts do not
delay decisions. A replayed completion or hook resolution still counts.
The same rule applies when sending the first callback reply or retrying a
rejected one. A live completion or abort suppresses an unsent result.
It does not wait for application handlers to finish. If the router cannot
reach that point in ten seconds, the manual reply fails without sending; an
automatic admission timeout fails a still-active session. A timeout after
a confirmed release does not discard observations already accepted. A restored
request can be answered by ID even when the runtime did not re-emit it, until
a live session-wide abort. New requests emitted after the abort can still be
answered. A checkpoint
that records completion or hook resolution rules out a new manual reply; it
also removes a handler that is still waiting to start. It does not cancel a
running handler or its original reply. A later abstention or rejection of that
reply does not reopen the settled request.

Across pending creates, each client holds at most 8,192 events and 16 MiB
of encoded frame data. To make room, it drops whole batches for the oldest
unregistered IDs and logs a warning. An oversized frame discards the held
batch for its own ID, so an earlier request cannot outlive a lost completion.
Later frames for an evicted ID can still be held and delivered. After an
eviction warning, the open-time observer may see only the tail of the stream.
An observed deletion ends the old conversation's eligibility; a refused
delete does not discard events for a conversation that still exists.
If the connection cannot queue a retained batch, open reports the failure.

Do not delete a saved ID without an open handle while this client is waiting
for a cloud create to return its ID. The delete request names only an ID;
until the allocation is identified, the SDK cannot prove the two operations
refer to different sessions. It refuses the delete without sending it. Wait
for the create result, then confirm which saved session you intend to delete.
If a create identifies the target while a permitted delete is still checking
the inventory, the create keeps the ID. The unsent delete cannot act on that
new handle.
If a create was written but its allocation remains unknown, this client
refuses later server-assigned creates and ID-only deletes without a handle
(`session_allocation_unresolved`). Early events cannot safely be assigned to
other outstanding creates, so the SDK discards their unidentified batches.
Use a fresh client and reconcile the saved sessions before deleting one.
An independent client that can delete or reuse IDs in the same store must
also coordinate with your application: the runtime delete has no generation
precondition.

If the returned ID already belongs to an open handle, the second open fails
without deleting the first. If local setup fails after the runtime
acknowledges a newly allocated cloud ID, the SDK
attempts to delete that allocation only if the ID cannot expand to another
session and the allocation belongs to the client's default runtime store.
Once the runtime confirms that release, early events for a replacement
belong to its new handle. They need not wait for the failed opener to return.
For an abbreviated UUID, an unconfirmed storage root, or an
application-owned filesystem, it sends no delete, attaches
`:session-cleanup-unconfirmed` to the setup error, and prevents that client
from reusing the ID. For an unambiguous ID in another store or an
application-owned filesystem, it still detaches the live session it acquired.
Detach does not remove the saved data. Graceful close waits for admitted
creates, even those that have not received a session ID yet, and reports
unconfirmed cleanup.
It never guesses an ID when the allocation is unconfirmed.
Closing an open session normally still detaches rather than deletes.

If a timeout withdraws a create, resume, detach, or delete before the
writer commits to sending it, the SDK does not send it later. A delete
is not committed merely because the writer took it from the queue: the
writer must first confirm it still owns the target.
Interrupting the caller after a create was sent does not cancel the remote
allocation. The SDK stops admitting callbacks for that open and waits up to
the request deadline for a reply so it can clean up a known allocation. If
the request times out or the connection fails before the server returns an
ID, the outcome is unknown: the SDK does not guess an ID and cannot
guarantee deletion.

Create and resume reserve a known ID before they call the application's
trace provider. A nested open of that ID gets `:session-already-open`; a
different ID remains available.

If an explicit create or resume returns a different ID, the SDK reports
`:session-id-mismatch` with `:session-id` and `:returned-session-id`. It
detaches the returned ID only if it can reserve it without displacing
another owner. The mismatch does not delete saved history. A failed
cleanup remains attached to the original error. If the runtime did not
even return a valid ID, the SDK does not guess which session to detach.

If create or resume **succeeded** before setup failed, only a successful
cleanup frees the ID. If the runtime refuses that cleanup or does not
acknowledge it, this client cannot reuse the ID: a callback could still
belong to the old session. Other IDs remain usable on a connected client.
If the runtime refused the **initial create or resume**, there was no
acquisition to undo. Later frames cannot call that generation's filesystem
or other callbacks, and the caller can retry the ID after rollback. These are protocol
guarantees, not a live cloud service or entitlement test.

**Configuration limits.** The named BYOK catalog supports provider/model
definitions and fixed credentials, not upstream's experimental rotating
bearer-token callback. Session `[:github :token-provider]` is a different
contract. The Clojure SDK does not expose experimental startup sandbox or
permission options, diagnostics configuration, extension-source overrides,
host-policy prechecks, or tool progress callbacks. Reading an open result or status diagnostic
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
| Either server | `:timeout` is in milliseconds. Optional `:defer-tools :auto` allows deferral behind tool search; `:never` keeps the server's tools in the initial list. Omit it for the runtime default. Server `:tools` omitted or `["*"]` includes all its tools; `[]` includes none. This is not the session's tool allowlist or a permission grant. |
| Custom agent | `:name` and `:prompt` are required. Agent `:tools` omitted or `nil` does not restrict tools further; `[]` selects none. `:model-policy :required` requires the configured `:model`; omitted policy permits fallback. Per-agent `:hooks` maps event-name strings to vectors of declarative actions, not Clojure functions. |

### Tool and permission callbacks

Put tool definitions in `[:tools :definitions]`. Use `:name`, `:description`,
a JSON Schema in `:parameters`, and a `(fn [arguments invocation] ...)`
handler. The invocation includes the session and tool-call IDs and a
`:cancelled?` function. A handler can return text. An automatic permission
callback also receives the pending `:request-id` in its invocation context.
Check the operation and its arguments before returning `{:kind :approve-once}`;
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

Each session runs up to two tool, permission, command, or MCP OAuth
callbacks at a time. A busy session queues up to 16 more in arrival order.
The client shares eight workers and up to 128 waiting requests across
sessions. Within each worker pool, a free worker takes the oldest request
whose session has a slot. Work that can start immediately does not need
space in the waiting queue.
If the SDK cannot admit more work, it returns a failure or unavailable
decision for requests that are still pending. A busy reply does not run
an application handler or grant permission. Keep callbacks short and
honor cancellation. Completion or cancellation frees a busy-reply slot
immediately if the reply has not been submitted. A submitted reply occupies
its slot until its acknowledgment, closure or timeout.

If a session exhausts its callback request history or its outstanding
busy replies, the handle stops admitting new requests. Pending turns,
forms, and event subscriptions report the same failure, even if the
application detects it in `respond!!` and no later event arrives. A later
runtime reply cannot complete an unanswered turn or form. If the connection
ends after this failure, a request still being prepared keeps that failure.
A manual answer being prepared keeps the same failure even if the handle
is then detached; the SDK does not send it.
Close the failed handle to detach it. The runtime can still use the session
filesystem and its end hook for cleanup, but ordinary callbacks stop.
Other sessions on the client can continue.

Two concurrent tools can each wait for `elicit!` on their own session.
Form handlers run on separate workers, with at most two active per session
and four per client. Forms that must wait use the same bounded waiting
queue; the SDK replies `:cancel` if there is no room to wait.
A form handler cannot call `elicit!` on **any session of the same client**
from the handler or a continuation carrying its Clojure bindings
(`:recursive-elicitation`). Do not wait for such a form on an unbound
executor either. Return the answer and ask a follow-up from the caller.
A separate client has its own form workers.

If an automatic permission handler throws or returns an invalid decision,
the SDK replies `{:kind :user-not-available}`, without attributing it to a user.
If the runtime definitively rejects a different automatic decision as invalid
parameters, the SDK sends **one** `:user-not-available` reply for that request.
It does not run the handler again. If the runtime rejects that terminal reply
as invalid parameters, the still-live request is available for manual
response. A timeout, disconnect, or negative acknowledgment does not trigger
a retry. This also applies when a full callback queue caused the SDK to send
a busy tool error or `:user-not-available` without running a handler. Only a
definitive invalid-parameters rejection permits manual repair of that
still-live request. Hook-resolved requests and replay checkpoints do not invoke the
handler; cancellation suppresses stale replies. No absent handler grants
permission.

To return an MCP tool result from a Clojure handler, use the pure function
`copilot/mcp-tool-result`. Supply a `:content` vector with string block types
`"text"`, `"image"`, or `"resource"` and an optional `:is-error?` flag. Text
is joined with newlines. Image data and resource blobs use canonical Base64;
a resource blob without a MIME type defaults to `application/octet-stream`.
The result includes `:result-type`, `:text-result-for-llm`, and binary results
only when present. Conversion does not start an MCP server.

A structured tool result may include `:skip-large-output-processing? true`
to bypass the runtime's spill-to-file processing for that result. `false`
or omission uses the session's normal large-output policy. This flag does
not approve the underlying tool call.

If the application resolves a pending request itself, use
`(copilot/respond!! session kind answer)`. It **blocks** until it receives
an affirmative runtime acknowledgment or fails; do not pass it to `await!!`.
After submission, interrupting the caller does not abandon the bounded wait
or resend the answer. The caller receives the result or error and keeps its
interrupt status. For `:tool`, supply the original
`:request-id` and exactly one of `:result` or a nonempty `:error` string.
For `:permission`, supply `:request-id` and a decision in `:result`. Do not
retry an already settled request or answer a request belonging to another
session. When the prompt advertises a confirmation option, an automatic
or manual decision can include its matching string `:selected-option-id`
on `:approve-once`, `:approve-for-session`, `:approved`, `:user-not-available`,
or `:denied-interactively-by-user`. Omit it when no option was selected.
The option ID does not attribute a decision to a person.
If `respond!!` throws a JSON-RPC invalid-parameters error (`-32602`),
you can correct the answer on the same live handle unless the request has
since completed or been aborted. Do not retry an ambiguous delivery. A
`{:kind :no-result}` abstention is not a manual resolution.

On create, omitting `:on-request` does not advertise an application permission
handler. On resume, the runtime can send **new** permission requests even
without that handler. Observe `:copilot/permission.requested` and pass its
`[:data :request-id]` to `respond!!` from an application thread. If nobody
answers, the tool can remain pending. A local wait timeout does not deny
permission or abort the turn.

After a successful detach or delete acknowledgment, a late runtime reply
cannot turn an unanswered `send-and-wait!` into success. Without an
`:on-event` observer, a turn keeps its runtime result or error if the SDK
received the reply and its matching completion event before the acknowledgment,
even if the event is still queued for publication. A missing reply or
completion fails the wait. With an observer, explicit release ends an
unfinished wait; the observer can still drain accepted events.
Do not depend on one error type for detach: the local
handle may close first, or the runtime may report a session error or an
aborted turn first. None of these outcomes approves the pending request.
An ordinary resume interrupts unfinished work; a permission request still
visible in history need not be actionable. To continue pending work, resume
with `:continue-pending-work? true`
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

Command handlers receive invocation context. An elicitation handler receives
the pending `:request-id` and optional `:_meta`; metadata member names stay
as supplied by the host. Elicitation returns `:action`
(`:accept`, `:decline`, or `:cancel`), optional `:content`, and optional
`:_meta` for negotiated extensions. Do not put image bytes in primitive
form content. `elicit!` accepts a message and form schema, not caller-supplied
request metadata. If the runtime explicitly rejects an answer as invalid
parameters, the SDK sends one cancellation without repeating the form data.

User input returns `:answer` and `:was-freeform?`. A legacy `ask_user` request can
advertise `:image-attachment-constraints`: allowed MIME types, maximum
image count, and maximum decoded bytes per image. Only then may the handler
also return an `:attachments` vector of image maps with `:type :image`,
`:mime-type` and Base64 `:data`. A text-only answer needs no change.
For an image-only answer, use `:answer ""` and `:was-freeform? true`.

Shell authentication handles `:secret`, `:text`, or `:confirm` input;
cancel with `{:action :cancel}`.
Declare `[:interaction :on-shell-authentication-request]` only if the host can
securely collect the user's response. The invocation's read-only `:cancel-ch`
closes when the response is no longer needed. A parked `core.async/take!`
listener does not run on the RPC reader. Keep the listener nonblocking or
schedule longer cleanup on an application-owned worker. A matching cancellation
received before the reply is admitted wins even if the handler has already
returned an answer. A normal handler return is not a cancellation. It
cannot be invalidated merely because its worker has not finished returning.
When a pending prompt is cancelled, the runtime receives the cancellation
answer without waiting for the application's terminal or stream to close.
The application worker still occupies its slot until the handler exits.
Cancellation cannot recall a submitted reply. Never log secret input.

A client can wait for four human responses across all its sessions: user
input, shell authentication, plan exit, and auto-mode changes share those
four slots. Each request has five minutes from receipt. If all slots are busy
or a request times out, the SDK cancels shell authentication, rejects plan
exit, declines an auto-mode switch, or reports user input unavailable. It does
not queue another question. A completed user-input, plan-exit, or auto-mode
handler frees its slot before its answer is sent, so the runtime can ask a
follow-up immediately. Filesystem, hook, token-provider, and native UI
callbacks use separate workers with a 60-second deadline. A transform
that changes several system-message sections shares one deadline across
all sections. If it expires, the SDK reports a timeout instead of sending
part of the transformed message.

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
an unknown key), an interrupted callback, a JVM `Error`, or a worker timeout reports a
reverse request error instead. All values inside a post-tool hook's
`:modified-result` must encode as JSON. Only SDK field names are converted.
Additional JSON fields and their nested names keep their spelling, including
case, punctuation and keyword namespaces. An application spec registered
under an extra field's name does not impose a schema on the tool's JSON.
If an extra wire name matches a
Clojure SDK keyword (for example, `"result-type"` alongside `"resultType"`),
the extra uses a string key. An invalid value fails that hook, not unrelated
requests on the client. Register only the hooks your application
handles. A subscription observes events and cannot change a hook decision.

### Session filesystem

Run the [session filesystem example](../../examples/session_filesystem.clj):

```bash
./run-all-examples.sh session-filesystem
# PASS: session-filesystem
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
start a lifecycle operation on that client from the factory or a continuation
carrying its bindings while the factory is active (`:session-fs-factory-reentry`).
After the factory returns, a retained continuation no longer owns its work.
Capture the intended store or tenant explicitly when you construct the
factory. SDK callback workers do not inherit the opener's Java
`InheritableThreadLocal` values, and the same worker can serve other sessions.
The application owns storage, tenant isolation and access checks.

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

An `:on-event` callback reserves one of **16 observer workers per client**,
even while its session is idle. If all workers are occupied, a create or
resume with another observer fails with `:observer-capacity`. Closing a
session lets its worker drain and exit; a blocked callback holds its slot
until it returns. This is not a limit on sessions without `:on-event`.
For more event streams on one client, omit `:on-event` and use `subscribe`
after open or resume, before sending a prompt. A subscription does not
replay events that arrived before it was attached.

Observer callbacks run in order for one session, and their return values
are ignored. A type check inside a callback also runs after admission to
the observer's queue. Keep callbacks short; do not
return a channel expecting the SDK to await its work. A slow observer can
overflow its bounded queue. A turn's wait normally includes delivery of its
completion event to that observer. One exception prevents a circular wait:
if the observer starts a follow-up on the same session and is still handling
the original event, the follow-up can return when the runtime reply and
matching event arrive. The observer still receives both events in order.
Independent callers keep the normal delivery guarantee. If shutdown cancels
delivery of the completion event, the outstanding turn fails rather than
returning a successful result. Overflow ends the observer with an explicit error,
not the other subscribers. A callback exception is logged; it does not turn
a successful runtime response into a failed turn. If application I/O throws
a nonfatal exception during interruption, the SDK logs its class without
including the application's exception message. That exception does not turn
a confirmed detach or shutdown into a failure. A fatal error during
interruption reaches the worker's uncaught-exception handler even if no
caller waits for it. Keep blocking decisions out
of event observers.

If the connection ends unexpectedly, the SDK stops accepting decisions and
new requests. Events already accepted by the reader still go to subscriptions
and ordinary observers in order. After the last accepted notification, an
observer has up to 500 ms to drain; a timeout is logged and any outstanding
delivery receipts fail. If a detach, delete, or failed open is already in
progress when the connection ends, its cleanup cancels the session observer.
A detach or delete that received no reply reports the connection error,
including when delete was still checking the session inventory. Closing
the handle after connection loss also cancels its observer. In both cases,
subscriptions keep events the reader already accepted, and a session without
an observer keeps a turn result confirmed before the loss.
When the observer itself closes its session,
the SDK stops further delivery without interrupting that callback's remaining
work. The earlier drain timer no longer applies once self-close has stopped
delivery. Until that callback returns, closing the session or client from
another thread can still interrupt it. The handle cannot be used for new work after
`close!!` returns. No detach is sent on a dead connection. Still close the
client to stop any remaining observers and release its native host. A graceful
client close cannot confirm remote shutdown after connection loss.
If `send-and-wait!` received its runtime reply and matching completion event
before EOF, it waits for observer delivery under the original deadline. If
the observer drains, the turn returns the runtime result or error. If the
drain times out or the worker stops before delivery finishes, the wait fails
with `:observer-drain-timeout` or `:observer-stopped`. An unanswered request
or a reply still missing its named event also fails.

`(copilot/lifecycle-events client)` is a separate read channel for
runtime-reported **client lifecycle** notifications about sessions. Close it
to unsubscribe without detaching those sessions. Do not look for assistant
text on the lifecycle channel. The SDK does not synthesize a notification
for each local open/close/delete. Do not use the arrival of one as evidence
that an operation completed. Use the operation's own result and query
`session-metadata` or `list-sessions` for current runtime state.

The default `history` view, like a live session event, uses a keyword `:type`
and leaves the envelope's `:timestamp` as an ISO string. If an event was
copied from another session, optional `:occurred-at` is the time it happened;
`:timestamp` is when this copy was recorded. Both are ISO strings. Selected fields
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
