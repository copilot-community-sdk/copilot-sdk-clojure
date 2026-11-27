(ns session-events
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [copilot.sdk :as copilot]
            [copilot.sdk.helpers :as h]))

(defn callback-stream [client options prompt]
  (let [text (atom "")
        observe (fn [{:keys [type data error]}]
                  (when error (binding [*out* *err*] (println "Live display stopped:" (:type (ex-data error)))))
                  (when (= type :copilot/assistant.message_delta)
                    (let [delta (:delta-content data)]
                      (swap! text str delta)
                      (print delta) (flush))))]
    (copilot/with-session [session client (assoc options :events {:streaming? true :on-event observe})]
      (println "You:" prompt)
      (print "Live: ") (flush)
      (let [reply (copilot/send-and-wait!! session {:prompt prompt :timeout-ms 60000})
            answer (get-in reply [:data :content])]
        (when (str/blank? answer) (throw (ex-info "The model returned no final answer" {})))
        (println)
        (println "Final:" answer)
        {:streamed @text :final answer}))))

(defn run [client options prompt]
  (copilot/with-session [session client (assoc-in options [:events :streaming?] true)]
    (h/with-subscription [events session]
      (println "You:" prompt)
      (print "Live: ")
      (flush)
      (let [reply (copilot/start-turn! session {:prompt prompt :timeout-ms 60000})]
        (try
          (loop [parts [] counts (sorted-map)]
            (let [[value port] (a/alts!! [events reply] :priority true)]
              (when-let [error (:error value)] (throw error))
              (when-not value (throw (ex-info "Channel closed before the turn finished" {})))
              (if (= port reply)
                (let [answer (get-in value [:ok :data :content])]
                  (when (str/blank? answer) (throw (ex-info "The model returned no final answer" {})))
                  (println)
                  (println "Final:" answer)
                  (println "Session events observed:" counts)
                  {:streamed (apply str parts) :final answer})
                (let [delta (when (= (:type value) :copilot/assistant.message_delta) (get-in value [:data :delta-content]))]
                  (when delta (print delta) (flush))
                  (recur (cond-> parts delta (conj delta)) (update counts (:type value) (fnil inc 0)))))))
          (finally (a/close! reply)))))))
