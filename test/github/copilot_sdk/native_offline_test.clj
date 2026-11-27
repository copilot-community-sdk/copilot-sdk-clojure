(ns github.copilot-sdk.native-offline-test
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.native-offline-fixture :as fixture]))

(defn- client-options []
  {:mode :empty :base-directory (System/getenv "HOME") :github {:use-logged-in-user? false}})

(defn- session-options [base-url]
  {:model "fixture/chat" :tools {:available-tools []} :events {:streaming? true}
   :inference {:providers [{:name "fixture" :provider-type :openai :base-url base-url}]
               :models [{:id "chat" :provider "fixture" :model-id "gpt-4o" :wire-model "probe-deployment"}]}
   :github {:capi {:enabled? false}}})

(defn- run-turn! [base-url prompt options]
  (sdk/with-client [client (client-options)]
    (sdk/with-session [session client (merge (session-options base-url) options)]
      (let [event (sdk/await!! (sdk/send-and-wait! session {:prompt prompt}))]
        (when-not (and (map? event) (map? (:data event)))
          (throw (ex-info "Expected a Clojure event map" {:class (type event)})))
        {:type (:type event) :content (get-in event [:data :content])}))))

(def ^:private tool-name "fixture_lookup")
(def ^:private tickets
  [["call-fixture-17" "NW-17" "zone:17/northwest"]
   ["call-fixture-18" "SE-18" "zone:18/southeast"]
   ["call-fixture-19" "N-19" "zone:19/north"]])

(defn- permission [id ticket]
  {:kind "custom-tool" :tool-call-id id :tool-name tool-name :args {:ticketCode ticket}})

(defn- fixture-tools [handler]
  {:available-tools [(str "custom:" tool-name)]
   :definitions [{:name tool-name :description "Look up a fixture ticket."
                  :parameters {:type "object" :properties {:ticketCode {:type "string"}} :required ["ticketCode"]}
                  :handler handler}]})

(defn- tool-turn! [base-url prompt]
  (let [calls (atom [])
        permissions (atom [])
        lookup (into {} (map (fn [[_ ticket result]] [ticket result])) tickets)
        allowed (into #{} (map (fn [[id ticket]] (permission id ticket))) tickets)
        handler (fn [arguments {:keys [tool-call-id tool-name session-id cancelled?]}]
                  (swap! calls conj {:args arguments :tool-call-id tool-call-id :tool-name tool-name
                                     :session-id session-id :cancelled? (cancelled?)
                                     :worker (.getName (Thread/currentThread))})
                  {:result-type :success :skip-large-output-processing? true
                   :text-result-for-llm (get lookup (:ticketCode arguments) "unknown ticket")})
        authorize (fn [request ctx]
                    (let [scope (select-keys request [:kind :tool-call-id :tool-name :args])]
                      (swap! permissions conj (merge scope (select-keys ctx [:session-id :request-id])))
                      (if (allowed scope)
                        {:kind :approve-once}
                        {:kind :reject})))
        event (run-turn! base-url prompt
                         {:session-id "fixture-tool-session"
                          :permissions {:on-request authorize}
                          :tools (fixture-tools handler)})]
    {:event event :calls @calls :permissions @permissions}))

(defn- request-id!! [requested turn call-id ticket]
  (let [[data port] (a/alts!! [requested turn] :priority true)]
    (when-not (= port requested)
      (throw (ex-info "Turn ended before a manual permission request" data)))
    (when-not (= (select-keys (:permission-request data) [:kind :tool-call-id :tool-name :args])
                 (permission call-id ticket))
      (throw (ex-info "Unexpected permission request" data)))
    (:request-id data)))

(defn- permission-observer [requested]
  (fn [{:keys [type data]}]
    (when (= type :copilot/permission.requested) (a/put! requested data))))

(defn- manual-resume-turn! [base-url prompt]
  (sdk/with-client [client (client-options)]
    (let [[call-id ticket result] (first tickets)
          opts (session-options base-url)
          session-id (sdk/with-session [s client opts]
                       (sdk/await!! (sdk/send-and-wait! s {:prompt prompt :timeout-ms 15000}))
                       (sdk/session-id s))
          requested (a/promise-chan)
          opts (-> opts (assoc :tools (fixture-tools (fn [_ _] result)))
                   (assoc-in [:events :on-event] (permission-observer requested)))]
      (with-open [s (sdk/resume-session!! client session-id opts)]
        (let [turn (sdk/send-and-wait! s {:prompt prompt :timeout-ms 15000})
              id (request-id!! requested turn call-id ticket)]
          {:ack (sdk/respond!! s :permission {:request-id id :result {:kind :approve-once}})
           :content (get-in (sdk/await!! turn) [:data :content])})))))

(defn- check-detached-waiter! [waiter session-id]
  (let [deadline (a/timeout 5000)
        [outcome port] (a/alts!! [waiter deadline] :priority true)
        cause (:error outcome)
        {:keys [type method error] :as data} (ex-data cause)]
    (when-not (= port waiter)
      (throw (ex-info "Detached waiter did not settle" {:session-id session-id})))
    ;; Rust may report an aborted turn or a closed session before local handle retirement.
    (when-not (or (and (= type :session-closed) (= (:session-id data) session-id))
                  (= type :turn-aborted)
                  (and (= method "session.sendAndWait") (= (:code error) -32603)
                       (= (:message error) "The session was closed or replaced while waiting for the response")))
      (throw (ex-info "Expected a detach failure from the original wait" {:outcome outcome} cause)))
    (when-not (= [nil waiter] (a/alts!! [waiter deadline] :priority true))
      (throw (ex-info "Detached waiter did not close after one outcome" {:session-id session-id})))))

(defn- continue-permission! [base-url prompt]
  (let [[call-id ticket result] (first tickets)
        calls (atom [])
        opts (assoc (session-options base-url) :tools
                    (fixture-tools (fn [args ctx] (swap! calls conj [(:tool-call-id ctx) args]) result)))
        [session-id request-id]
        (sdk/with-client [c (client-options)]
          (let [requested (a/promise-chan)
                [sid rid waiter]
                (sdk/with-session [s c (-> opts (assoc :permissions {:on-request (constantly {:kind :no-result})})
                                           (assoc-in [:events :on-event] (permission-observer requested)))]
                  (let [turn (sdk/send-and-wait! s {:prompt prompt :timeout-ms 15000})]
                    [(sdk/session-id s) (request-id!! requested turn call-id ticket) turn]))]
            (check-detached-waiter! waiter sid)
            [sid rid]))
        replayed (atom [])
        done (promise)
        observe (fn [{:keys [type data]}]
                  (case type
                    :copilot/permission.requested (swap! replayed conj :event)
                    :copilot/assistant.message (when (= (:content data) "Lookup confirmed.") (deliver done true))
                    nil))
        resumed (-> opts (assoc :continue-pending-work? true
                                :permissions {:on-request (fn [_ _] (swap! replayed conj :handler) {:kind :reject})})
                    (assoc-in [:events :on-event] observe))
        [ack history] (sdk/with-client [c (client-options)]
                        (with-open [s (sdk/resume-session!! c session-id resumed)]
                          (let [ack (sdk/respond!! s :permission {:request-id request-id :result {:kind :approve-once}})]
                            (when-not (deref done 15000 false) (throw (ex-info "Restored turn did not finish" {})))
                            [ack (sdk/await!! (sdk/history s))])))]
    {:ack ack :calls @calls :replayed @replayed
     :permission-history (mapv :type (filter #(= request-id (get-in % [:data :request-id])) history))
     :content (->> history (filter #(= :copilot/assistant.message (:type %))) last :data :content)}))

(defn -main
  ([base-url prompt] (-main "text" base-url prompt))
  ([mode base-url prompt]
   (try
     (spit "result.edn" (pr-str (case mode
                                  "text" (run-turn! base-url prompt {})
                                  "tool" (tool-turn! base-url prompt)
                                  "manual-resume" (manual-resume-turn! base-url prompt)
                                  "continue-permission" (continue-permission! base-url prompt))))
     (finally (shutdown-agents)))))

(deftest ^:native native-offline-turn
  (let [{:keys [event requests]} (fixture/exercise-turn!
                                  ["-m" "github.copilot-sdk.native-offline-test"]
                                  (constantly [[{:role "assistant" :content "Native "}
                                                {:content "turn complete."}] "stop"]))
        request (first requests)
        last-message (last (:messages request))]
    (is (= (count requests) 1))
    (is (= (select-keys request [:model :stream]) {:model "probe-deployment" :stream true}))
    (is (= (:role last-message) "user"))
    ;; The runtime can prepend a timestamp and context to the user message.
    (is (= (last (str/split-lines (:content last-message))) fixture/prompt))
    (is (= event {:type :copilot/assistant.message :content fixture/answer}))))

(defn- tool-model [calls history]
  (let [request (peek history)]
    (case (count history)
      1 [[{:role "assistant"
           :tool_calls (mapv (fn [i [id ticket]]
                               {:index i :id id :type "function"
                                :function {:name tool-name :arguments (json/write-str {:ticketCode ticket})}})
                             (range) calls)}]
         "tool_calls"]
      2 (let [tool-messages (filter #(= (:role %) "tool") (:messages request))]
          (when-not (= (frequencies (map (juxt :tool_call_id :content) tool-messages))
                       (frequencies (map (fn [[id _ result]] [id result]) calls)))
            (throw (ex-info "Unexpected model-facing tool result" {:tool-messages tool-messages})))
          [[{:role "assistant" :content "Lookup confirmed."}] "stop"])
      (throw (ex-info "Unexpected model request" {:count (count history)})))))

(deftest ^:native native-offline-custom-tool-turn
  (let [{{:keys [event calls permissions]} :event requests :requests}
        (fixture/exercise-turn! ["-m" "github.copilot-sdk.native-offline-test" "tool"] (partial tool-model tickets))]
    (is (= (mapv #(get-in % [:function :name]) (:tools (first requests))) [tool-name]))
    (is (= (count requests) 2))
    (let [ids (map :request-id permissions)]
      (is (every? #(and (string? %) (not (str/blank? %))) ids))
      (is (= (count (set ids)) (count tickets))))
    (is (= (frequencies (map #(dissoc % :request-id) permissions))
           (frequencies (map (fn [[id ticket]] (assoc (permission id ticket) :session-id "fixture-tool-session"))
                             tickets))))
    (is (= (frequencies calls)
           (frequencies (map (fn [[id ticket]] {:args {:ticketCode ticket} :tool-call-id id :tool-name tool-name
                                                :session-id "fixture-tool-session" :cancelled? false
                                                :worker "copilot-native-request"})
                             tickets))))
    (is (= event {:type :copilot/assistant.message :content "Lookup confirmed."}))))

(deftest ^:native native-offline-handlerless-resume-permission
  (let [{:keys [event]} (fixture/exercise-turn!
                         ["-m" "github.copilot-sdk.native-offline-test" "manual-resume"]
                         (fn [history]
                           (if (= 1 (count history))
                             [[{:role "assistant" :content "Ready to resume."}] "stop"]
                             (tool-model (take 1 tickets) (subvec history 1)))))]
    (is (= event {:ack {:success true} :content "Lookup confirmed."}))))

(deftest ^:native native-offline-continue-pending-permission
  (let [{:keys [event]} (fixture/exercise-turn!
                         ["-m" "github.copilot-sdk.native-offline-test" "continue-permission"]
                         (partial tool-model (take 1 tickets)))
        [call-id ticket] (first tickets)]
    (is (= event {:ack {:success true}
                  :calls [[call-id {:ticketCode ticket}]] :replayed []
                  :permission-history [:copilot/permission.requested :copilot/permission.completed]
                  :content "Lookup confirmed."}))))
