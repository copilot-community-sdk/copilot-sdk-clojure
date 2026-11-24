(ns session-filesystem
  (:require [github.copilot-sdk :as copilot]
            [github.copilot-sdk.memory-fs-fixture :as memory-fs]))

(defn run [options]
  (let [tree (atom {"/" nil})
        provider (memory-fs/provider tree)
        client-options {:mode :empty :github {:use-logged-in-user? false}
                        :session-fs {:initial-cwd "/" :session-state-path "/session" :conventions "posix"}}
        options (assoc options :working-directory "/" :state {:create-session-fs-provider provider})
        message "Stored in the application's session filesystem."]
    (try
      (let [id (copilot/with-client [client client-options]
                 (copilot/with-session [session client (assoc options :name "Filesystem example")]
                   (copilot/await!! (copilot/log! session message))
                   (copilot/session-id session)))]
        (when-not ((:exists provider) "/session/events.jsonl")
          (throw (ex-info "No journal in the application store" {:session-id id})))
        ;; A fresh runtime must read the first client's journal from the provider.
        (copilot/with-client [client client-options]
          (with-open [session (copilot/resume-session!! client id options)]
            (let [history (copilot/await!! (copilot/history session))]
              (when-not (some #(and (= :copilot/session.info (:type %))
                                    (= message (get-in % [:data :message]))) history)
                (throw (ex-info "The saved timeline entry was not restored" {:session-id id})))))))
      (finally
        ;; Both clients have closed. The application owns this single-session store.
        ((:rm provider) "/session" true true)))
    {:restored? true :cleaned? (= @tree {"/" nil})}))
