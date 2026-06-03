(ns manual-tool-resume
  "Demonstrates manually resolving a pending tool call (upstream PR #1308).

   A tool declared without a `:handler` is *declaration-only*: the runtime
   advertises it to the model but, instead of executing anything locally, leaves
   the call pending and surfaces it to the application. With no
   `:on-permission-request` handler registered either, the permission prompt is
   likewise left pending. The application drives the work forward by hand:

   1. Ask the model to use the tool and wait for the pending permission request.
   2. Resolve it with `handle-pending-permission-request!` (approve-once) and
      wait for the resulting external tool request.
   3. Supply the tool result with `handle-pending-tool-call!` and read the
      model's final answer.

   This is the SDK-driven analogue of the upstream `manual_tool_resume` sample.
   The same pending requests can also be resolved after `resume-session` with
   `:continue-pending-work? true` when the originating CLI process persists them;
   this example keeps everything in one client lifecycle so it runs
   deterministically against any CLI build."
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
        (let [[event port] (alts!! [ch deadline])]
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

(defn run
  [{:keys [model] :or {model "claude-haiku-4.5"}}]
  (copilot/with-client-session
    [session {:model model
              :tools [tool]}]
    ;; Step 1: ask for the tool, capture the pending permission request.
    (let [permission-ch (copilot/subscribe-events session)]
      (println "Step 1: asking the model to use the declaration-only tool...")
      (copilot/send! session {:prompt (str "Use the manual_resume_status tool with id "
                                           "'alpha', then tell me the status.")})
      (let [permission (wait-for session (evt :permission.requested) permission-ch)
            permission-id (get-in permission [:data :request-id])
            ;; Subscribe BEFORE approving so the tool request cannot be missed.
            tool-ch (copilot/subscribe-events session)]
        (println "  pending permission request:" permission-id)

        ;; Step 2: approve the pending permission, capture the pending tool call.
        (println "Step 2: approving the pending permission...")
        (copilot/handle-pending-permission-request!
         session {:request-id permission-id :result {:kind :approve-once}})
        (let [tool-event (wait-for session (evt :external_tool.requested) tool-ch
                                   #(= (get-in % [:data :tool-name]) "manual_resume_status"))
              tool-request-id (get-in tool-event [:data :request-id])
              ;; Subscribe BEFORE resolving so the answer cannot be missed.
              answer-ch (copilot/subscribe-events session)]
          (println "  pending tool call:" tool-request-id)

          ;; Step 3: supply the tool result by hand, read the final answer.
          (println "Step 3: supplying the tool result manually...")
          (copilot/handle-pending-tool-call!
           session {:request-id tool-request-id :result "MANUAL_STATUS_READY"})
          (let [answer (wait-for session (evt :assistant.message) answer-ch)]
            (println "🤖:" (get-in answer [:data :content]))))))))
