(ns elicitation-provider
  "Serve an application form in-process. No model, browser, or stdin is needed."
  (:require [clojure.core.async :as a]
            [copilot.sdk :as copilot]))

(def ^:private form
  {:message "Choose a color for the sample card."
   :requested-schema {:type "object" :properties {:color {:type "string" :enum ["Blue" "Green"]}}}})

(defn handle-request [{:keys [message mode requested-schema url elicitation-source]}]
  (cond
    (or (some? url) (#{"url" :url} mode))
    (do (println "Declined a URL request. No browser was opened.")
        {:action :decline})

    (and (nil? elicitation-source) (or (nil? mode) (#{"form" :form} mode))
         (= message (:message form)) (= requested-schema (:requested-schema form)))
    (do (println "Form:" message)
        {:action :accept :content {"color" "Blue"}})

    :else {:action :decline}))

(defn run [client options]
  (let [served? (atom false)
        accept-card (fn [ctx]
                      (let [answer (handle-request ctx)]
                        (when (= :accept (:action answer)) (reset! served? true))
                        answer))]
    (copilot/with-session
      [session client (assoc options :tools {:available-tools []}
                             :interaction {:on-elicitation-request accept-card})]
      ;; Detaching the session on timeout also abandons the unanswered form.
      (let [reply (copilot/elicit! session form)
            [result port] (a/alts!! [reply (a/timeout 10000)] :priority true)]
        (when-not (= port reply)
          (throw (ex-info "The host did not answer the card form" {})))
        (when-let [error (:error result)] (throw error))
        (when-not (and @served? (= (:ok result) {:action :accept :content {"color" "Blue"}}))
          (throw (ex-info "The demo provider did not fill the card form" {})))
        (println "Card color:" (get-in result [:ok :content "color"]))
        (:ok result)))))
