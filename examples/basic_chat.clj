(ns basic-chat
  (:require [github.copilot-sdk :as copilot]))

(defn run [client options prompt]
  (copilot/with-session [session client options]
    (let [reply (copilot/await!! (copilot/send-and-wait! session {:prompt prompt
                                                                  :timeout-ms 30000}))]
      (get-in reply [:data :content]))))
