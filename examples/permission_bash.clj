(ns permission-bash
  "Approve one exact Bash call in a fresh directory and reject a different command."
  (:require [basic-chat]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [copilot.sdk :as copilot])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private hello "/usr/bin/printf 'Hello from Bash.\\n'")
(def ^:private goodbye "/usr/bin/printf 'Goodbye from Bash.\\n'")
(def ^:private refusal "Only the demo greeting is allowed.")

(defn- check-result! [session decisions dir]
  (let [history (copilot/await!! (copilot/history session))
        completions (map :data (filter #(= (:type %) :copilot/tool.execution_complete) history))
        [allowed denied] completions
        output (first (get-in allowed [:result :contents]))]
    (when-not (and (= (map (comp decisions :tool-call-id) completions) [hello goodbye])
                   (:success allowed) (= (:type output) "shell_exit")
                   (= (:cwd output) dir) (= (:exit-code output) 0)
                   (= (str/trim (:output-preview output)) "Hello from Bash.")
                   (false? (:success denied)) (= (get-in denied [:error :code]) "denied")
                   (str/includes? (get-in denied [:error :message]) refusal))
      (throw (ex-info "Expected the greeting in the demo directory, then a denied command" {})))
    {:output "Hello from Bash." :second-command :denied}))

(defn- exercise [client options dir]
  (let [decisions (atom {})
        greeting-call (atom nil)
        check-call (fn [{:keys [working-directory tool-name tool-args]} _]
                     ;; Some builtins need no permission. Reject other commands before the runtime checks.
                     (when-not (and (= working-directory dir) (= tool-name "bash")
                                    (= (set (keys tool-args)) #{:command :description})
                                    (#{hello goodbye} (:command tool-args)))
                       {:permission-decision :deny :permission-decision-reason refusal}))
        authorize (fn [{:keys [kind full-command-text command-segments tool-call-id
                               request-sandbox-bypass managed-approval-required]} _]
                    (let [in-demo? (and (= kind "shell") (string? tool-call-id)
                                        (= (mapv :working-directory command-segments) [dir])
                                        (not request-sandbox-bypass) (not managed-approval-required))
                          approve? (and in-demo? (= full-command-text hello)
                                        ;; One call can request more than one permission. Never approve a second call.
                                        (or (compare-and-set! greeting-call nil tool-call-id)
                                            (= @greeting-call tool-call-id)))]
                      (when in-demo? (swap! decisions assoc tool-call-id full-command-text))
                      (println (if approve? "Permission: approved this greeting request."
                                   "Permission: rejected the command."))
                      (if approve? {:kind :approve-once} {:kind :reject :feedback refusal})))]
    (copilot/with-session
      [session client (assoc options :working-directory dir :enable-config-discovery? false
                             :tools {:available-tools ["builtin:bash"]}
                             :hooks {:enable-repository-hooks? false
                                     :handlers {:on-pre-tool-use check-call}}
                             :permissions {:on-request authorize})]
      (doseq [command [hello goodbye]]
        (basic-chat/ask session (str "Call bash once with exactly this command and a short description. "
                                     "Omit all optional arguments. Report the result; do not retry.\n" command)))
      (check-result! session @decisions dir))))

(defn run
  "Supply an isolated client and a demo-owned state directory. No user-supplied command is accepted."
  [client options]
  (when-not (.canExecute (io/file "/usr/bin/printf"))
    (throw (ex-info "This demo requires /usr/bin/printf." {})))
  (let [parent (.toPath (io/file (:working-directory options)))
        dir (Files/createTempDirectory parent "bash-demo-" (make-array FileAttribute 0))]
    (try (exercise client options (.getCanonicalPath (.toFile dir)))
         (finally (Files/delete dir)))))
