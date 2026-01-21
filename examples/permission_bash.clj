(ns permission-bash
  "Example 6: Permission handling with bash

   Demonstrates:
   - permission callback for built-in tools
   - bash tool invocation with auto-approval

   Run with: clojure -A:examples -M -m permission-bash"
  (:require [clojure.core.async :as async :refer [<!]]
            [krukow.copilot-sdk :as copilot]
            [clojure.pprint :as pprint]))

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
        {:keys [tool command]} (shell-config)
        denied-command (str command " && echo 'denied'")
        allowed-commands #{command}]
    (try
      (println "📡 Starting Copilot client...")
      (copilot/with-client [client {:cli-path cli-path
                                    :log-level :debug}]
        (println "✅ Connected!\n")
        (println "📝 Creating session with permission callback...")
        (copilot/with-session [session client
                               {:model "gpt-5.2"
                                :available-tools [tool]
                                :on-permission-request (fn [request _ctx]
                                                         (println "🔐 Permission request:")
                                                         (pprint/pprint request)
                                                         (if (contains? allowed-commands (:full-command-text request))
                                                           {:kind :approved}
                                                           {:kind :denied-by-rules
                                                            :rules [{:kind "shell"
                                                                     :argument (:full-command-text request)}]}))}]
          (println (str "✅ Session created: " (copilot/session-id session) "\n"))
          (let [ch (copilot/subscribe-events session)]
            (async/go-loop []
              (when-let [event (<! ch)]
                (println "Event:" (:type event))
                (recur))))
          (println "💬 Asking: Run an allowed shell command and reply DONE.")
          (let [response (copilot/send-and-wait!
                          session
                          {:prompt (str "Run this command with the "
                                        tool
                                        " tool, then reply with just DONE:\n\n"
                                        command)})]
            (println "🤖 Allowed response:")
            (println (get-in response [:data :content])))
          (println "\n💬 Asking: Run a denied shell command and reply DONE.")
          (let [response (copilot/send-and-wait!
                          session
                          {:prompt (str "Run this command with the "
                                        tool
                                        " tool, then reply with just DONE:\n\n"
                                        denied-command)})]
            (println "🤖 Denied response:")
            (println (get-in response [:data :content])))))
      (println "\n✅ Done!")
      (catch Exception e
        (println (str "❌ Error: " (.getMessage e)))
        (System/exit 1)))))
