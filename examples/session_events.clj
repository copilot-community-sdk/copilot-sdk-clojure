(ns session-events
  (:require [clojure.core.async :as a]
            [github.copilot-sdk :as copilot]))

(defn run [client options prompt]
  (copilot/with-session [session client (assoc-in options [:events :streaming?] true)]
    (let [events (copilot/subscribe session)]
      (try
        (let [reply (copilot/send-and-wait! session {:prompt prompt :timeout-ms 30000})]
          (loop [parts []]
            (let [[value port] (a/alts!! [events reply] :priority true)]
              (cond
                (:error value) (throw (:error value))
                (nil? value) (throw (ex-info "Channel closed before the turn finished" {}))
                (= port reply) {:streamed (apply str parts) :final (get-in value [:ok :data :content])}
                (= (:type value) :copilot/assistant.message_delta)
                (recur (conj parts (get-in value [:data :delta-content])))
                :else (recur parts)))))
        (finally (a/close! events))))))
