(ns github.copilot-sdk.integration.upstream-1-0-92-4-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.generated.event-specs :as gen]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! await-value! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.session :as session]
            [github.copilot-sdk.specs :as specs])
  (:import [java.util Base64]))

(use-fixtures :each with-mock-server)

(defn- read-result! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 15000)])]
    (when-not (= port channel)
      (throw (ex-info "Timed out reading SDK result" {})))
    (when (instance? Throwable value)
      (throw value))
    value))

(defn- open-session [copilot-client operation config]
  (if (#{:create :create-channel} operation)
    (case operation
      :create (sdk/create-session copilot-client config)
      :create-channel (read-result! (sdk/<create-session copilot-client config)))
    (let [seed (sdk/create-session *test-client* {})
          session-id (sdk/session-id seed)]
      (sdk/disconnect! seed)
      (case operation
        :resume (sdk/resume-session copilot-client session-id config)
        :resume-channel (read-result! (sdk/<resume-session copilot-client session-id config))
        :join (:session
               (with-redefs-fn
                 {#'client/foreground-session-id (constantly session-id)
                  #'client/client (constantly copilot-client)}
                 #(sdk/join-session config)))))))

(defn- completed-channel [value]
  (let [channel (async/chan 1)]
    (when (some? value)
      (async/>!! channel value))
    (async/close! channel)
    channel))

(deftest subagent-hooks-round-trip-through-every-session-builder
  (doseq [operation [:create :create-channel :resume :resume-channel :join]]
    (let [received (atom [])
          output (atom nil)
          root-stops (atom 0)
          requests (atom [])
          _ (mock/set-request-hook! *mock-server*
                                    (fn [method params]
                                      (swap! requests conj [method params])))
          handler (fn [input invocation]
                    (swap! received conj [input invocation])
                    (completed-channel @output))
          copilot-session
          (open-session *test-client* operation
                        {:hooks {:on-subagent-start handler
                                 :on-subagent-stop handler
                                 :on-agent-stop (fn [_ _] (swap! root-stops inc))}})
          session-id (sdk/session-id copilot-session)
          base-input {:sessionId session-id
                      :timestamp 1700000000000
                      :cwd "/workspace"
                      :transcriptPath "/state/events.jsonl"
                      :agentName "explore"}]
      (try
        (is (true? (:hooks (second (last (filter #(contains? #{"session.create" "session.resume"}
                                                             (first %))
                                                 @requests))))))
        (doseq [[method params] @requests
                :when (= method "session.options.update")]
          (is (not (contains? params :hooks))))
        (doseq [[hook-type extra-input outputs]
                [["subagentStart" {}
                  [nil {} {:additional-context ""} {:additional-context "Read the whole file."}]]
                 ["subagentStop" {:agentId "" :agentType "explore"
                                  :stopReason "end_turn" :response "First line"}
                  [nil {} {:modified-response ""}
                   {:decision "allow" :modified-response "Reviewed"}
                   {:decision "block" :reason "Read the rest."
                    :modified-response "Ignored when blocked"}]]]
                expected outputs]
          (reset! output expected)
          (let [response
                (mock/send-rpc-request!
                 *mock-server* "hooks.invoke"
                 {:sessionId session-id :hookType hook-type
                  :input (merge base-input extra-input)})
                [input invocation] (last @received)]
            (is (= invocation {:session-id session-id}) (str operation " " hook-type))
            (is (= (select-keys input [:session-id :timestamp :cwd :transcript-path :agent-name])
                   {:session-id session-id :timestamp 1700000000000 :cwd "/workspace"
                    :transcript-path "/state/events.jsonl" :agent-name "explore"}))
            (is (not (contains? input :agent-display-name)))
            (when (= hook-type "subagentStop")
              (is (= (select-keys input [:agent-id :agent-type :stop-reason :response])
                     {:agent-id "" :agent-type "explore"
                      :stop-reason "end_turn" :response "First line"})))
            (is (= (:result response)
                   (if (nil? expected)
                     {}
                     {:output (cond-> (dissoc expected :additional-context :modified-response)
                                (contains? expected :additional-context)
                                (assoc :additionalContext (:additional-context expected))
                                (contains? expected :modified-response)
                                (assoc :modifiedResponse (:modified-response expected)))})))))
        (is (= @root-stops 0))
        (finally
          (sdk/disconnect! copilot-session))))))

(deftest subagent-hook-options-reject-non-functions
  (doseq [hook [:on-subagent-start :on-subagent-stop]
          value [nil false true "" {} []]]
    (is (not (s/valid? ::specs/hooks {hook value})) (str hook " " (pr-str value)))))

(deftest string-schema-patch-overrides-normalize-only-handler-arguments
  (doseq [operation [:create :create-channel :resume :resume-channel :join]
          [tool-name override? schema arguments expected]
          [["apply_patch" true {:type "string"} "patch" "patch"]
           ["apply_patch" true {"type" "string"} {:input ""} ""]
           ["apply_patch" true {:type "string"} {:input "patch" :extraKey 1} "patch"]
           ["apply_patch" true {:type "object"} {:input "patch"} {:input "patch"}]
           ["apply_patch" false {:type "string"} {:input "patch"} {:input "patch"}]
           ["another_tool" true {:type "string"} {:input "patch"} {:input "patch"}]]]
    (let [received (atom [])
          tool (sdk/define-tool tool-name
                 {:parameters schema
                  :overrides-built-in-tool override?
                  :handler (fn [input invocation]
                             (swap! received conj [input (:arguments invocation)])
                             "handled")})
          copilot-session (open-session *test-client* operation {:tools [tool]})]
      (try
        (is (= (:result-type
                (:result
                 (read-result!
                  (session/handle-tool-call! *test-client* (sdk/session-id copilot-session)
                                             "patch-call" tool-name arguments))))
               "success"))
        (is (= @received [[expected arguments]]) (str operation " " tool-name " " schema))
        (finally
          (sdk/disconnect! copilot-session))))))

(deftest invalid-string-schema-patch-input-never-reaches-handler
  (let [calls (atom 0)
        copilot-session
        (sdk/create-session
         *test-client*
         {:tools [(sdk/define-tool "apply_patch"
                    {:parameters {:type "string"}
                     :overrides-built-in-tool true
                     :handler (fn [_ _] (swap! calls inc) "unexpected")})]})]
    (try
      (doseq [arguments [nil 42 [] {} {:input nil} {:input 42}]]
        (let [result (:result (read-result!
                               (session/handle-tool-call! *test-client*
                                                          (sdk/session-id copilot-session)
                                                          "invalid-patch" "apply_patch" arguments)))]
          (is (= (:result-type result) "failure"))
          (is (= (:error result) "apply_patch string override requires a string input"))))
      (is (= @calls 0))
      (finally
        (sdk/disconnect! copilot-session)))))

(deftest patch-normalization-also-applies-to-broadcast-tool-requests
  (doseq [arguments ["patch" {:input "patch"} {:input 42}]]
    (let [received (atom [])
          completed (promise)
          copilot-session
          (sdk/create-session
           *test-client*
           {:tools [(sdk/define-tool "apply_patch"
                      {:parameters {:type "string"} :overrides-built-in-tool true
                       :handler (fn [value invocation]
                                  (swap! received conj [value (:arguments invocation)])
                                  value)})]})
          session-id (sdk/session-id copilot-session)
          valid? (not= arguments {:input 42})]
      (try
        (mock/set-request-hook!
         *mock-server*
         (fn [method params]
           (when (= method "session.tools.handlePendingToolCall")
             (deliver completed params))))
        (mock/send-v3-broadcast-event!
         *mock-server* session-id :copilot/external_tool.requested
         {:requestId "patch-request" :sessionId session-id
          :toolCallId "patch-call" :toolName "apply_patch" :arguments arguments})
        (let [params (await-value! completed "external patch result" 5000)]
          (is (= (get-in params [:result :resultType]) (if valid? "success" "failure")))
          (is (= @received (if valid? [["patch" arguments]] [])))
          (when-not valid?
            (is (= (get-in params [:result :error])
                   "apply_patch string override requires a string input"))))
        (finally
          (sdk/disconnect! copilot-session))))))

(defn- memory-provider [files]
  {:read-file (fn [path] (:content (get @files path)))
   :write-file (fn [path content mode] (swap! files assoc path {:content content :mode mode}))
   :append-file (fn [path content _] (swap! files update-in [path :content] str content))
   :exists (fn [path] (contains? @files path))
   :stat (fn [path] {:is-file (contains? @files path) :is-directory false
                     :size (count (:content (get @files path)))})
   :mkdir (fn [path _ _] (swap! files assoc path {:directory? true}))
   :readdir (fn [_] (vec (keys @files)))
   :readdir-with-types (fn [_] (mapv (fn [[path value]]
                                       {:name path :is-directory (boolean (:directory? value))
                                        :is-file (not (:directory? value))})
                                     @files))
   :rm (fn [path _ _] (swap! files dissoc path))
   :rename (fn [src dest] (swap! files #(-> % (assoc dest (get % src)) (dissoc src))))})

(defn- binary-provider [files]
  (assoc (memory-provider files)
         :read-file-bytes (fn [path] (:bytes (get @files path)))
         :write-file-bytes (fn [path content mode]
                             (swap! files assoc path {:bytes content :mode mode}))))

(def ^:private fs-config
  {:initial-cwd "/workspace" :session-state-path "/state" :conventions "posix"
   :capabilities {:binary true}})

(deftest binary-filesystem-round-trips-bytes-through-rpc
  (doseq [operation [:create :create-channel :resume :resume-channel :join]]
    (let [files (atom {"/image" {:bytes (byte-array [0 -1 -2 1])}})
          copilot-client (assoc *test-client* :session-fs fs-config)
          copilot-session
          (open-session copilot-client operation
                        {:create-session-fs-handler (fn [_] (binary-provider files))})
          session-id (sdk/session-id copilot-session)]
      (try
        (is (= (:result (mock/send-rpc-request!
                         *mock-server* "sessionFs.readFileBytes"
                         {:sessionId session-id :path "/image"}))
               {:content "AP/+AQ=="}))
        (is (nil? (:result (mock/send-rpc-request!
                            *mock-server* "sessionFs.writeFileBytes"
                            {:sessionId session-id :path "/written"
                             :content "AP/+AQ==" :mode 384}))))
        (is (= (vec (get-in @files ["/written" :bytes])) [0 -1 -2 1]))
        (is (= (get-in @files ["/written" :mode]) 384))
        (finally
          (sdk/disconnect! copilot-session))))))

(deftest binary-capability-validates-before-local-rpc-and-rolls-back
  (doseq [value [nil "" 0 [] {}]]
    (is (not (s/valid? ::specs/session-fs-capabilities {:binary value}))))
  (doseq [operation [:create :create-channel :resume :resume-channel :join]
          missing [[:read-file-bytes] [:write-file-bytes] [:read-file-bytes :write-file-bytes]]]
    (with-mock-server
      (fn []
        (let [copilot-client (assoc *test-client* :session-fs fs-config)
              requests (atom [])]
          (mock/set-request-hook! *mock-server*
                                  (fn [method _]
                                    (when (contains? #{"session.create" "session.resume"} method)
                                      (swap! requests conj method))))
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo #"capabilities.binary"
               (open-session copilot-client operation
                             {:create-session-fs-handler
                              (fn [_] (apply dissoc (binary-provider (atom {})) missing))})))
          (is (= @requests (if (#{:create :create-channel} operation) [] ["session.create"])))
          (is (every? :destroyed? (vals (:sessions @(:state copilot-client)))))
          (is (empty? (:session-setups @(:state copilot-client)))))))))

(deftest binary-capabilities-preserve-omission-false-and-true-on-connect
  (doseq [capabilities [::absent {} {:binary false} {:binary true}]]
    (let [server (mock/create-mock-server)
          seen (atom nil)
          config (cond-> (dissoc fs-config :capabilities)
                   (not= capabilities ::absent) (assoc :capabilities capabilities))
          copilot-client (sdk/client {:auto-start? false :session-fs config})]
      (mock/start-mock-server! server)
      (try
        (mock/set-request-hook!
         server
         (fn [method params]
           (when (= method "sessionFs.setProvider")
             (reset! seen params))))
        (let [[in out] (mock/client-streams server)]
          (client/connect-with-streams! copilot-client in out))
        (is (= (contains? @seen :capabilities) (not= capabilities ::absent)))
        (when-not (= capabilities ::absent)
          (is (= (:capabilities @seen) capabilities)))
        (let [provider (if (true? (:binary capabilities))
                         (binary-provider (atom {}))
                         (memory-provider (atom {})))
              copilot-session
              (sdk/create-session copilot-client {:create-session-fs-handler (fn [_] provider)})]
          (sdk/disconnect! copilot-session))
        (finally
          (try
            (is (= (sdk/stop! copilot-client) []))
            (finally
              (mock/stop-mock-server! server))))))))

(deftest binary-adapter-enforces-canonical-base64-and-exact-size-limit
  (let [files (atom {})
        handler (sdk/create-session-fs-adapter (binary-provider files))
        read-bytes (:read-file-bytes handler)
        write-bytes (:write-file-bytes handler)
        raw-limit 50330880
        encoded-limit 67107840]
    (is (fn? read-bytes))
    (is (fn? write-bytes))
    (when (and read-bytes write-bytes)
      (doseq [encoded ["AA==AAAA" "AA" "AB==" "AA==\n" "_w==" "%%%"]]
        (is (= (write-bytes {:path "/invalid" :content encoded})
               {:code "UNKNOWN" :message "invalid sessionFs.writeFileBytes base64 content"})))
      (is (= @files {}))
      (is (nil? (write-bytes {:path "/empty" :content ""})))
      (is (= (vec (get-in @files ["/empty" :bytes])) []))
      (let [bytes (byte-array raw-limit)
            encoded (.encodeToString (Base64/getEncoder) bytes)]
        (is (= (count encoded) encoded-limit))
        (swap! files assoc "/limit" {:bytes bytes})
        (is (= (count (:content (read-bytes {:path "/limit"}))) encoded-limit))
        (is (nil? (write-bytes {:path "/accepted" :content encoded})))
        (is (= (alength ^bytes (get-in @files ["/accepted" :bytes])) raw-limit))
        (is (= (:message (write-bytes {:path "/oversized" :content (str encoded "AAAA")}))
               "sessionFs.writeFileBytes content exceeds the binary write limit"))
        (is (not (contains? @files "/oversized"))))
      (swap! files assoc "/oversized" {:bytes (byte-array (inc raw-limit))})
      (is (= (read-bytes {:path "/oversized"})
             {:content ""
              :error {:code "UNKNOWN"
                      :message "sessionFs.readFileBytes content exceeds the binary read limit"}})))))

(deftest uninitialized-cloud-session-is-deleted-on-provider-failure
  (doseq [operation [:create :create-channel]]
    (let [requests (atom [])
          session-id (str "invalid-cloud-" (name operation))
          copilot-client (assoc *test-client* :session-fs fs-config)]
      (mock/set-request-hook!
       *mock-server*
       (fn [method params]
         (swap! requests conj [method params])
         (when (= method "session.create")
           {::mock/merge-response {:sessionId session-id}})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"capabilities.binary"
           (open-session copilot-client operation
                         {:cloud {}
                          :create-session-fs-handler (fn [_] (memory-provider (atom {})))})))
      (is (= (filterv #(contains? #{"session.delete" "session.detach"} (first %)) @requests)
             [["session.delete" {:sessionId session-id}]]))
      (doseq [key [:sessions :session-io :session-setups]]
        (is (not (contains? (get @(:state copilot-client) key) session-id)))))))

(deftest rejected-cloud-deletion-remains-observable
  (doseq [operation [:create :create-channel]]
    (let [session-id (str "rejected-cloud-delete-" (name operation))
          copilot-client (assoc *test-client* :session-fs fs-config)]
      (mock/set-request-hook!
       *mock-server*
       (fn [method _]
         (case method
           "session.create" {::mock/merge-response {:sessionId session-id}}
           "session.delete" {::mock/merge-response {:success false :error "deletion denied"}}
           nil)))
      (let [failure (try
                      (open-session copilot-client operation
                                    {:cloud {}
                                     :create-session-fs-handler (fn [_] (memory-provider (atom {})))})
                      (catch Throwable error error))
            cleanup (first (.getSuppressed ^Throwable failure))]
        (is (re-find #"capabilities.binary" (ex-message failure)))
        (is (some? cleanup))
        (when cleanup
          (is (= (:operation (ex-data cleanup)) :delete))
          (is (= (ex-data (ex-cause cleanup))
                 {:session-id session-id :error "deletion denied"})))
        (doseq [key [:sessions :session-io :session-setups]]
          (is (not (contains? (get @(:state copilot-client) key) session-id))))))))

(deftest cloud-deletion-timeout-preserves-the-provider-failure
  (let [session-id "stalled-cloud-delete"
        delete-started (promise)
        release-delete (promise)
        copilot-client (assoc *test-client* :session-fs fs-config)]
    (mock/set-request-hook!
     *mock-server*
     (fn [method _]
       (case method
         "session.create" {::mock/merge-response {:sessionId session-id}}
         "session.delete" (do (deliver delete-started true) @release-delete nil)
         nil)))
    (let [result (future
                   (try
                     (sdk/create-session
                      copilot-client
                      {:cloud {}
                       :create-session-fs-handler (fn [_] (memory-provider (atom {})))})
                     (catch Throwable failure failure)))]
      (try
        (await-value! delete-started "cloud delete request" 5000)
        (let [failure (await-value! result "bounded cloud deletion" 15000)
              cleanup (first (.getSuppressed ^Throwable failure))]
          (is (re-find #"capabilities.binary" (ex-message failure)))
          (is (= (:operation (ex-data cleanup)) :delete))
          (is (= (ex-data (ex-cause cleanup))
                 {:method "session.delete" :timeout-ms 10000}))
          (doseq [key [:sessions :session-io :session-setups]]
            (is (not (contains? (get @(:state copilot-client) key) session-id)))))
        (finally
          (deliver release-delete true)
          (await-value! result "cloud setup worker exit" 5000))))))
(deftest filesystem-write-failure-retains-partial-mutation-only-for-text-writes
  (doseq [execute [(fn [f] (f))
                   (fn [f] (future (f)))
                   (fn [f] (completed-channel (try (f) (catch Throwable error error))))]
          changed? [false true]]
    (let [files (atom {})
          failure (if changed?
                    (sdk/session-fs-write-failure "write failed")
                    (ex-info "write failed" {}))
          write (fn [path _ _]
                  (execute
                   #(do
                      (when changed? (swap! files assoc path {:content ""}))
                      (throw failure))))
          handler (sdk/create-session-fs-adapter
                   (assoc (binary-provider files)
                          :write-file write :append-file write :write-file-bytes write))]
      (is (= (sdk/session-fs-write-failure? failure) changed?))
      (is (= ((:write-file handler) {:path "/partial" :content "value"})
             (cond-> {:code "UNKNOWN" :message "write failed"}
               changed? (assoc :write-changed true))))
      (is (= (contains? @files "/partial") changed?))
      (doseq [operation [:append-file :write-file-bytes]]
        (is (= ((operation handler) {:path "/other" :content ""})
               {:code "UNKNOWN" :message "write failed"}))))))

(deftest text-only-filesystem-keeps-explicit-unsupported-binary-errors
  (let [handler (sdk/create-session-fs-adapter (memory-provider (atom {})))]
    (is (= ((:read-file-bytes handler) {:path "/missing"})
           {:content "" :error {:code "UNKNOWN" :message "Binary reads are not supported"}}))
    (is (= ((:write-file-bytes handler) {:path "/missing" :content ""})
           {:code "UNKNOWN" :message "Binary writes are not supported"}))))

(deftest async-arity-failures-never-retry-filesystem-mutations
  (doseq [delivery [:channel :promise :future]
          [operation arity] [[:write-file 3] [:append-file 3] [:mkdir 3] [:rm 3] [:rename 2]]]
    (let [calls (atom [])
          failure (clojure.lang.ArityException. 0 "provider-body")
          mutate (fn [& args]
                   (swap! calls conj (count args))
                   (when-not (= (count args) 1)
                     (case delivery
                       :channel (completed-channel failure)
                       :promise (doto (promise) (deliver failure))
                       :future (future (throw failure)))))
          handler (sdk/create-session-fs-adapter
                   (assoc (memory-provider (atom {})) operation mutate))
          result ((operation handler) {:path "/file" :content "value" :mode 384
                                       :recursive false :force false :src "/before" :dest "/after"})]
      (is (= @calls [arity]) (str operation " " delivery))
      (is (= result {:code "UNKNOWN" :message (ex-message failure)})
          (str operation " " delivery)))))

(def ^:private response-cases
  [[{:responseKind "ask_user" :message ""
     :content {:camelCase {:inner_Key [nil false {"mixed.Key" 1}]}}
     :requestedSchema {:type "object"
                       :properties {:camelCase {:type "object" :additionalProperties true}}
                       :required ["camelCase"]}}
    {:response-kind "ask_user" :message ""
     :content {:camelCase {:inner_Key [nil false {:mixed.Key 1}]}}
     :requested-schema {:type "object"
                        :properties {:camelCase {:type "object" :additionalProperties true}}
                        :required ["camelCase"]}}]
   [{:responseKind "user_input" :question "" :answer "" :wasFreeform false}
    {:response-kind "user_input" :question "" :answer "" :was-freeform false}]
   [{:responseKind "user_input" :question "Choose" :answer "custom"
     :choices [] :allowFreeform false :wasFreeform true}
    {:response-kind "user_input" :question "Choose" :answer "custom"
     :choices [] :allow-freeform false :was-freeform true}]
   [{:responseKind "exit_plan_mode" :summary "" :planContent ""
     :actions [] :recommendedAction "interactive" :approved false}
    {:response-kind "exit_plan_mode" :summary "" :plan-content ""
     :actions [] :recommended-action "interactive" :approved false}]
   [{:responseKind "exit_plan_mode" :summary "Plan" :planContent "Exact plan"
     :actions ["interactive" "autopilot" "autopilot_fleet" "exit_only"]
     :recommendedAction "interactive" :approved true
     :autoApproveEdits false :selectedAction "exit_only" :feedback ""}
    {:response-kind "exit_plan_mode" :summary "Plan" :plan-content "Exact plan"
     :actions ["interactive" "autopilot" "autopilot_fleet" "exit_only"]
     :recommended-action "interactive" :approved true
     :auto-approve-edits false :selected-action "exit_only" :feedback ""}]])

(deftest response-provenance-events-retain-exact-live-and-historical-json
  (let [copilot-session (sdk/create-session *test-client* {})
        session-id (sdk/session-id copilot-session)
        events (sdk/subscribe-events copilot-session)]
    (try
      (doseq [actor ["human_response" "host_automation" "unknown"]
              [wire-response response] response-cases
              tool-id [::absent "" "tool"]
              :let [wire (cond-> {:requestId "" :actor actor :response wire-response}
                           (not= tool-id ::absent) (assoc :toolCallId tool-id))
                    expected (cond-> {:request-id "" :actor actor :response response}
                               (not= tool-id ::absent) (assoc :tool-call-id tool-id))]]
        (mock/send-session-event! *mock-server* session-id :copilot/human_response.recorded wire)
        (is (= (:data (await-event-type! events :copilot/human_response.recorded 5000)) expected))
        (mock/set-session-messages! *mock-server* session-id
                                    [{:type "human_response.recorded" :data wire}])
        (is (= (:data (first (sdk/get-messages copilot-session))) expected)))
      (finally
        (sdk/unsubscribe-events! copilot-session events)
        (sdk/disconnect! copilot-session)))))

(deftest new-public-events-have-curated-idiom-contracts
  (doseq [[event-type group spec valid invalids]
          [[:copilot/tool.shell_output sdk/tool-events ::specs/tool.shell_output-data
            {:tool-call-id "" :text "" :sequence 0}
            [{:sequence -1} {:sequence 1.5} {:sequence nil} {:text nil}
             {:stream nil} {:stream "merged"} {:stream :stdout}]]
           [:copilot/human_response.recorded sdk/interaction-events ::specs/human_response.recorded-data
            {:request-id "" :actor "host_automation" :response (second (second response-cases))}
            [{:request-id nil} {:actor "human"} {:actor :unknown} {:response nil}
             {:tool-call-id nil} {:response {:response-kind "unknown"}}]]]]
    (is (contains? sdk/event-types event-type))
    (is (contains? group event-type))
    (is (s/get-spec spec))
    (when (s/get-spec spec)
      (is (s/valid? spec valid))
      (is (s/valid? spec (assoc valid :future-field true)))
      (doseq [invalid invalids]
        (is (not (s/valid? spec (merge valid invalid))) (str event-type " " invalid)))))
  (when (s/get-spec ::specs/human_response.recorded-data)
    (doseq [[_ response] response-cases]
      (is (s/valid? ::specs/human_response.recorded-data
                    {:request-id "request" :actor "unknown" :response response})))
    (doseq [content [nil [] #{1} {:value (Object.)} {:value ##NaN}]
            :let [response (assoc (second (first response-cases)) :content content)]]
      (is (not (s/valid? ::specs/human_response.recorded-data
                         {:request-id "request" :actor "unknown" :response response}))))))

(deftest shell-output-preserves-append-only-stream-and-sequence-data
  (let [copilot-session (sdk/create-session *test-client* {})
        session-id (sdk/session-id copilot-session)
        events (sdk/subscribe-events copilot-session)]
    (try
      (doseq [[sequence stream text] [[7 ::absent "half"] [8 "stderr" "error"]
                                      [9 "stdout" "-line\n"] [10 "terminal" ""]]
              :let [wire (cond-> {:toolCallId "shell" :sequence sequence :text text}
                           (not= stream ::absent) (assoc :stream stream))
                    expected (cond-> {:tool-call-id "shell" :sequence sequence :text text}
                               (not= stream ::absent) (assoc :stream stream))]]
        (mock/send-session-event! *mock-server* session-id :copilot/tool.shell_output wire :ephemeral? true)
        (is (= (:data (await-event-type! events :copilot/tool.shell_output 5000)) expected)))
      (finally
        (sdk/unsubscribe-events! copilot-session events)
        (sdk/disconnect! copilot-session)))))

(deftest model-bound-reasoning-effort-metadata-is-an-optional-string
  (let [started (promise)
        copilot-session (sdk/create-session
                         *test-client*
                         {:on-event (fn [event]
                                      (when (= (:type event) :copilot/session.start)
                                        (deliver started true)))})
        session-id (sdk/session-id copilot-session)
        _ (await-value! started "initial session start" 5000)
        events (sdk/subscribe-events copilot-session)]
    (try
      (doseq [[event-type spec base]
              [[:copilot/session.start ::specs/session.start-data {:session-id "fixture"}]
               [:copilot/session.resume ::specs/session.resume-data {:event-count 0}]
               [:copilot/session.model_change ::specs/session.model_change-data {:new-model "model"}]]
              [value valid?] [[::absent true] ["" true] ["authored-model" true]
                              [nil false] [true false] [0 false] [[] false]]
              :let [expected (cond-> base
                               (not= value ::absent) (assoc :reasoning-effort-model value))]]
        (is (= (s/valid? spec expected) valid?) (str event-type " " (pr-str value)))
        (when valid?
          (let [wire (cond-> base
                       (not= value ::absent) (assoc :reasoningEffortModel value))]
            (mock/send-session-event! *mock-server* session-id event-type wire)
            (is (= (:data (await-event-type! events event-type 5000)) expected))
            (mock/set-session-messages! *mock-server* session-id
                                        [{:type (name event-type) :data wire}])
            (is (= (:data (first (sdk/get-messages copilot-session))) expected)))))
      (finally
        (sdk/unsubscribe-events! copilot-session events)
        (sdk/disconnect! copilot-session)))))

(deftest experimental-reasoning-blocks-preserve-provider-payloads-without-new-public-specs
  (let [copilot-session (sdk/create-session *test-client* {})
        session-id (sdk/session-id copilot-session)
        events (sdk/subscribe-events copilot-session)
        blocks [nil false "" 42 [] {} {:signatureId "exact" :nestedValue {:inner_Key true}}]]
    (try
      (doseq [[wire-key idiom-key] [[:blocks :blocks] [:orderedBlocks :ordered-blocks]]
              :let [wire {:messageId "message" :content ""
                          :reasoningBlocks {:provider "anthropic" wire-key blocks}}
                    expected {:message-id "message" :content ""
                              :reasoning-blocks {:provider "anthropic" idiom-key blocks}}]]
        (mock/send-session-event! *mock-server* session-id :copilot/assistant.message wire)
        (is (= (:data (await-event-type! events :copilot/assistant.message 5000)) expected))
        (mock/set-session-messages! *mock-server* session-id
                                    [{:type "assistant.message" :data wire}])
        (is (= (:data (first (sdk/get-messages copilot-session))) expected)))
      (finally
        (sdk/unsubscribe-events! copilot-session events)
        (sdk/disconnect! copilot-session))))
  (is (nil? (s/get-spec ::specs/ordered-blocks)))
  (is (not (s/valid? ::gen/assistant-message-reasoning-blocks-shape
                     {:provider "anthropic" :blocks [] :ordered-blocks []})))
  (doseq [data [{:provider "anthropic"}
                {:provider "anthropic" :blocks []}
                {:provider "anthropic" :ordered-blocks []}]]
    (is (s/valid? ::gen/assistant-message-reasoning-blocks-shape data))))
