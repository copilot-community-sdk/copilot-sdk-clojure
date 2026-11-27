(ns empty-mode
  "Run one tenant's BYOK turn with an application-owned filesystem."
  (:require [basic-chat]
            [byok-provider]
            [copilot.sdk.helpers :as h]
            [github.copilot-sdk.memory-fs-fixture :as memory-fs]
            [copilot.sdk.tool-set :as tool-set]))

(def default-prompt byok-provider/default-prompt)

(defn run [state-dir prompt]
  (let [provider (memory-fs/provider (atom {"/" nil}))
        options (assoc (byok-provider/session-options (System/getenv))
                       :working-directory "/"
                       :state {:create-session-fs-provider provider}
                       :tools {:available-tools tool-set/isolated}
                       :permissions {:on-request (constantly {:kind :reject})})]
    (try
      (let [config {:client {:mode :empty :base-directory state-dir :github {:use-logged-in-user? false}
                             :session-fs {:initial-cwd "/" :session-state-path "/session" :conventions "posix"}}
                    :session options}
            answer (h/with-conversation [session config] (basic-chat/ask session prompt))]
        (when-not ((:exists provider) "/session/events.jsonl")
          (throw (ex-info "The tenant store did not receive the session journal." {})))
        (println "The application stored this tenant's journal in memory.")
        answer)
      (finally ((:rm provider) "/session" true true)))))
