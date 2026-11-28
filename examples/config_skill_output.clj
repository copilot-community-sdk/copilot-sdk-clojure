(ns config-skill-output
  "Disable a demo skill and inspect what the runtime does with a large custom tool result."
  (:require [basic-chat]
            [clojure.java.io :as io]
            [copilot.sdk :as copilot])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def default-prompt "Call big_output once with no arguments, then reply with just DONE.")

(defn run
  "Pass a private :working-directory. Its owner removes it after closing the client."
  [client options prompt]
  (when-not (:working-directory options)
    (throw (ex-info "Supply a private working directory for this demo" {})))
  (let [attrs (make-array FileAttribute 0)
        parent (.toAbsolutePath (.toPath (io/file (:working-directory options))))
        root (Files/createTempDirectory parent "skill-demo-" attrs)
        skills (.resolve root "skill-catalog")
        skill (Files/createDirectories (.resolve skills "garden-label") attrs)
        output (Files/createDirectory (.resolve root "output") attrs)
        payload (.repeat "0123456789\n" 10000)
        called (atom false)
        tool {:name "big_output" :description "Generate a large text sample for this demo."
              :parameters {:type "object" :properties {} :additionalProperties false}
              :handler (fn [_ _]
                         (reset! called true)
                         (println "Generated" (count payload) "bytes of ASCII text.")
                         payload)}
        loaded (promise)
        observe (fn [{:keys [type data]}]
                  (when (= :copilot/session.skills_loaded type)
                    (when-let [entry (some #(when (= "garden-label" (:name %)) %) (:skills data))]
                      (deliver loaded entry))))
        config (-> options
                   (assoc :config-directory (str root) :working-directory (str root)
                          :enable-config-discovery? false
                          :skills {:enabled? true :directories [(str skills)] :disabled-skills ["garden-label"]}
                          :permissions {:on-request (fn [{:keys [kind tool-name args]} _]
                                                      (if (and (= kind "custom-tool") (= tool-name (:name tool)) (= {} args))
                                                        {:kind :approve-once}
                                                        {:kind :reject}))}
                          :tools {:available-tools ["custom:big_output"] :definitions [tool]
                                  :large-output {:enabled? true :max-size-bytes 2000
                                                 :output-directory (str output)}})
                   (assoc-in [:events :on-event] observe))]
    (spit (.toFile (.resolve skill "SKILL.md"))
          (str "---\nname: garden-label\ndescription: Write a label for a kitchen herb.\n---\n"
               "Give the plant name and one care tip in a single line.\n")
          :encoding "UTF-8")
    (println "Tool output above" (get-in config [:tools :large-output :max-size-bytes]) "bytes is saved to a file.")
    (copilot/with-session [session client config]
      (let [answer (basic-chat/ask session prompt)
            entry (deref loaded 5000 nil)
            [spill & others] (.listFiles (.toFile output))]
        (when-not (false? (:enabled entry))
          (throw (ex-info "The runtime did not report the demo skill as disabled" {})))
        (when-not @called
          (throw (ex-info "The model did not call big_output" {})))
        (when-not (and spill (empty? others) (= (.length spill) (count payload))
                       (= (slurp spill :encoding "UTF-8") payload))
          (throw (ex-info "Expected one complete tool result in the configured output directory" {})))
        (println "Disabled skill:" (:name entry))
        (println "Verified" (.length spill) "bytes in" (.getAbsolutePath spill))
        {:disabled-skill (:name entry) :spill-bytes (.length spill) :answer answer}))))
