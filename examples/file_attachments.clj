(ns file-attachments
  "Send a whole-file reference or a text selection from a private sample file."
  (:require [basic-chat]
            [clojure.java.io :as io]
            [copilot.sdk :as copilot])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn selection [client options prompt]
  (let [note "The community garden grows lemon thyme."
        path (Files/createTempFile (.toPath (io/file (:working-directory options)))
                                   "garden-note-" ".txt" (make-array FileAttribute 0))]
    (try
      (spit (.toFile path) (str note "\nThe next workday is Saturday.\n") :encoding "UTF-8")
      (println "Attachment:" (str (.getFileName path)))
      (copilot/with-session [session client options]
        (basic-chat/ask session {:prompt prompt
                                 :attachments [{:type :selection :file-path (str path) :display-name (str (.getFileName path))
                                                :text note :selection-range {:start {:line 0 :character 0}
                                                                             :end {:line 0 :character (count note)}}}]}))
      (finally (Files/delete path)))))

(defn run [client options prompt]
  (let [path (Files/createTempFile (.toPath (io/file (:working-directory options)))
                                   "sample-deps-" ".edn" (make-array FileAttribute 0))
        read? (atom false)
        tool {:name "read_attachment" :description "Read the single attached demo manifest."
              :parameters {:type "object" :properties {} :additionalProperties false}
              :handler (fn [_ _] (reset! read? true) (slurp (.toFile path) :encoding "UTF-8"))}
        permit (fn [{:keys [kind tool-name args]} _]
                 (if (and (= kind "custom-tool") (= tool-name (:name tool)) (= args {}))
                   {:kind :approve-once} {:kind :reject}))]
    (try
      (spit (.toFile path) "{:deps {org.clojure/data.json {:mvn/version \"2.5.1\"}}}" :encoding "UTF-8")
      (println "Attached manifest:" (.getFileName path))
      (copilot/with-session [session client (assoc options :tools {:available-tools ["custom:read_attachment"] :definitions [tool]}
                                                   :permissions {:on-request permit})]
        (let [answer (basic-chat/ask session {:prompt prompt :attachments [{:type :file :path (str path)}]})]
          (when-not @read? (throw (ex-info "The model did not read the attached manifest" {})))
          answer))
      (finally (Files/delete path)))))
