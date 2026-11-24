(ns tool-integration
  (:require [github.copilot-sdk :as copilot]))

(defn run [client options prompt]
  (let [tickets {"NW-17" "ready"}
        tool {:name "ticket_status" :description "Read one ticket from the example catalog."
              :parameters {:type "object" :properties {:ticket {:type "string"}}
                           :required ["ticket"] :additionalProperties false}
              :handler (fn [{:keys [ticket]} _] (get tickets ticket "unknown"))}
        authorize (fn [{:keys [kind tool-name args]} _]
                    (if (and (= kind "custom-tool") (= tool-name (:name tool))
                             (= (set (keys args)) #{:ticket}) (contains? tickets (:ticket args)))
                      {:kind :approve-once}
                      {:kind :reject}))]
    (copilot/with-session
      [session client (assoc options :tools {:available-tools ["custom:ticket_status"]
                                             :definitions [tool]}
                             :permissions {:on-request authorize})]
      (-> (copilot/send-and-wait! session {:prompt prompt :timeout-ms 30000})
          copilot/await!! :data :content))))
