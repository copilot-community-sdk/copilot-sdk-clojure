(ns elicitation-provider
  "Example: Acting as an elicitation provider.

   When an MCP server or sub-agent needs user input (e.g., OAuth consent,
   configuration choices), the runtime sends an elicitation request to the
   SDK client. This example shows how to handle those requests.

   The example simulates a scenario where an MCP server triggers an OAuth
   consent flow — the handler prints the request and auto-approves it."
  (:require [clojure.core.async :refer [chan tap go-loop <!]]
            [github.copilot-sdk :as copilot :refer [evt]]))

;; See examples/README.md for usage

(defn handle-elicitation
  "Handle an elicitation request from the runtime.
   In a real app this would render a UI dialog or open a browser.
   Here we print the request and auto-approve.
   Takes a single ElicitationContext map (upstream PR #960)."
  [{:keys [session-id message mode elicitation-source url requested-schema] :as _context}]
  (println "\n📋 Elicitation request received!")
  (println "   Session:" session-id)
  (println "   Message:" message)
  (when mode
    (println "   Mode:" mode))
  (when elicitation-source
    (println "   Source:" elicitation-source))
  (when url
    (println "   URL:" url))
  (when requested-schema
    (println "   Schema:" (pr-str requested-schema)))

  ;; Decide how to respond based on mode
  (case mode
    ;; URL mode: the server wants us to open a browser
    "url"
    (do
      (println "   → Auto-approving URL-based elicitation (would open browser)")
      {:action "accept"})

    ;; Form mode (or nil): the server wants form field values
    (if-let [props (get-in requested-schema [:properties])]
      (do
        (println "   → Auto-filling form fields:")
        (let [content (reduce-kv
                       (fn [acc field-name field-schema]
                         (let [field-type (get field-schema "type" (:type field-schema))
                               value (case field-type
                                       "boolean" true
                                       "string" "auto-filled"
                                       ("number" "integer") 42
                                       "auto-filled")]
                           (println (str "     " field-name " (" field-type "): " value))
                           (assoc acc (keyword field-name) value)))
                       {}
                       props)]
          {:action "accept" :content content}))
      (do
        (println "   → No schema provided, approving without content")
        {:action "accept"}))))

(defn run
  "Run a session configured as an elicitation provider.

   The agent is asked to interact with an MCP server that may trigger
   elicitation requests. Even if no elicitation is triggered (model's
   choice), the handler is registered and ready."
  [_]
  (println "=== Elicitation Provider Example ===")
  (println "This example shows how to handle elicitation requests from MCP servers.\n")
  (println "The session registers an :on-elicitation-request handler that auto-approves")
  (println "any elicitation requests (OAuth consent, form inputs, etc.).\n")

  (copilot/with-client-session [session {:on-permission-request copilot/approve-all
                                         :model "claude-haiku-4.5"
                                         :on-elicitation-request handle-elicitation}]
    ;; Check if elicitation capability is advertised
    (println "Elicitation supported:" (copilot/elicitation-supported? session))

    (let [events-ch (chan 256)
          done (promise)]
      (tap (copilot/events session) events-ch)

      (go-loop []
        (when-let [event (<! events-ch)]
          (condp = (:type event)
            (evt :assistant.message)
            (println "\n🤖 Agent:" (get-in event [:data :content]))

            (evt :elicitation.requested)
            (println "\n🔔 Elicitation event observed:" (get-in event [:data :message]))

            (evt :capabilities.changed)
            (println "\n🔄 Capabilities changed:" (:data event))

            (evt :session.idle)
            (deliver done true)

            (evt :session.error)
            (do
              (println "❌ Error:" (get-in event [:data :message]))
              (deliver done (ex-info "Session error" {:event event})))

            nil)
          (recur)))

      (let [prompt "Say hello and tell me what capabilities this session has."]
        (println "📤 You:" prompt)
        (copilot/send! session {:prompt prompt}))

      (let [result @done]
        (when (instance? Exception result)
          (throw result))
        (println "\n=== Session Complete ===")))))
