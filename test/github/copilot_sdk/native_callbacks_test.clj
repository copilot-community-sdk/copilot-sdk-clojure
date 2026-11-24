(ns github.copilot-sdk.native-callbacks-test
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk.native-callbacks :as cb]
            [github.copilot-sdk.protocol :as rpc])
  (:import [java.io ByteArrayOutputStream OutputStream PipedInputStream PipedOutputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- take!! [ch]
  (let [[v p] (a/alts!! [ch (a/timeout 5000)] :priority true)]
    (when-not (= ch p) (throw (ex-info "Test timed out" {})))
    v))

(defn- await-slot-release! [owner reg]
  ;; Receiving the frame does not mean its worker has consumed the ACK.
  (let [jobs (:jobs (get @(:sessions owner) (:id reg)))
        released (promise)]
    (add-watch jobs released (fn [_ _ _ active] (when (empty? active) (deliver released true))))
    (try
      (when-not (or (empty? @jobs) (deref released 5000 false))
        (throw (ex-info "Callback worker did not release its slot" {})))
      (finally (remove-watch jobs released)))))

(defn- with-peer [f]
  (let [in (PipedInputStream. 65536)
        peer-in (PipedInputStream. 65536)
        out (PipedOutputStream. peer-in)
        peer-out (PipedOutputStream. in)
        connect #(rpc/connect %1 %2
                              (atom {:connection (rpc/initial-connection-state)}))
        conn (connect in out)
        peer (connect peer-in peer-out)
        replies (a/chan 16)
        owner (cb/open! conn {})]
    (try
      (rpc/set-request-handler! peer (fn [method params]
                                       (a/>!! replies [method params])
                                       (a/go {:result {:success true}})))
      (f conn peer owner replies)
      (finally (cb/close! owner) (rpc/disconnect conn) (rpc/disconnect peer)))))

(defn- tool-event [id]
  {:type :copilot/external_tool.requested
   :agent-id "agent"
   :data {:session-id "s" :request-id id :tool-call-id "call" :tool-name "lookup"
          :arguments {:Keep_thisKey true} :traceparent "trace"}})

(defn- permission-event [id]
  {:type :copilot/permission.requested
   :data {:request-id id :permission-request {:kind "read"}
          :prompt-request {:kind "read"}}})

(deftest broadcasts-invoke-handlers-and-use-session-scoped-replies
  (with-peer
    (fn [_ peer owner replies]
      (let [calls (a/chan 4)
            events (a/chan 8)
            reg (cb/register!
                 owner "s"
                 {:events {:on-event #(a/>!! events %)}
                  :github {:fetch-managed-settings? true}
                  :permissions {:on-request (fn [req invocation]
                                              (a/>!! calls [req invocation])
                                              (a/go {:kind :reject :feedback "No"}))}
                  :tools {:definitions [{:name "lookup"
                                         :handler (fn [args invocation]
                                                    (a/>!! calls [args invocation])
                                                    "done")}]}})
            tool (tool-event "t")
            perm (permission-event "p")]
        (doseq [method ["tool.call" "permission.request" "sessionFs.readFile"]]
          (is (= -32601
                 (try (rpc/send-request! peer method {:session-id "s"} 5000)
                      (catch clojure.lang.ExceptionInfo e (get-in (ex-data e) [:error :code]))))))
        (cb/publish! owner reg (assoc-in (tool-event "manual") [:data :has-handler] false))
        (cb/publish! owner reg tool)
        (let [[args ctx] (take!! calls)]
          (is (= {:Keep_thisKey true} args (:arguments ctx)))
          (is (= {:request-id "t" :session-id "s" :tool-call-id "call"
                  :tool-name "lookup" :agent-id "agent" :traceparent "trace"}
                 (dissoc ctx :cancelled? :arguments))))
        (is (= ["session.tools.handlePendingToolCall"
                {:session-id "s" :request-id "t" :result "done"}] (take!! replies)))
        (cb/publish! owner reg (assoc-in (permission-event "hook") [:data :resolved-by-hook] true))
        (cb/publish! owner reg perm)
        (let [[req ctx] (take!! calls)]
          (is (= req {:kind "read"}))
          (is (= (select-keys ctx [:session-id :managed-settings-enabled? :prompt-request])
                 {:session-id "s" :managed-settings-enabled? true :prompt-request {:kind "read"}})))
        (is (= ["session.permissions.handlePendingPermissionRequest"
                {:session-id "s" :request-id "p" :result {:kind "reject" :feedback "No"}}]
               (take!! replies)))
        (is (= [(assoc-in (tool-event "manual") [:data :has-handler] false) tool
                (assoc-in (permission-event "hook") [:data :resolved-by-hook] true) perm]
               (vec (repeatedly 4 #(take!! events)))))))))

(deftest unsafe-or-missing-permission-decisions-never-approve
  (with-peer
    (fn [_ _ owner replies]
      (let [absent (cb/register! owner "s" {})]
        (cb/publish! owner absent (permission-event "absent"))
        (cb/unregister! owner absent))
      (let [reg (cb/register!
                 owner "s"
                 {:permissions {:on-request
                                (fn [req _]
                                  (case (:id req)
                                    "nil" nil
                                    "throw" (throw (ex-info "secret-credential" {}))
                                    "bad" {:kind :approve-once :only-if false}
                                    "abstain" {:kind :no-result}
                                    "yes" {:kind :approve-once}))}})]
        (doseq [[id expected] [["abstain" nil] ["nil" "user-not-available"]
                               ["throw" "user-not-available"] ["bad" "user-not-available"]
                               ["yes" "approve-once"]]]
          (cb/publish! owner reg (assoc-in (permission-event id) [:data :permission-request :id] id))
          (when expected
            (is (= ["session.permissions.handlePendingPermissionRequest"
                    {:session-id "s" :request-id id :result {:kind expected}}]
                   (take!! replies))))
          (await-slot-release! owner reg))))))

(deftest rejected-permission-decisions-fail-closed-without-repeating-the-handler
  (with-peer
    (fn [_ peer owner _]
      (let [method "session.permissions.handlePendingPermissionRequest"
            ok {:result {:success true}}
            invalid {:error {:code -32602 :message "invalid decision"}}
            unknown {:error {:code -32603 :message "delivery unknown"}}
            approval {:result {:kind "approve-once"}}
            label {:source :human-response :outcome :prompted-user :surface :sdk}
            unavailable {:result {:kind "user-not-available"}}]
        (doseq [[decision replies expected repair?]
                [[{:kind :attributed :result {:kind :approve-once} :decision-context label}
                  [invalid ok]
                  [(assoc approval :decision-context {:source "human_response" :outcome "prompted_user" :surface "sdk"})
                   unavailable] false]
                 [{:kind :approve-once} [invalid invalid] [approval unavailable] true]
                 [{:kind :user-not-available} [invalid] [unavailable] true]
                 [{:kind :approve-once} [unknown] [approval] false]
                 [{:kind :approve-once} [{:result {:success false}}] [approval] false]
                 [{:kind :approve-once} [invalid unknown] [approval unavailable] false]]]
          (let [sent (atom [])
                calls (atom 0)
                handler (fn [_ _] (swap! calls inc) decision)
                reg (cb/register! owner "s" {:permissions {:on-request handler}})]
            (rpc/set-request-handler!
             peer (fn [method params]
                    (let [n (count (swap! sent conj [method params]))]
                      (a/go (get replies (dec n) ok)))))
            (cb/publish! owner reg (permission-event "p"))
            (await-slot-release! owner reg)
            (is (= @sent (mapv #(vector method (assoc % :session-id "s" :request-id "p")) expected)))
            (is (= @calls 1))
            (let [{:keys [ok error]} (take!! (cb/respond! owner reg :permission
                                                          {:request-id "p" :result {:kind :reject}}))]
              (is (= (or ok (:type (ex-data error))) (if repair? {:success true} :not-pending))))
            (cb/unregister! owner reg)))))))

(deftest completions-and-teardown-cancel-only-their-own-invocations
  (with-peer
    (fn [conn _ owner replies]
      (let [entered (a/chan 8)
            interrupted (a/chan 8)
            latch (CountDownLatch. 1)
            old (cb/register! owner "s"
                              {:tools {:definitions
                                       [{:name "lookup"
                                         :handler (fn [_ {:keys [request-id cancelled?]}]
                                                    (a/>!! entered [request-id cancelled?])
                                                    (try (.await latch)
                                                         (catch InterruptedException _
                                                           (a/>!! interrupted request-id)))
                                                    "stale result")}]}})]
        (try
          (cb/publish! owner old (tool-event "completed"))
          (let [[id cancelled?] (take!! entered)]
            (is (= id "completed"))
            (cb/publish! owner old (tool-event id))
            (cb/publish! owner old {:type :copilot/external_tool.completed :data {:request-id id}})
            (is (true? (cancelled?)))
            (is (= id (take!! interrupted)))
            (cb/publish! owner old (tool-event id)))
          (cb/publish! owner old (tool-event "released"))
          (let [[id cancelled?] (take!! entered)]
            (cb/unregister! owner old)
            (is (= id "released"))
            (is (true? (cancelled?)))
            (is (= id (take!! interrupted))))
          (let [new (cb/register! owner "s"
                                  {:tools {:definitions
                                           [{:name "lookup"
                                             :handler (fn [_ ctx]
                                                        (if (= "eof" (:request-id ctx))
                                                          (do (a/>!! entered ["eof" (:cancelled? ctx)])
                                                              (try (.await latch)
                                                                   (catch InterruptedException _
                                                                     (a/>!! interrupted "eof"))))
                                                          "new"))}]}})]
            (cb/publish! owner old (tool-event "obsolete"))
            (cb/unregister! owner old)
            (cb/publish! owner new (tool-event "live"))
            (is (= ["session.tools.handlePendingToolCall"
                    {:session-id "s" :request-id "live" :result "new"}] (take!! replies)))
            (cb/publish! owner new (tool-event "eof"))
            (let [[id cancelled?] (take!! entered)]
              (is (= id "eof"))
              (rpc/end-input! conn)
              (is (= "eof" (take!! interrupted)))
              (is (true? (cancelled?)))
              (is (thrown? clojure.lang.ExceptionInfo (cb/register! owner "no" {})))))
          (finally (.countDown latch)))))))

(deftest callback-frames-preserve-opaque-data-and-hide-handler-errors
  (let [in (PipedInputStream.)
        server-out (PipedOutputStream. in)
        written (a/chan 4)
        buffer (ByteArrayOutputStream.)
        drain-frame (fn []
                      (let [frame (.toString buffer StandardCharsets/UTF_8)
                            body (last (str/split frame #"\r\n\r\n" 2))
                            id (:id (json/read-str body :key-fn keyword))
                            ack (json/write-str {:jsonrpc "2.0" :id id :result {:success true}})
                            bytes (.getBytes (str "Content-Length: " (count ack) "\r\n\r\n" ack)
                                             StandardCharsets/UTF_8)]
                        (.write server-out bytes) (.flush server-out)
                        (a/>!! written frame)
                        (.reset buffer)))
        out (proxy [OutputStream] []
              (write ([b] (.write buffer (int b)))
                ([b off len] (.write buffer ^bytes b (int off) (int len))))
              (flush [] (drain-frame)))
        conn (rpc/connect in out (atom {:connection (rpc/initial-connection-state)}))
        owner (cb/open! conn {})]
    (try
      (let [reg (cb/register!
                 owner "s"
                 {:interaction {:on-elicitation-request (fn [_] {:action :accept
                                                                 :content {"Row_ID" ["abc"] :Field-X false}})}
                  :tools {:definitions
                          [{:name "lookup"
                            :handler (fn [_ {:keys [request-id]}]
                                       (case request-id
                                         "ok" (a/go {:text-result-for-llm "image"
                                                     :tool-telemetry {"Scope" {:ab_X 3}}
                                                     :binary-results-for-llm
                                                     [{:type "image" :data "AQ==" :mime-type "image/png"
                                                       :metadata {:Vendor_Key [{:Item_ID false}]}}]
                                                     :result-type :success})
                                         "plain" {:text-result-for-llm "image"}
                                         "bad" (throw (ex-info "secret-from-application" {}))
                                         "assert" (throw (AssertionError. "secret assertion"))))}]}})]
        (doseq [[event rpc-method expected]
                [[(tool-event "ok") "session.tools.handlePendingToolCall"
                  {:result {:textResultForLlm "image" :toolTelemetry {:Scope {:ab_X 3}} :resultType "success"
                            :binaryResultsForLlm [{:type "image" :data "AQ==" :mimeType "image/png"
                                                   :metadata {:Vendor_Key [{:Item_ID false}]}}]}}]
                 [(tool-event "plain") "session.tools.handlePendingToolCall" {:result "{\"text-result-for-llm\":\"image\"}"}]
                 [(tool-event "bad") "session.tools.handlePendingToolCall" {:error "Tool callback failed"}]
                 [(tool-event "assert") "session.tools.handlePendingToolCall" {:error "Tool callback failed"}]
                 [{:type :copilot/elicitation.requested :data {:request-id "form" :message "Choose"}}
                  "session.ui.handlePendingElicitation" {:result {:action "accept" :content {:Row_ID ["abc"] :Field-X false}}}]]]
          (cb/publish! owner reg event)
          (let [frame (take!! written)
                {:keys [method params]} (json/read-str (last (str/split frame #"\r\n\r\n" 2))
                                                       :key-fn keyword)]
            (is (= method rpc-method))
            (is (= params (assoc expected :requestId (get-in event [:data :request-id]) :sessionId "s"))))
          (await-slot-release! owner reg)))
      (finally (cb/close! owner) (rpc/disconnect conn) (.close server-out)))))

(deftest slow-observer-isolated-from-requests-subscriptions-and-other-sessions
  (with-peer
    (fn [conn _ owner replies]
      (let [entered (CountDownLatch. 1)
            release (CountDownLatch. 1)
            terminal (promise)
            observed (promise)
            reg (cb/register! owner "s" {:events {:on-event (fn [event]
                                                              (.countDown entered)
                                                              (.await release)
                                                              (when (:error event) (deliver terminal event)))}
                                         :permissions {:on-request (fn [_ _] {:kind :reject})}})
            other (cb/register! owner "other" {:events {:on-event #(deliver observed %)}})
            event {:type :copilot/assistant.message}]
        (try
          (cb/publish! owner reg event)
          (is (.await entered 5 TimeUnit/SECONDS))
          (dotimes [i 300] (cb/publish! owner reg (assoc event :id i)))
          (cb/publish! owner other event)
          (is (= event (deref observed 5000 :timeout)))
          (is (= {:success true} (rpc/send-request! conn "session.abort" {:session-id "other"} 5000)))
          (is (= ["session.abort" {:session-id "other"}] (take!! replies)))
          (cb/publish! owner reg (permission-event "still-open"))
          (is (= ["session.permissions.handlePendingPermissionRequest"
                  {:session-id "s" :request-id "still-open" :result {:kind "reject"}}] (take!! replies)))
          (.countDown release)
          (is (= :observer-overflow (-> (deref terminal 5000 :timeout) :error ex-data :type)))
          (finally (.countDown release)))))))

(deftest interaction-broadcasts-use-their-own-result-contracts
  (with-peer
    (fn [_ _ owner replies]
      (let [label {:source :human-response :outcome :prompted-user :surface :sdk
                   :response-capability :interactive :user-feedback "checked"}
            label-wire {:source "human_response" :outcome "prompted_user" :surface "sdk"
                        :response-capability "interactive" :user-feedback "checked"}
            calls (atom [])
            reg (cb/register!
                 owner "s" {:interaction {:commands [{:name "go" :handler #(swap! calls conj %)}]
                                          :on-elicitation-request
                                          (fn [ctx] (swap! calls conj ctx)
                                            (a/go {:action :accept :content {"keep_key" false}
                                                   :decision-context label}))}
                            :mcp {:on-auth-request
                                  (fn [{:keys [reason server-name]} _]
                                    (case reason
                                      "refresh" {:kind :runtime-refresh}
                                      "initial" {:access-token "opaque" :expires-in (case server-name
                                                                                      "expired" 0 "fractional" 1.5 1)}
                                      (throw (Exception. "secret"))))}
                            :permissions {:on-request
                                          (fn [_ _] {:kind :attributed :decision-context label
                                                     :result {:kind :approve-for-location
                                                              :location-key "/work"
                                                              :approval {:kind :mcp :server-name "x"
                                                                         :tool-name nil}}})}})
            cmd {:command-name "go" :command "/go fast" :args "fast"}
            elic {:message "Choose" :requested-schema {:properties {:keep_key {:type "boolean"}}}}]
        (doseq [[event data method body]
                [[:copilot/command.execute cmd "session.commands.handlePendingCommand" {}]
                 [:copilot/command.execute (assoc cmd :command-name "unknown")
                  "session.commands.handlePendingCommand" {:error "Unknown command: unknown"}]
                 [:copilot/elicitation.requested elic "session.ui.handlePendingElicitation"
                  {:result {:action "accept" :content {:keep-key false}} :decision-context label-wire}]
                 [:copilot/mcp.oauth_required {:reason "refresh" :runtime-refresh-supported true}
                  "session.mcp.oauth.handlePendingRequest" {:result {:kind "runtimeRefresh"}}]
                 [:copilot/mcp.oauth_required {:reason "refresh" :runtime-refresh-supported false}
                  "session.mcp.oauth.handlePendingRequest" {:result {:kind "cancelled"}}]
                 [:copilot/mcp.oauth_required {:reason "initial"}
                  "session.mcp.oauth.handlePendingRequest" {:result {:kind "token" :access-token "opaque" :expires-in 1}}]
                 [:copilot/mcp.oauth_required {:reason "initial" :server-name "expired"}
                  "session.mcp.oauth.handlePendingRequest" {:result {:kind "cancelled"}}]
                 [:copilot/mcp.oauth_required {:reason "initial" :server-name "fractional"}
                  "session.mcp.oauth.handlePendingRequest" {:result {:kind "cancelled"}}]
                 [:copilot/mcp.oauth_required {:reason "reauth"}
                  "session.mcp.oauth.handlePendingRequest" {:result {:kind "cancelled"}}]
                 [:copilot/permission.requested {:permission-request {:kind "mcp"}}
                  "session.permissions.handlePendingPermissionRequest"
                  {:decision-context label-wire :result {:kind "approve-for-location" :location-key "/work"
                                                         :approval {:kind "mcp" :server-name "x" :tool-name nil}}}]]]
          (let [id (str (random-uuid))]
            (cb/publish! owner reg {:type event :data (assoc data :request-id id)})
            (is (= [method (assoc body :session-id "s" :request-id id)] (take!! replies)))
            (await-slot-release! owner reg)))
        (is (= [(assoc cmd :session-id "s") (assoc elic :session-id "s")]
               (mapv #(dissoc % :cancelled?) @calls)))))))

(deftest manual-responses-preserve-scope-and-reject-stale-claims
  (with-peer
    (fn [_ peer owner replies]
      (let [reg (cb/register! owner "s" {:continue-pending-work? true})
            reply (fn [kind opts]
                    (let [{:keys [ok error]} (take!! (cb/respond! owner reg kind opts))]
                      (if error (throw error) ok)))
            request {:request-id "p" :result {:kind :approve-for-session
                                              :approval {:kind :custom-tool :tool-name "lookup"}}}
            tool {:request-id "restored" :error "cancelled by operator"}]
        (cb/publish! owner reg (permission-event "p"))
        (doseq [invalid [(assoc request :result {:kind :no-result})
                         (assoc-in request [:result :approval :kind] :unknown)
                         (assoc-in request [:result :approval :only-under] "/project")
                         (assoc request :decision-context {:source :human-response})]]
          (is (thrown? clojure.lang.ExceptionInfo (reply :permission invalid))))
        (is (= {:success true} (reply :permission request)))
        (is (= ["session.permissions.handlePendingPermissionRequest"
                {:session-id "s" :request-id "p" :result {:kind "approve-for-session"
                                                          :approval {:kind "custom-tool" :tool-name "lookup"}}}]
               (take!! replies)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not pending" (reply :permission request)))
        (is (thrown? clojure.lang.ExceptionInfo (reply :tool (assoc tool :result "conflict"))))
        ;; Resume does not replay pending requests. Let the runtime match this ID.
        (is (= {:success true} (reply :tool tool)))
        (is (= ["session.tools.handlePendingToolCall" (assoc tool :session-id "s")] (take!! replies)))
        (cb/publish! owner reg {:type :copilot/permission.completed :data {:request-id "completed"}})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not pending"
                              (reply :permission (assoc request :request-id "completed"))))
        (rpc/set-request-handler! peer (fn [_ _] (a/go {:result {:success false}})))
        (is (= :request-not-handled
               (try (reply :tool (assoc tool :request-id "no-longer-pending"))
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (cb/unregister! owner reg)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no longer active"
                              (reply :tool (assoc tool :request-id "next"))))))))

(deftest definitive-rejection-allows-manual-correction-only-while-still-pending
  (with-peer
    (fn [_ peer owner _]
      (let [reg (cb/register! owner "s" {})
            received (a/chan 1)
            answer #(cb/respond! owner reg :permission {:request-id %1 :result {:kind %2}})
            code #(-> % take!! :error ex-data :type)]
        (rpc/set-request-handler! peer (fn [_ params]
                                         (let [ack (a/promise-chan)]
                                           (a/>!! received [params ack])
                                           ack)))
        (doseq [[id reject-code retire] [["repair" -32602 nil] ["unknown" -32603 nil]
                                         ["completed" -32602 :copilot/permission.completed]
                                         ["aborted" -32602 :copilot/abort]]]
          (cb/publish! owner reg (assoc (permission-event id) :agent-id "agent"))
          (let [result (answer id :approve-once)
                [_ ack] (take!! received)]
            (is (= (code (answer id :reject)) :not-pending))
            (when retire (cb/publish! owner reg {:type retire :agent-id "agent" :data {:request-id id}}))
            (a/put! ack {:error {:code reject-code :message "not accepted"}})
            (is (= (-> result take!! :error ex-data :error :code) reject-code))
            (let [corrected (answer id :reject)]
              (if (= id "repair")
                (let [[reply port] (a/alts!! [received corrected (a/timeout 5000)])]
                  (when-not (= port received)
                    (throw (ex-info "Corrected decision did not reach the peer" {:reply reply})))
                  (is (= (:result (first reply)) {:kind "reject"}))
                  (a/put! (second reply) {:result {:success true}})
                  (is (= (take!! corrected) {:ok {:success true}}))
                  (is (= (code (answer id :reject)) :not-pending)))
                (is (= (code corrected) :not-pending))))))))))

(deftest explicitly-rejected-interactions-cancel-once-without-resending-application-data
  (with-peer
    (fn [_ peer owner _]
      (let [reg (cb/register! owner "s" {:mcp {:on-auth-request (fn [_ _] {:access-token "fixture"})}
                                         :interaction {:on-elicitation-request (fn [_] {:action :accept
                                                                                        :content {"key" "value"}})}})
            replies (atom [])]
        (rpc/set-request-handler! peer (fn [_ {:keys [result]}]
                                         (swap! replies conj result)
                                         (a/go {:error {:code -32602 :message "invalid"}})))
        (doseq [[type expected]
                [[:copilot/mcp.oauth_required [{:kind "token" :access-token "fixture"} {:kind "cancelled"}]]
                 [:copilot/elicitation.requested [{:action "accept" :content {:key "value"}} {:action "cancel"}]]]]
          (reset! replies [])
          (cb/publish! owner reg {:type type :data {:request-id "r"}})
          (await-slot-release! owner reg)
          (is (= @replies expected)))))))

(deftest abort-fences-stale-commands-without-blocking-another-agent
  (with-peer
    (fn [_ _ owner replies]
      (let [entered (a/chan 4)
            release (CountDownLatch. 1)
            reg (cb/register! owner "s"
                              {:interaction {:commands [{:name "go" :handler
                                                         (fn [{:keys [args cancelled?]}]
                                                           (a/>!! entered [args cancelled?])
                                                           (.await release))}]}})
            event (fn [id] {:type :copilot/command.execute :agent-id id
                            :data {:request-id id :command-name "go" :command "/go" :args id}})]
        (try
          (cb/publish! owner reg (assoc (event "history") :checkpoint true))
          (doseq [id ["a" "b"]]
            (cb/publish! owner reg (event id))
            (let [[actual cancelled?] (take!! entered)]
              (is (= id actual))
              (when (= "a" id)
                (cb/publish! owner reg {:type :copilot/abort :agent-id "a"})
                (is (cancelled?))
                (cb/publish! owner reg (event "a")))))
          (.countDown release)
          (is (= ["session.commands.handlePendingCommand" {:session-id "s" :request-id "b"}] (take!! replies)))
          ;; A duplicate can arrive after the RPC has already been accepted.
          (cb/publish! owner reg (event "b"))
          (cb/publish! owner reg (event "c"))
          (is (= "c" (first (take!! entered))))
          (is (= "c" (:request-id (second (take!! replies)))))
          (finally (.countDown release)))))))

(deftest apply-patch-string-override-keeps-original-invocation-arguments
  (with-peer
    (fn [_ _ owner replies]
      (let [wrapped {:input "patch text"}
            reg (cb/register! owner "s" {:tools {:definitions
                                                 [{:name "apply_patch" :overrides-built-in-tool? true
                                                   :parameters {"type" "string"}
                                                   :handler (fn [input {:keys [arguments]}]
                                                              (is (= wrapped arguments))
                                                              input)}]}})]
        (cb/publish! owner reg (update (tool-event "patch") :data assoc :tool-name "apply_patch" :arguments wrapped))
        (is (= ["session.tools.handlePendingToolCall" {:session-id "s" :request-id "patch" :result "patch text"}]
               (take!! replies)))))))

(deftest unregister-cancels-manual-wait-and-removes-pending-rpc
  (with-peer
    (fn [conn peer owner _]
      (let [seen (promise)
            ack (a/chan 1)
            reg (cb/register! owner "s" {})]
        (rpc/set-request-handler! peer (fn [_ _] (deliver seen true) ack))
        (try
          (let [wait (cb/respond! owner reg :tool {:request-id "p" :result "ok"})]
            (is (= true (deref seen 5000 :timeout)))
            (cb/unregister! owner reg)
            (is (= :session-closed (-> wait take!! :error ex-data :type)))
            (is (empty? (get-in @(:state-atom conn) [:connection :pending-requests]))))
          (finally (a/close! ack)))))))

(deftest observer-receipt-tracks-that-sessions-ordered-delivery
  (with-peer
    (fn [_ _ owner _]
      (let [gate (CountDownLatch. 1)
            seen (atom [])
            entered (promise)
            reg (cb/register! owner "s" {:events {:on-event (fn [e]
                                                              (deliver entered true)
                                                              (.await gate)
                                                              (swap! seen conj (:id e)))}})]
        (try
          (cb/publish! owner reg {:id "message" :type :copilot/assistant.message})
          (is (= true (deref entered 5000 :timeout)))
          (let [receipt (cb/publish! owner reg {:id "idle" :type :copilot/session.idle})]
            (is (nil? (a/poll! receipt)))
            (.countDown gate)
            (is (= {:ok nil} (take!! receipt) (take!! receipt)))
            (cb/unregister! owner reg)
            (is (= ["message" "idle"] @seen)))
          (finally (.countDown gate)))))))

(deftest busy-handlers-answer-safely-without-starving-another-session
  (with-peer
    (fn [_ _ owner replies]
      (let [entered (CountDownLatch. 2)
            tool-calls (atom #{})
            gate (CountDownLatch. 1)
            deferred (a/chan)
            slow (cb/register! owner "s" {:tools {:definitions [{:name "lookup"
                                                                 :handler (fn [_ {:keys [request-id]}]
                                                                            (swap! tool-calls conj request-id)
                                                                            (.countDown entered)
                                                                            (if (= request-id "channel")
                                                                              deferred
                                                                              (.await gate)))}]}
                                          :permissions {:on-request (fn [_ _] (throw (AssertionError.)))}})
            fast (cb/register! owner "fast" {:permissions {:on-request (fn [_ _] {:kind :reject})}})]
        (try
          (doseq [id ["thread" "channel"]] (cb/publish! owner slow (tool-event id)))
          (is (.await entered 5 TimeUnit/SECONDS))
          (dotimes [i 16] (cb/publish! owner slow (tool-event (str "waiting" i))))
          (cb/publish! owner slow (permission-event "busy"))
          (is (= ["session.permissions.handlePendingPermissionRequest"
                  {:session-id "s" :request-id "busy" :result {:kind "user-not-available"}}] (take!! replies)))
          (cb/publish! owner fast (permission-event "normal"))
          (is (= ["session.permissions.handlePendingPermissionRequest"
                  {:session-id "fast" :request-id "normal" :result {:kind "reject"}}] (take!! replies)))
          (cb/publish! owner slow {:type :copilot/abort})
          (cb/publish! owner slow (permission-event "assert"))
          (is (= ["session.permissions.handlePendingPermissionRequest"
                  {:session-id "s" :request-id "assert" :result {:kind "user-not-available"}}] (take!! replies)))
          (await-slot-release! owner slow)
          (is (= #{"thread" "channel"} @tool-calls))
          (is (nil? (a/poll! replies)))
          (finally (.countDown gate) (a/close! deferred)))))))

(deftest withheld-overload-acknowledgements-fail-only-the-affected-session
  (with-peer
    (fn [conn peer owner _]
      (let [busy-received (CountDownLatch. 16)
            withheld (a/promise-chan)
            app-result (a/chan)
            slow (cb/register! owner "s" {:permissions {:on-request (fn [_ _] app-result)}})
            other (cb/register! owner "other" {})]
        (rpc/set-request-handler!
         peer (fn [_ {:keys [session-id]}]
                (if (= session-id "s")
                  (do (.countDown busy-received) withheld)
                  (a/go {:result {:success true}}))))
        (try
          (dotimes [i (+ 2 16 16)] (cb/publish! owner slow (permission-event (str i))))
          (is (.await busy-received 5 TimeUnit/SECONDS))
          (is (= :callback-overflow (try (cb/publish! owner slow (permission-event "overflow"))
                                         (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
          ;; Release the simulated runtime's reverse-RPC workers before it
          ;; processes the independent session's next request.
          (a/put! withheld {:result {:success false}})
          (is (= {:ok {:success true}} (take!! (cb/respond! owner other :tool {:request-id "q" :result "ok"}))))
          (is (= {:success true} (rpc/send-request! conn "session.abort" {:session-id "other"} 5000)))
          (finally (a/put! withheld {:result {:success true}}) (a/close! app-result)))))))

(deftest tool-burst-drains-in-fifo-order-without-replaying-completed-work
  (with-peer
    (fn [_ _ owner replies]
      (let [entered (a/chan 16)
            release-a (CountDownLatch. 1)
            release-b (CountDownLatch. 1)
            reg (cb/register! owner "s" {:tools {:definitions
                                                 [{:name "lookup" :handler
                                                   (fn [_ {:keys [request-id]}]
                                                     (a/>!! entered request-id)
                                                     (case request-id
                                                       "a" (.await release-a)
                                                       "b" (.await release-b)
                                                       nil)
                                                     request-id)}]}})
            other (cb/register! owner "other" {:permissions {:on-request (fn [_ _] {:kind :reject})}})]
        (try
          (doseq [id ["a" "b"]] (cb/publish! owner reg (tool-event id)))
          (is (= #{"a" "b"} (set (repeatedly 2 #(take!! entered)))))
          (doseq [id ["c" "retired" "d" "d" "e"]] (cb/publish! owner reg (tool-event id)))
          (cb/publish! owner reg {:type :copilot/external_tool.completed :data {:request-id "retired"}})
          (cb/publish! owner other (permission-event "independent"))
          (is (= ["session.permissions.handlePendingPermissionRequest"
                  {:session-id "other" :request-id "independent" :result {:kind "reject"}}] (take!! replies)))
          (.countDown release-b)
          (doseq [id ["b" "c" "d" "e"]]
            (is (= ["session.tools.handlePendingToolCall" {:session-id "s" :request-id id :result id}]
                   (take!! replies))))
          (is (= ["c" "d" "e"] (vec (repeatedly 3 #(take!! entered)))))
          (.countDown release-a)
          (is (= "a" (get-in (take!! replies) [1 :result])))
          (await-slot-release! owner reg)
          (is (nil? (a/poll! entered)))
          (finally (.countDown release-a) (.countDown release-b)))))))

(deftest unregister-discards-queued-jobs-before-reusing-session-id
  (with-peer
    (fn [_ _ owner replies]
      (let [entered (CountDownLatch. 2)
            exited (CountDownLatch. 2)
            release (CountDownLatch. 1)
            calls (atom [])
            reg (cb/register! owner "s" {:tools {:definitions
                                                 [{:name "lookup" :handler
                                                   (fn [_ {:keys [request-id]}]
                                                     (swap! calls conj request-id)
                                                     (.countDown entered)
                                                     (try (.await release)
                                                          (catch InterruptedException _ nil)
                                                          (finally (.countDown exited)))
                                                     "stale")}]}})]
        (try
          (doseq [id ["one" "two"]] (cb/publish! owner reg (tool-event id)))
          (is (.await entered 5 TimeUnit/SECONDS))
          (doseq [id ["three" "four"]] (cb/publish! owner reg (tool-event id)))
          (cb/unregister! owner reg)
          (is (.await exited 5 TimeUnit/SECONDS))
          (let [replacement (cb/register! owner "s" {:permissions {:on-request (fn [_ _] {:kind :reject})}})]
            (cb/publish! owner replacement (permission-event "current"))
            (is (= ["session.permissions.handlePendingPermissionRequest"
                    {:session-id "s" :request-id "current" :result {:kind "reject"}}] (take!! replies)))
            (is (= ["one" "two"] (sort @calls))))
          (finally (.countDown release)))))))

(deftest any-released-worker-can-run-another-sessions-eligible-request
  (with-peer
    (fn [_ _ owner replies]
      (let [started (CountDownLatch. 8)
            release-others (CountDownLatch. 1)
            dependency (a/promise-chan)
            target (cb/register! owner "s" {:tools {:definitions
                                                    [{:name "lookup" :handler
                                                      (fn [_ {:keys [request-id]}]
                                                        (if (= "first" request-id)
                                                          (do (.countDown started) dependency)
                                                          (do (a/put! dependency "unblocked") "second")))}]}})
            others (mapv #(cb/register! owner (str "other-" %)
                                        {:permissions {:on-request (fn [_ _]
                                                                     (.countDown started)
                                                                     (.await release-others)
                                                                     {:kind :reject})}})
                         (range 7))]
        (try
          (cb/publish! owner target (tool-event "first"))
          (doseq [[i reg] (map-indexed vector others)]
            (cb/publish! owner reg (permission-event (str i))))
          (is (.await started 5 TimeUnit/SECONDS))
          (cb/publish! owner target (tool-event "second"))
          (.countDown release-others)
          (doseq [reg others] (await-slot-release! owner reg))
          (let [frames (repeatedly 9 #(take!! replies))]
            (is (= {"first" "unblocked" "second" "second"}
                   (into {} (for [[_ {:keys [session-id request-id result]}] frames :when (= session-id "s")]
                              [request-id result])))))
          (finally (.countDown release-others) (a/close! dependency)))))))

(deftest client-waiting-bound-applies-to-sessions-without-a-worker
  (with-peer
    (fn [_ _ owner replies]
      (let [gate (CountDownLatch. 1)
            started (CountDownLatch. 8)
            sessions (mapv (fn [i]
                             (cb/register! owner (str i)
                                           {:permissions {:on-request (fn [_ _]
                                                                        (.countDown started)
                                                                        (.await gate)
                                                                        {:kind :reject})}}))
                           (range 9))
            occupied (take 8 sessions)
            newcomer (peek sessions)]
        (try
          (doseq [reg occupied] (cb/publish! owner reg (permission-event "held")))
          (is (.await started 5 TimeUnit/SECONDS))
          (doseq [reg occupied i (range 16)] (cb/publish! owner reg (permission-event (str i))))
          (cb/publish! owner newcomer (permission-event "over-limit"))
          (is (= ["session.permissions.handlePendingPermissionRequest"
                  {:session-id "8" :request-id "over-limit" :result {:kind "user-not-available"}}] (take!! replies)))
          (cb/publish! owner (first occupied) {:type :copilot/permission.completed :data {:request-id "0"}})
          (cb/publish! owner newcomer (permission-event "within-limit"))
          (doseq [reg occupied] (cb/unregister! owner reg))
          (.countDown gate)
          (is (= ["session.permissions.handlePendingPermissionRequest"
                  {:session-id "8" :request-id "within-limit" :result {:kind "reject"}}] (take!! replies)))
          (finally (.countDown gate)))))))

(deftest automatic-tool-result-can-fall-back-on-explicit-rejection-only
  (with-peer
    (fn [_ peer owner _]
      (let [calls (atom {})
            sent (atom [])
            rejected {:error {:code -32602 :message "private rejection details"}}
            stale (a/promise-chan)
            stale-sent (promise)
            reg (cb/register! owner "s" {:tools {:definitions
                                                 [{:name "lookup" :handler
                                                   (fn [_ {:keys [request-id]}]
                                                     (swap! calls update request-id (fnil inc 0))
                                                     (when (= request-id "failed-handler")
                                                       (throw (ex-info "private details" {})))
                                                     "private result")}]}})]
        (rpc/set-request-handler!
         peer (fn [_ {:keys [request-id result] :as params}]
                (swap! sent conj (select-keys params [:request-id :result :error]))
                (cond
                  (= "replaced" request-id) (do (deliver stale-sent true) stale)
                  (= "gone" request-id) (a/go {:result {:success false}})
                  (= "ambiguous" request-id) (a/go {:error {:code -32603 :message "outcome unknown"}})
                  (or (= "private result" result)
                      (and (#{"repair" "failed-handler"} request-id) (:error params))) (a/go rejected)
                  :else (a/go {:result {:success true}}))))
        (doseq [id ["fallback" "repair" "gone" "ambiguous"]]
          (cb/publish! owner reg (tool-event id))
          (await-slot-release! owner reg)
          (let [first-frame {:request-id id :result "private result"}
                fallback {:request-id id :error "Runtime rejected the tool result"}]
            (is (= (if (#{"fallback" "repair"} id) [first-frame fallback] [first-frame])
                   (filterv #(= id (:request-id %)) @sent)))))
        (is (= {:ok {:success true}} (take!! (cb/respond! owner reg :tool {:request-id "repair" :result "corrected"}))))
        (cb/publish! owner reg (tool-event "failed-handler"))
        (await-slot-release! owner reg)
        (is (= (filterv #(= "failed-handler" (:request-id %)) @sent)
               [{:request-id "failed-handler" :error "Tool callback failed"}]))
        (is (= (take!! (cb/respond! owner reg :tool {:request-id "failed-handler" :result "corrected"}))
               {:ok {:success true}}))
        (doseq [id ["fallback" "gone" "ambiguous"]]
          (is (= :not-pending (-> (cb/respond! owner reg :tool {:request-id id :result "again"})
                                  take!! :error ex-data :type))))
        (cb/publish! owner reg (tool-event "replaced"))
        (is (= true (deref stale-sent 5000 :timeout)))
        (cb/unregister! owner reg)
        (let [replacement (cb/register! owner "s" {})]
          (a/put! stale rejected)
          (is (= {:ok {:success true}} (take!! (cb/respond! owner replacement :tool {:request-id "live" :result "ok"}))))
          (is (= [{:request-id "replaced" :result "private result"}] (filterv #(= "replaced" (:request-id %)) @sent))))
        (is (= {"fallback" 1 "repair" 1 "gone" 1 "ambiguous" 1 "replaced" 1 "failed-handler" 1} @calls))))))
