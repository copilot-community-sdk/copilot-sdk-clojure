(ns session-events
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [github.copilot-sdk :as copilot]))

(defn run [client options prompt]
  (copilot/with-session [session client (assoc-in options [:events :streaming?] true)]
    (let [events (copilot/subscribe session)]
      (try
        (println "You:" prompt)
        (print "Assistant: ")
        (flush)
        (let [reply (copilot/send-and-wait! session {:prompt prompt :timeout-ms 60000})]
          (loop [parts []]
            (let [[value port] (a/alts!! [events reply] :priority true)]
              (cond
                (:error value) (throw (:error value))
                (nil? value) (throw (ex-info "Channel closed before the turn finished" {}))
                (= port reply) (let [answer (get-in value [:ok :data :content])]
                                 (when (str/blank? answer)
                                   (throw (ex-info "The model returned no final answer" {})))
                                 (when (empty? parts) (print answer))
                                 (println)
                                 {:streamed (apply str parts) :final answer})
                (= (:type value) :copilot/assistant.message_delta)
                (let [delta (get-in value [:data :delta-content])]
                  (print delta)
                  (flush)
                  (recur (conj parts delta)))
                :else (recur parts)))))
        (finally (a/close! events))))))
