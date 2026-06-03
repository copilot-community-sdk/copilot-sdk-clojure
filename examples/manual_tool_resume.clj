(ns manual-tool-resume
  "Manually resolve pending tool calls across resume (upstream PR #1308).

   A tool declared without a `:handler` is *declaration-only*: the runtime
   advertises it to the model but, instead of executing anything locally, leaves
   the call pending and surfaces it to the application. With no
   `:on-permission-request` handler registered either, the permission prompt is
   likewise left pending. The application drives the work forward by hand, and
   the pending requests survive across separate client lifecycles via
   `resume-session` with `:continue-pending-work? true`:

   1. Lifecycle 1 — create a session, ask the model to use the tool, wait for the
      pending permission request, then suspend.
   2. Lifecycle 2 — resume, approve the pending permission with
      `handle-pending-permission-request!`, wait for the pending tool request,
      then suspend.
   3. Lifecycle 3 — resume, supply the tool result with
      `handle-pending-tool-call!`, and read the model's final answer.

   This is the SDK-driven analogue of the upstream `manual_tool_resume` sample.
   It demonstrates manual pending-work resolution across graceful
   **suspend/resume**, not hard crash recovery. Each lifecycle ends by suspending
   the session with `disconnect!` rather than force-killing the client: on CLI
   builds where in-flight pending requests are persisted only during graceful
   teardown, a SIGKILL (`force-stop!`) can drop the pending request ids before the
   next `resume-session` can continue them."
  (:require [clojure.core.async :refer [alts!! timeout]]
            [github.copilot-sdk :as copilot :refer [evt]]))

;; See examples/README.md for usage

(def tool
  (copilot/define-tool
    "manual_resume_status"
    {:description (str "Looks up a status value. The SDK consumer supplies the "
                       "result manually.")
     :parameters {:type "object"
                  :properties {:id {:type "string"
                                    :description "Identifier to look up"}}
                  :required ["id"]}}))
;; No :handler — the runtime leaves the call pending for manual resolution.

(defn- wait-for
  "Block (up to 120s) until an event of `type-kw` (optionally matching `pred`)
   arrives on the freshly-subscribed channel `ch`, then unsubscribe and return
   it. Subscribe BEFORE triggering the work so the event cannot be missed.
   Throws if the channel closes or the timeout elapses first."
  [session type-kw ch & [pred]]
  (let [deadline (timeout 120000)]
    (try
      (loop []
        (let [[event port] (alts!! [ch deadline] :priority true)]
          (cond
            (= port deadline)
            (throw (ex-info "Timed out waiting for session event" {:event-type type-kw}))

            (nil? event)
            (throw (ex-info "Event channel closed before expected event arrived"
                            {:event-type type-kw}))

            (and (= (:type event) type-kw) (or (nil? pred) (pred event)))
            event

            :else (recur))))
      (finally
        (copilot/unsubscribe-events! session ch)))))

(defn- resolve-pending!
  "Throw if a `handle-pending-*` call did not succeed. The whole point of the
   example is that the original request ids resolve after resume, so a silent
   `{:success false}` must surface loudly."
  [result what]
  (when-not (:success result)
    (throw (ex-info (str "Failed to resolve pending " what) {:result result})))
  result)

(defn run
  [{:keys [model] :or {model "claude-haiku-4.5"}}]
  (let [config {:model model :tools [tool]}
        ;; Lifecycle 1: ask for the tool, capture the pending permission, suspend.
        {:keys [session-id permission-id]}
        (copilot/with-client [client]
          (let [session (copilot/create-session client config)
                ;; Subscribe BEFORE sending so the permission event cannot be missed.
                permission-ch (copilot/subscribe-events session)]
            (println "Lifecycle 1: asking the model to use the declaration-only tool...")
            (copilot/send! session {:prompt (str "Use the manual_resume_status tool with id "
                                                 "'alpha', then tell me the status.")})
            (let [permission (wait-for session (evt :permission.requested) permission-ch)
                  permission-id (get-in permission [:data :request-id])]
              (println "  pending permission request:" permission-id)
              ;; Suspend gracefully so the pending request is flushed to disk.
              (copilot/disconnect! session)
              {:session-id (copilot/session-id session)
               :permission-id permission-id})))

        ;; Lifecycle 2: resume, approve the permission, capture the pending tool call, suspend.
        tool-request-id
        (copilot/with-client [client]
          (let [session (copilot/resume-session
                         client session-id (assoc config :continue-pending-work? true))
                ;; Subscribe BEFORE approving so the tool request cannot be missed.
                tool-ch (copilot/subscribe-events session)]
            (println "Lifecycle 2: resuming and approving the pending permission...")
            (resolve-pending!
             (copilot/handle-pending-permission-request!
              session {:request-id permission-id :result {:kind :approve-once}})
             "permission request")
            (let [tool-event (wait-for session (evt :external_tool.requested) tool-ch
                                       #(= (get-in % [:data :tool-name]) "manual_resume_status"))
                  tool-request-id (get-in tool-event [:data :request-id])]
              (println "  pending tool call:" tool-request-id)
              (copilot/disconnect! session)
              tool-request-id)))]

    ;; Lifecycle 3: resume, supply the tool result by hand, read the final answer.
    (copilot/with-client [client]
      (let [session (copilot/resume-session
                     client session-id (assoc config :continue-pending-work? true))
            ;; Subscribe BEFORE resolving so the answer cannot be missed.
            answer-ch (copilot/subscribe-events session)]
        (println "Lifecycle 3: resuming and supplying the tool result manually...")
        (resolve-pending!
         (copilot/handle-pending-tool-call!
          session {:request-id tool-request-id :result "MANUAL_STATUS_READY"})
         "tool call")
        (let [answer (wait-for session (evt :assistant.message) answer-ch)]
          (println "🤖:" (get-in answer [:data :content])))
        (copilot/disconnect! session)))))
