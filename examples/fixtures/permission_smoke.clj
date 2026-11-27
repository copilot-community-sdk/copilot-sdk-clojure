(ns fixtures.permission-smoke
  (:require [byok-provider]
            [copilot.sdk :as copilot]
            [permission-bash]))

(defn -main []
  (try
    (let [dir (System/getenv "HOME")
          options (assoc (byok-provider/session-options (System/getenv)) :working-directory dir)]
      (copilot/with-client [client {:mode :empty :base-directory dir :github {:use-logged-in-user? false}}]
        (when-not (= (permission-bash/run client options) {:output "Hello from Bash." :second-command :denied})
          (throw (ex-info "Unexpected shell permission result" {})))
        (println "PASS: permission-bash")))
    (finally (shutdown-agents))))
