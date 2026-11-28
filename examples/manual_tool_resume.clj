(ns manual-tool-resume
  "Continue one parcel lookup across three native client lifetimes."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [copilot.sdk :as copilot]
            [copilot.sdk.helpers :as h]))

(def default-prompt "Call parcel_status once with parcel NW-17. Then report its status.")

(def ^:private tool
  {:name "parcel_status" :description "Read the shipping status for demo parcel NW-17."
   :parameters {:type "object" :properties {:parcel {:type "string" :enum ["NW-17"]}}
                :required ["parcel"] :additionalProperties false}})

(defn- wait-for!! [events deadline wanted]
  (loop []
    (let [[{:keys [type data error] :as event} port] (a/alts!! [deadline events] :priority true)]
      (cond
        (= port deadline) (throw (ex-info "Timed out waiting for the parcel lookup" {:expected wanted}))
        (nil? event) (throw (ex-info "Session closed before the parcel lookup finished" {:expected wanted}))
        error (throw error)
        (seq (:agent-id event)) (recur)
        (= type :copilot/session.error) (throw (ex-info "Parcel lookup failed" data))
        (= type wanted) data
        (= type :copilot/session.idle) (throw (ex-info "The turn ended before the parcel lookup" {:expected wanted}))
        :else (recur)))))

(defn run
  "Pass client options, not an open handle. The three clients share only the
  isolated base directory; the two resumptions also need the saved request IDs."
  [client-options options prompt]
  (let [options (assoc options :tools {:available-tools ["custom:parcel_status"] :definitions [tool]})
        resume (assoc options :continue-pending-work? true)
        deadline (a/timeout 60000)
        [id permission-id call-id]
        (copilot/with-client [client client-options]
          (copilot/with-session [session client (assoc options :permissions {:on-request (constantly {:kind :no-result})})]
            (h/with-subscription [events session]
              (println "Client 1:" prompt)
              (copilot/await!! (copilot/send! session {:prompt prompt}))
              (let [{:keys [request-id permission-request]} (wait-for!! events deadline :copilot/permission.requested)]
                (when-not (and (= (select-keys permission-request [:kind :tool-name :args])
                                  {:kind "custom-tool" :tool-name (:name tool) :args {:parcel "NW-17"}})
                               (not (str/blank? request-id))
                               (not (str/blank? (:tool-call-id permission-request))))
                  (throw (ex-info "Unexpected parcel permission request" {})))
                (println "Closing client 1 with permission pending.")
                [(copilot/session-id session) request-id (:tool-call-id permission-request)]))))
        tool-id
        (copilot/with-client [client client-options]
          (copilot/with-resumed-session [session client id resume]
            (h/with-subscription [events session]
              (copilot/respond!! session :permission {:request-id permission-id :result {:kind :approve-once}})
              (let [{:keys [tool-name tool-call-id arguments request-id]} (wait-for!! events deadline :copilot/external_tool.requested)]
                (when-not (and (= tool-name (:name tool)) (= tool-call-id call-id)
                               (= arguments {:parcel "NW-17"}) (not (str/blank? request-id)))
                  (throw (ex-info "Unexpected pending parcel tool request" {})))
                (println "Client 2 approved the saved permission. Closing with the tool call pending.")
                request-id))))]
    (copilot/with-client [client client-options]
      (copilot/with-resumed-session [session client id resume]
        (h/with-subscription [events session]
          ;; The runtime need not re-emit a pending request. Answer the saved ID.
          (copilot/respond!! session :tool {:request-id tool-id :result "in transit"})
          (println "Client 3 answered the saved tool request: in transit.")
          (wait-for!! events deadline :copilot/session.idle)
          (let [history (copilot/await!! (copilot/history session))
                answer (->> history (filter #(and (= (:type %) :copilot/assistant.message)
                                                  (not (seq (:agent-id %))))) last :data :content)]
            (when (str/blank? answer) (throw (ex-info "The resumed turn ended without an answer" {})))
            (println "Assistant:" answer)
            answer))))))
