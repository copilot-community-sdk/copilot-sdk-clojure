(ns permission-bash
  "Example 6: Permission handling with bash

   Demonstrates:
   - permission callback for built-in tools
   - bash tool invocation with auto-approval

   Run with: clojure -A:examples -M -m permission-bash"
  (:require [clojure.core.async :as async :refer [<! <!! alts!! timeout]]
            [krukow.copilot-sdk :as copilot]))

(defn- windows?
  []
  (boolean (re-find #"(?i)windows" (System/getProperty "os.name"))))

(defn- shell-config
  []
  (if (windows?)
    {:tool "powershell"
     :command "Write-Output 'hello from powershell'"}
    {:tool "bash"
     :command "echo 'hello from bash'"}))

(defn -main [& _args]
  (println "🔐 Permission Handling Example (Bash)")
  (println "======================================\n")
  (let [cli-path (or (System/getenv "COPILOT_CLI_PATH") "copilot")
        {:keys [tool command]} (shell-config)]
    (try
      (println "📡 Starting Copilot client...")
      (copilot/with-client [client {:cli-path cli-path
                                    :log-level :info}]
        (println "✅ Connected!\n")
        (println "📝 Creating session with permission callback...")
        (copilot/with-session [session client
                               {:model "gpt-5"
                                :available-tools [tool]
                                :on-permission-request (fn [request _ctx]
                                                         (println "🔐 Permission request:" (:kind request))
                                                         (println "    Command:" (:full-command-text request))
                                                         {:kind :approved})}]
          (println (str "✅ Session created: " (copilot/session-id session) "\n"))
          (println "💬 Asking: Run a shell command and reply DONE.")
          (let [event-ch (copilot/send-async
                          session
                          {:prompt (str "Run this command with the "
                                        tool
                                        " tool, then reply with just DONE:\n\n"
                                        command)})
                deadline (timeout 60000)]
            (loop []
              (let [[event ch] (alts!! [event-ch deadline])]
                (cond
                  (= ch deadline)
                  (println "⚠️  Timed out waiting for completion.")

                  (nil? event)
                  (println "⚠️  Event stream closed.")

                  :else
                  (do
                    (println "Event:" (:type event))
                    (case (:type event)
                      :tool.execution_complete
                      (do
                        (println "🧰 Tool completed.")
                        (copilot/abort! session))
                      :assistant.message
                      (when-let [content (get-in event [:data :content])]
                        (println "🤖 Response:")
                        (println content))
                      :session.idle
                      (println "✅ Session idle.")
                      nil)
                    (when-not (= :session.idle (:type event))
                      (recur))))))))
      (println "\n✅ Done!")
      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (System/exit 1))))))
