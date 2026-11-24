(ns github.copilot-sdk.authenticated-smoke
  "Opt-in release gate, never part of clojure.test discovery."
  (:require [clojure.string :as str]
            [github.copilot-sdk :as sdk])
  (:import [java.util UUID]))

(defn -main [home]
  (let [token (System/getenv "COPILOT_NATIVE_SMOKE_TOKEN")]
    (when (str/blank? token)
      (throw (ex-info "The protected release smoke token is not configured" {})))
    (try
      (sdk/with-client [c {:mode :empty :base-directory home :github {:use-logged-in-user? false}}]
        (sdk/with-session [s c {:tools {:available-tools []}
                                :github {:token token :telemetry-enabled? false}}]
          (let [marker (str "sdk-" (UUID/randomUUID))
                reply (sdk/await!! (sdk/send-and-wait! s {:prompt (str "Reply with exactly " marker)
                                                          :timeout-ms 60000}))]
            (when-not (and (= :copilot/assistant.message (:type reply))
                           (str/includes? (get-in reply [:data :content] "") marker))
              ;; Do not put the prompt, service reply or configuration into CI output.
              (throw (ex-info "Authenticated turn did not return the requested marker" {}))))))
      (finally (shutdown-agents)))))
