(ns tool-integration
  (:require [clojure.string :as str]
            [github.copilot-sdk :as copilot]))

(defn run [client options prompt]
  (let [tickets {"NW-17" "ready"}
        called? (atom false)
        tool {:name "ticket_status" :description "Read one ticket from the example catalog."
              :parameters {:type "object" :properties {:ticket {:type "string"}}
                           :required ["ticket"] :additionalProperties false}
              :handler (fn [{:keys [ticket]} _]
                         (reset! called? true)
                         (let [status (get tickets ticket "unknown")]
                           (println "Tool ticket_status:" ticket "=>" status)
                           status))}
        authorize (fn [{:keys [kind tool-name args]} _]
                    (if (and (= kind "custom-tool") (= tool-name (:name tool))
                             (= (set (keys args)) #{:ticket}) (contains? tickets (:ticket args)))
                      (do (println "Permission: approved one ticket_status lookup for" (:ticket args))
                          {:kind :approve-once})
                      (do (println "Permission: rejected a request outside the ticket catalog")
                          {:kind :reject})))]
    (copilot/with-session
      [session client (assoc options :tools {:available-tools ["custom:ticket_status"]
                                             :definitions [tool]}
                             :permissions {:on-request authorize})]
      (println "You:" prompt)
      (let [reply (copilot/await!! (copilot/send-and-wait! session {:prompt prompt :timeout-ms 60000}))
            answer (get-in reply [:data :content])]
        (when (str/blank? answer)
          (throw (ex-info "The model returned no ticket answer" {})))
        (println "Assistant:" answer)
        (when-not @called?
          (throw (ex-info "The model answered without calling ticket_status" {})))
        answer))))
