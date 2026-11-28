(ns mcp-local-server
  "List files in a demo-owned directory with a read-only stdio MCP server."
  (:require [basic-chat]
            [byok-provider]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [copilot.sdk :as copilot])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def default-prompt "Call list_files and report the file names.")
(def summary-prompt "Call list_files. Pass all returned names as the files array to summarize_files and report its summary.")

(def ^:private samples ["garden.md" "weather.csv"])

(def ^:private summarize
  {:name "summarize_files" :description "Summarize the names from list_files."
   :parameters {:type "object" :properties {:files {:type "array" :items {:type "string"}}}
                :required ["files"] :additionalProperties false}
   :handler (fn [{:keys [files]} _]
              (str (count files) " files: " (str/join ", " files) "."))})

(defn- interpreter []
  (let [python (io/file (or (System/getenv "COPILOT_DEMO_PYTHON") ""))]
    (when-not (and (.isAbsolute python) (.isFile python) (.canExecute python))
      (throw (ex-info "Set COPILOT_DEMO_PYTHON to the absolute path of a trusted, installed Python 3." {})))
    (.getPath python)))

(defn- list-samples [client options prompt summary?]
  (let [python (interpreter)
        ;; The shell removes this tree after native workers and the MCP process exit.
        directory (Files/createDirectory (.toPath (io/file (:working-directory options) "mcp-samples"))
                                         (make-array FileAttribute 0))
        script (io/file (io/resource "fixtures/directory_server.py"))
        mcp-tool "samples-list_files"
        approved (atom {})
        authorize (fn [{:keys [kind server-name tool-name tool-call-id args]} _]
                    (if (and (string? tool-call-id)
                             (or (and (= kind "mcp") (= server-name "samples") (= tool-name mcp-tool) (= args {}))
                                 (and summary? (= kind "custom-tool") (= tool-name (:name summarize))
                                      (= (set (keys args)) #{:files}) (vector? (:files args))
                                      (= (count samples) (count (:files args)))
                                      (= (set samples) (set (:files args))))))
                      (do (swap! approved assoc tool-call-id tool-name)
                          {:kind :approve-once})
                      {:kind :reject}))
        tools (cond-> {:available-tools [(str "mcp:" mcp-tool)]}
                summary? (assoc :definitions [summarize])
                summary? (update :available-tools conj "custom:summarize_files"))]
    (doseq [file samples]
      (Files/createFile (.resolve directory file) (make-array FileAttribute 0)))
    (copilot/with-session
      [session client (assoc options :tools tools :permissions {:on-request authorize}
                             :mcp {:servers {"samples" {:type :stdio :command python
                                                        :args ["-I" "-S" (.getAbsolutePath script)]
                                                        :working-directory (str directory)
                                                        :tools ["list_files"] :defer-tools :never :timeout 5000}}})]
      (let [answer (basic-chat/ask session prompt)
            history (copilot/await!! (copilot/history session))
            completed (into #{} (for [{:keys [type data]} history
                                      :when (and (= type :copilot/tool.execution_complete) (:success data))]
                                  (get @approved (:tool-call-id data))))
            expected (cond-> #{mcp-tool} summary? (conj (:name summarize)))]
        (when-not (= completed expected)
          (throw (ex-info "The model did not complete the requested tools." {:expected expected :completed completed})))
        (println "Completed tools:" (str/join ", " (sort completed)))
        answer))))

(defn run [state-dir prompt]
  (byok-provider/run state-dir prompt #(list-samples %1 %2 %3 false)))

(defn run-with-custom-tools [state-dir prompt]
  (byok-provider/run state-dir prompt #(list-samples %1 %2 %3 true)))
