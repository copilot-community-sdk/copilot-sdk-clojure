(ns github.copilot-sdk
  "Clojure client for the in-process Copilot runtime. Exchange immutable data
  over core.async channels. Resource acquisition and close block the caller."
  (:require [github.copilot-sdk.callback-results :as results]
            [github.copilot-sdk.native-client :as client]
            [github.copilot-sdk.teardown :as teardown]))

(defn open-client!!
  "Load the pinned runtime in this JVM and negotiate SDK protocol 4.
  Select :mode :coding-agent or :empty. In empty mode supply isolated
  storage. Prefer with-client when the lifetime fits a lexical scope."
  [options]
  (client/open-client! options))

(defn open-session!!
  "Create an owned session using grouped 2.x options. In :empty client mode
  supply an explicit [:tools :available-tools] allowlist, possibly []."
  [client options]
  (client/open-session! client options))

(defn resume-session!!
  "Open a new handle to a saved session. Supply live callbacks and provider
  configuration again; saved model identity is not a credential registry.
  New permission requests can be answered with respond!! without a handler.
  To continue pending work, pass :continue-pending-work? true and retain the
  original request ID; the runtime does not re-emit pending permissions."
  [client session-id options]
  (client/resume-session! client session-id options))

(defn close!!
  "Detach a session without deleting history, or shut down an owned client.
  May block or throw; never call from a go block."
  [^java.lang.AutoCloseable resource]
  (.close resource))

(defn force-close!!
  "Release an owned client without waiting for runtime detach/shutdown RPCs.
  Still waits for the native callback safety barrier; never unloads live code."
  [client]
  (client/close-client! client false))

(defmacro with-client
  "Bind an open native client for the body, then shut it down. A body failure
  remains primary if shutdown also fails."
  [[binding options] & body]
  `(let [~binding (open-client!! ~options)]
     (teardown/with-cleanup #(close!! ~binding) ~@body)))

(defmacro with-session
  "Bind a new session for the body, then detach it. Finish or cancel any
  asynchronous work that owns this session before the body returns."
  [[binding client options] & body]
  `(let [~binding (open-session!! ~client ~options)]
     (teardown/with-cleanup #(close!! ~binding) ~@body)))

(defmacro with-resumed-session
  "Bind a resumed session for the body, then detach it. A body failure
  remains primary if detach also fails."
  [[binding client id options] & body]
  `(let [~binding (resume-session!! ~client ~id ~options)]
     (teardown/with-cleanup #(close!! ~binding) ~@body)))

(defn session-id
  "Return the session ID that can be passed to resume-session!!."
  [session]
  (client/session-id session))

(defn session-info
  "Read the session ID and :capabilities (nil until reported). The workspace
  path, transcript recovery and permission mode result appear only if reported."
  [session]
  (client/session-info session))

(defn list-models
  "Read available models. Concurrent calls share a cache owned by this
  native client; a failed lookup is not cached."
  [client]
  (client/list-models client))

(defn lifecycle-events
  "Subscribe to runtime-reported client lifecycle events. Close the channel
  to unsubscribe; this does not detach any session."
  [client]
  (client/lifecycle-events client))

(defn respond!!
  "Settle a pending :tool or :permission request. Pass :request-id and
  :result (or :error for a failed tool). After submission, wait for the
  bounded outcome even if interrupted, and preserve the interrupt status."
  [session kind answer]
  (client/respond!! session kind answer))

(defn mcp-tool-result
  "Convert an MCP call result to a map that a tool handler can return.
  Supply a :content vector of blocks with string :type values (\"text\",
  \"image\", or \"resource\"). Set :is-error? to true for a failed call.
  Text is joined with newlines. Image :data and resource :blob must be
  canonical Base64. A resource blob without a :mime-type uses
  application/octet-stream; its :uri becomes the binary description."
  [result]
  (results/mcp-tool-result result))

(defn approve-all
  "Explicit permission handler for trusted, unmanaged use only. Abstains
  when the runtime requires managed approval; never enabled by default."
  [request invocation]
  (when (:managed-settings-enabled? invocation)
    (throw (ex-info "approve-all cannot be used with managed settings" {:type :managed-settings})))
  (if (and (contains? request :managed-approval-required)
           (not (false? (:managed-approval-required request))))
    {:kind :no-result}
    {:kind :approve-once}))

(defn status
  "Return the loaded runtime's version and negotiated SDK protocol version."
  [client]
  (client/status client))

(defn ping
  "Round-trip a control request to this client's runtime. Does not call a model."
  ([client] (client/ping client nil))
  ([client message] (client/ping client message)))

(defn last-session-id
  "Return a result channel for the most recently updated session ID, or nil if none."
  [client]
  (client/last-session-id client))

(defn auth-status
  "Return the runtime's authentication status on a channel. This does not
  sign in or grant access."
  [client]
  (client/auth-status client))

(defn session-metadata
  "Read metadata for one saved session on a result channel, or {:ok nil}
  when it is absent. Times are Instants; optional client names are retained."
  [client id]
  (client/session-metadata client id))

(defn list-sessions
  "Return a channel of session metadata. Optional filters use :cwd,
  :git-root, :repository and :branch."
  ([client] (client/list-sessions client {}))
  ([client filters] (client/list-sessions client filters)))

(defn delete-session!!
  "Delete a saved session from the client's default runtime store. Confirm
  its unique, exact ID with the runtime's listing before sending the delete.
  Refuse UUID prefixes, unlisted IDs (:session-delete-unconfirmed), and
  handles using another storage root. Refusal leaves the session's files intact.
  Acknowledgment invalidates the matching open handle. For application-owned
  storage, delete through the filesystem provider after closing the session."
  [client id]
  (client/delete-session!! client id))

(defn history
  "Return normalized session events, or their original JSON values with
  :raw. Raw history has string keys, original timestamps and exact integers."
  ([session] (client/history session false))
  ([session view]
   (case view
     :raw (client/history session true)
     :idiom (client/history session false)
     (throw (ex-info "Expected :raw or :idiom history" {:view view})))))

(defn send!
  "Submit a message. The channel delivers {:ok message-id} or {:error cause},
  then closes. Submission is not turn completion."
  [session message]
  (client/send! session message))

(defn send-and-wait!
  "Return this request's final root assistant event as {:ok event}, or
  {:ok nil} when the runtime completed it without an assistant response.
  Errors arrive as {:error cause}. Closing the result withdraws an unsent
  reservation or ends its admitted wait. Neither close nor timeout aborts
  a turn already admitted by the runtime connection."
  [session message]
  (client/send-and-wait! session message))

(defn abort!
  "Ask the runtime to abort active work on the session. The result channel
  delivers {:ok nil} on acknowledgment or {:error cause} on failure."
  [session]
  (client/abort! session))

(defn set-model!
  "Ask the runtime to switch the active model. Optional tuning applies only
  to this selection; it does not replace provider connections."
  ([session model] (client/set-model! session model {}))
  ([session model tuning] (client/set-model! session model tuning)))

(defn subscribe
  "Subscribe to session events. Close the subscription when done; closing
  a subscription does not abort work or detach the session."
  [session]
  (client/subscribe session))

(defn log!
  "Append a session timeline entry. Optional :level is :info, :warning or
  :error; :ephemeral? omits it from saved history."
  ([session message] (client/log! session message {}))
  ([session message options] (client/log! session message options)))

(defn elicit!
  "Ask a capable host for structured input using :message and :requested-schema.
  The result contains :action (:accept, :decline or :cancel) and optional
  :content and :_meta maps with the host's original JSON member names.
  The schema's property names belong to the application. The request waits for
  a response or for the session to close; it has no SDK timer for human input.
  A client can have 64 unanswered outbound forms. An excess request returns
  :elicitation-capacity without sending a form."
  [session form]
  (client/elicit! session form))

(defn await!!
  "Block for one operation result. Return its value (including nil) or throw
  its error. Interrupting the wait does not cancel the operation. If the take
  already claimed the result, deliver it and preserve the interrupt status.
  Inside a go block, use <! and inspect the result instead."
  [result]
  (client/await!! result))
