(ns github.copilot-sdk.native-reverse-test
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-fs :as fs]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.reverse :as reverse]
            [github.copilot-sdk.util :as util])
  (:import [java.time Instant]
           [java.util.concurrent FutureTask ThreadPoolExecutor TimeUnit]))

(deftest native-ui-actions-run-on-the-chosen-thread-once
  (peer/with-peer {}
    (fn [conn request!]
      (let [thread (java.util.concurrent.Executors/newSingleThreadExecutor)
            next-action (atom nil)
            calls (atom [])
            schedule? (atom true)
            target-thread (deref (.submit thread ^java.util.concurrent.Callable #(Thread/currentThread)))
            schedule (fn [action]
                       (reset! next-action action)
                       (when @schedule? (.submit thread ^java.util.concurrent.Callable action)))
            owner (reverse/open! conn {:run-on-ui-thread schedule}
                                 #(swap! calls conj [% (Thread/currentThread)]))
            run #(request! "uiThread.run" {:actionId %})]
        (try
          (is (= (select-keys (run 4294967295) [:result :error]) {:result nil}))
          (is (= @calls [[4294967295 target-thread]]))
          (is (thrown? clojure.lang.ExceptionInfo (@next-action)))
          (is (= (get-in (run -1) [:error :code]) -32602))
          (reset! schedule? false)
          (is (= (get-in (run 0) [:error :code]) -32603))
          (is (thrown? clojure.lang.ExceptionInfo (@next-action)))
          (finally (reverse/close! owner) (.shutdownNow thread)))))))

(deftest filesystem-errors-identify-only-proven-missing-paths
  (let [read-error (fn [read-file path]
                     (get-in (fs/handle (fn [f & args] (apply f args)) {:read-file read-file} {} :read-file {:path path})
                             [:result :error]))]
    ;; FileInputStream throws FileNotFoundException for a directory that exists.
    (is (= (:code (read-error slurp ".")) "UNKNOWN"))
    (is (= (read-error (fn [_] (throw (ex-info "missing" {:code "ENOENT"}))) "virtual")
           {:code "ENOENT" :message "missing"}))
    (is (= (:code (read-error (fn [p] (throw (java.nio.file.NoSuchFileException. p))) "missing")) "ENOENT"))))

(defn- with-session [opts f]
  (peer/with-peer {}
    (fn [conn request!]
      (let [owner (reverse/open! conn {})]
        (try
          (let [reg (reverse/register! owner "s" nil opts)]
            (reverse/confirm-open! reg)
            (f owner reg conn (fn [method params] (request! method (assoc params :sessionId "s")))))
          (finally (reverse/close! owner)))))))

(s/def ::opaque-units nat-int?)

(deftest framed-hooks
  (let [seen (atom nil)
        opaque {"kebab-key" 1 "kebabKey" 2 "Vendor/MIXED_Key" [nil]}
        received (update-keys opaque keyword)
        handler (fn [input invocation]
                  (reset! seen [input invocation])
                  {:permission-decision :deny :modified-args opaque})]
    (with-session {:hooks {:handlers {:on-pre-tool-use handler
                                      :on-pre-mcp-tool-call (fn [input _] {:meta-to-use (:_meta input)})
                                      :on-post-tool-use (fn [{:keys [tool-result]} _]
                                                          (reset! seen tool-result)
                                                          {:modified-result tool-result})
                                      :on-session-end (fn [input _] {:session-summary (:error input)})
                                      :on-agent-stop (fn [& _] (throw (Exception. "sensitive")))}}}
      (fn [_ _ _ request!]
        (let [hook (fn [type input] (:result (request! "hooks.invoke" {:hookType type :input input})))]
          (is (= (hook "preToolUse" {:toolArgs opaque :sessionId "child" :timestamp 42 :cwd "/v"})
                 {:output {:permissionDecision "deny" :modifiedArgs received}}))
          (is (= @seen [{:tool-args received :session-id "child" :timestamp (Instant/ofEpochMilli 42)
                         :working-directory "/v"} {:session-id "s"}]))
          (hook "preToolUse" {:cwd "/now"})
          (is (= (first @seen) {:working-directory "/now"}))
          (is (= (hook "preMcpToolCall" {:_meta opaque}) {:output {:metaToUse received}}))
          (is (= (hook "preMcpToolCall" {:_meta nil}) {:output {:metaToUse nil}}))
          (let [metadata {"vendorKey" 1 "vendor_key" 2 "provider/Asset_ID" [false]}
                result {:textResultForLlm "ok" :resultType "success" :skipLargeOutputProcessing true
                        "result-type" "vendor spelling"
                        (subs (str ::opaque-units) 1) "tokens"
                        :Custom_Metrics {"lab/count" 2 "model/count" 7}
                        :binaryResultsForLlm [{:type "image" :data "AQ==" :mimeType "image/png"
                                               "mime-type" "vendor spelling" :Vendor_Tag {"nested_key" true} :metadata metadata}
                                              {:type "resource" :data "Ag==" :mimeType "application/octet-stream"}]}]
            (is (= (hook "postToolUse" {:toolResult result})
                   {:output {:modifiedResult (walk/keywordize-keys result)}}))
            (is (= (get @seen :Custom_Metrics) {:lab/count 2 :model/count 7}))
            (is (true? (:skip-large-output-processing? @seen)))
            (is (= [(:result-type @seen) (get @seen "result-type")]
                   [:success "vendor spelling"])))
          (is (= (hook "sessionEnd" {:error {:message "failed"}}) {:output {:sessionSummary "failed"}}))
          (doseq [type ["agentStop" "unspecified" "sessionStart"]]
            (is (= (hook type {}) {}))))))))

(deftest callback-fallback-applies-only-to-ordinary-application-exceptions
  (let [failure (atom (java.util.concurrent.TimeoutException. "application deadline"))
        fail (fn [& _] (throw @failure))]
    (with-session {:hooks {:handlers {:on-agent-stop fail}}
                   :system-message {:sections {:safety {:action fail}}}}
      (fn [_ _ _ request!]
        (let [hook #(request! "hooks.invoke" {:hookType "agentStop" :input {}})
              sections {:safety {:content "original"}}
              transform #(request! "systemMessage.transform" {:sections sections})]
          (is (= (:result (hook)) {}))
          (is (= (:result (transform)) {:sections sections}))
          (reset! failure (LinkageError. "broken callback"))
          (is (= (get-in (hook) [:error :code]) -32603))
          (is (= (get-in (transform) [:error :code]) -32603)))))))

(deftest an-expired-transform-cannot-publish-a-partial-or-late-result
  (let [until (java.util.concurrent.atomic.AtomicLong.)
        calls (atom [])
        transform (fn [content]
                    (swap! calls conj content)
                    (.set until (dec (System/nanoTime)))
                    "changed")]
    (with-session {:system-message {:sections {:safety {:action transform} :tone {:action transform}}}}
      (fn [_ reg _ request!]
        ;; Finish the job before the protocol worker calls FutureTask.get with
        ;; an expired deadline. get can still succeed when the task is done.
        (add-watch (:jobs reg) ::completed
                   (fn [_ _ _ jobs]
                     (when-let [job (ffirst jobs)]
                       (try (.get ^FutureTask job 3 TimeUnit/SECONDS)
                            (catch java.util.concurrent.ExecutionException _)))))
        (try
          (with-redefs-fn {#'reverse/deadline (fn [_] (.set until (+ (System/nanoTime) 60000000000)) until)}
            #(doseq [sections [{:safety {:content "only"}}
                               {:safety {:content "first"} :tone {:content "second"}}]]
               (is (= (:error (request! "systemMessage.transform" {:sections sections}))
                      {:code -32000 :message "Reverse callback timed out"}))))
          (is (= @calls ["only" "first"]))
          (finally (remove-watch (:jobs reg) ::completed)))))))

(deftest a-completed-shell-answer-does-not-depend-on-the-task-returning
  (let [entered (promise) release (promise) attached (promise)
        accepted {:action :accept :value "yes"}]
    (with-session {:interaction {:on-shell-authentication-request (fn [_ _] (deliver entered true) @release accepted)}}
      (fn [_ reg _ request!]
        (add-watch (:jobs reg) ::publishing
                   (fn [_ _ _ jobs]
                     (when-let [[result _] (first (vals jobs))] (deliver attached result))))
        (try
          (let [answer (future (request! "shellAuthentication.request"
                                         {:requestId "confirm" :program "fixture" :prompt "Continue?" :inputKind "confirm"}))]
            (is (true? (deref entered 3000 false)))
            ;; Reproduce publication before the FutureTask returns without
            ;; relying on the scheduler to pause between the two instructions.
            (let [^java.util.concurrent.CompletableFuture result (deref attached 3000 nil)]
              (is (.complete result {:result accepted})))
            (is (= (:result (deref answer 3000 {})) {:action "accept" :value "yes"})))
          (finally (remove-watch (:jobs reg) ::publishing) (deliver release true)))))))

(deftest exact-shell-cancellation
  (let [entered (promise)
        handler (fn [request {:keys [cancel-ch session-id]}]
                  (deliver entered [request cancel-ch session-id])
                  (a/chan))]
    (with-session {:interaction {:on-shell-authentication-request handler}}
      (fn [owner reg conn _]
        (let [{:keys [request-handler request-preparer]} (:connection @(:state-atom conn))
              params {:session-id "s" :request-id "r" :program "ssh" :prompt "key" :input-kind "secret"}
              prepare request-preparer
              run (fn [method {:keys [params release!]}]
                    (try (a/<!! (request-handler method params))
                         (finally (when release! (release!)))))
              ask "shellAuthentication.request"
              cancel "shellAuthentication.cancel"
              first-call (future (run ask (prepare ask params)))]
          (try
            (let [[req signal id] (deref entered 3000 nil)]
              (is (= [req id] [(assoc (dissoc params :session-id) :input-kind :secret :cwd "" :command "") "s"]))
              (is (= (:result (run ask (prepare ask params))) {:action "cancel"}))
              (is (= (:result (run cancel (prepare cancel (assoc params :request-id "other")))) {}))
              ;; The protocol worker may be saturated. Arrival, not worker scheduling, cancels the secret.
              (let [ack (prepare cancel params)]
                (is (= (:result (deref first-call 3000 {})) {:action "cancel"}))
                (when signal (is (= (a/alts!! [signal (a/timeout 1000)]) [nil signal])))
                (is (= (:result (run cancel ack)) {}))))
            (testing "a queued prompt cannot cross a registration boundary"
              (let [old (prepare ask params)]
                (reverse/unregister! owner reg)
                (reverse/register! owner "s" nil {:interaction {:on-shell-authentication-request
                                                                (constantly {:action :accept :value "wrong"})}})
                (is (= (:result (run ask old)) {:action "cancel"}))))
            (finally (future-cancel first-call))))))))

(deftest shell-cancel-remains-effective-through-reply-conversion
  (let [{:keys [conn ->client <-client]} (peer/open-connection {:request-handler-threads 2})
        owner (reverse/open! conn {})
        converted (promise)
        resume (promise)
        cancelled (promise)
        calls (atom 0)
        convert util/clj->wire
        ask "shellAuthentication.request"
        cancel "shellAuthentication.cancel"
        send! (fn [id method]
                (peer/write-framed! ->client {:jsonrpc "2.0" :id id :method method
                                              :params {:sessionId "s" :requestId "same" :inputKind "confirm"
                                                       :program "demo" :prompt "Continue?"}}))
        read! #(let [f (future (peer/read-framed <-client))]
                 (try (deref f 3000 ::timeout) (finally (future-cancel f))))]
    (try
      (reverse/register! owner "s" nil
                         {:interaction {:on-shell-authentication-request
                                        (fn [_ {:keys [cancel-ch]}]
                                          (when (= 1 (swap! calls inc))
                                            (a/take! cancel-ch (fn [_] (deliver cancelled true))))
                                          {:action :accept :value "yes"})}})
      (with-redefs [util/clj->wire (fn [v]
                                     (when (and (= (:action v) "accept") (not (realized? converted)))
                                       (deliver converted true)
                                       (deref resume 5000 nil))
                                     (convert v))]
        (send! "pending" ask)
        (is (= true (deref converted 3000 nil)))
        (send! "cancel" cancel)
        (is (= (select-keys (read!) [:id :result]) {:id "cancel" :result {}}))
        (is (= true (deref cancelled 1000 false)))
        (send! "duplicate" ask)
        (is (= (:result (read!)) {:action "cancel"}))
        (deliver resume true)
        (is (= (select-keys (read!) [:id :result]) {:id "pending" :result {:action "cancel"}})))
      (send! "reuse" ask)
      (is (= (:result (read!)) {:action "accept" :value "yes"}))
      (is (= @calls 2))
      (finally
        (deliver resume true)
        (reverse/close! owner)
        (protocol/disconnect conn)
        (.close ^java.io.OutputStream ->client)
        (.close ^java.io.InputStream <-client)))))

(deftest shell-reply-offer-cannot-be-overtaken-by-a-later-cancel
  (let [{:keys [conn ->client <-client]} (peer/open-connection {:request-handler-threads 2})
        owner (reverse/open! conn {})
        offering (promise) cancel-read (promise) cancel-offer (promise) resume (promise)
        offer a/offer!
        convert util/wire->clj
        send! (fn [id method p]
                (peer/write-framed! ->client {:jsonrpc "2.0" :id id :method method
                                              :params (assoc p :sessionId "s" :requestId "r")}))]
    (try
      (reverse/register! owner "s" nil {:interaction {:on-shell-authentication-request
                                                      (constantly {:action :accept :value "ok"})}})
      (with-redefs [util/wire->clj (fn [m]
                                     (when (= (:method m) "shellAuthentication.cancel") (deliver cancel-read true))
                                     (convert m))
                    a/offer! (fn [ch v]
                               (when (and (identical? ch (:outgoing-ch conn)) (= "prompt" (:id v)))
                                 (deliver offering true)
                                 (deref resume 5000 nil))
                               (let [sent (offer ch v)]
                                 (when (and sent (= "cancel" (:id v))) (deliver cancel-offer true))
                                 sent))]
        (send! "prompt" "shellAuthentication.request" {:inputKind "confirm" :program "demo" :prompt "Continue?"})
        (is (= true (deref offering 3000 nil)))
        (send! "cancel" "shellAuthentication.cancel" {})
        (is (= true (deref cancel-read 3000 nil)))
        ;; Wait until the actual reader contends with the worker at the offer,
        ;; or (regression) until it lets the cancel ACK onto the queue first.
        (let [reader (:read-thread conn)
              until (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 3))]
          (loop []
            (when-not (or (= (.getState ^Thread reader) Thread$State/BLOCKED)
                          (realized? cancel-offer) (>= (System/nanoTime) until))
              (Thread/yield)
              (recur)))
          (is (or (= (.getState ^Thread reader) Thread$State/BLOCKED) (realized? cancel-offer))))
        (deliver resume true)
        (let [frames (future (mapv (fn [_] (select-keys (peer/read-framed <-client) [:id :result])) (range 2)))]
          (try
            (is (= (deref frames 3000 nil) [{:id "prompt" :result {:action "accept" :value "ok"}}
                                            {:id "cancel" :result {}}]))
            (finally (future-cancel frames)))))
      (finally
        (deliver resume true)
        (reverse/close! owner)
        (protocol/disconnect conn)
        (.close ^java.io.OutputStream ->client)
        (.close ^java.io.InputStream <-client)))))

(deftest disconnect-releases-a-shell-prompt-discarded-from-the-protocol-queue
  (let [{:keys [conn ->client <-client]} (peer/open-connection {:request-handler-threads 1 :request-handler-queue-size 1})
        owner (reverse/open! conn {})
        entered (promise)
        proceed (promise)
        calls (atom [])
        wire (fn [id method params] (peer/write-framed! ->client {:jsonrpc "2.0" :id id :method method
                                                                  :params (assoc params :sessionId "s")}))]
    (try
      (let [reg (reverse/register! owner "s" nil
                                   {:hooks {:handlers {:on-agent-stop (fn [& _]
                                                                        (deliver entered true)
                                                                        (deref proceed 5000 nil)
                                                                        nil)}}
                                    :interaction {:on-shell-authentication-request #(swap! calls conj [%1 %2])}})]
        (wire "running" "hooks.invoke" {:hookType "agentStop" :input {}})
        (is (= true (deref entered 3000 nil)))
        (wire "queued" "shellAuthentication.request" {:requestId "r" :program "demo" :inputKind "confirm" :prompt "Continue?"})
        (wire "excess" "unknown" {})
        (let [rejected (deref (future (peer/read-framed <-client)) 3000 nil)]
          (is (= [(:id rejected) (get-in rejected [:error :data :code])] ["excess" "request_handler_saturated"])))
        (is (contains? @(:prompts reg) "r"))
        (is (zero? (.availablePermits ^java.util.concurrent.Semaphore (:human-slots owner))))
        (protocol/disconnect conn)
        (is (= (.availablePermits ^java.util.concurrent.Semaphore (:human-slots owner)) 1))
        (is (empty? @(:prompts reg)))
        (is (empty? @calls)))
      (finally
        (deliver proceed true)
        (reverse/close! owner)
        (protocol/disconnect conn)
        (.close ^java.io.OutputStream ->client)
        (.close ^java.io.InputStream <-client)))))

(deftest all-hook-types
  (let [metadata {:Vendor_Key false :vendorKey 2 :provider/Asset_ID [{:sourceKey true}]}
        cases [["preToolUse" :on-pre-tool-use {:permission-decision :ask} {:permissionDecision "ask"}]
               ["preMcpToolCall" :on-pre-mcp-tool-call {:meta-to-use nil} {:metaToUse nil}]
               ["postToolUse" :on-post-tool-use {:modified-result {:text-result-for-llm "ok" :result-type :success
                                                                   :binary-results-for-llm [{:type "image" :mime-type "image/png"
                                                                                             :data "AQ==" :metadata metadata}]
                                                                   :tool-telemetry {"vendor" {:mixed_Key 1}}}}
                {:modifiedResult {:textResultForLlm "ok" :resultType "success" :toolTelemetry {:vendor {:mixed_Key 1}}
                                  :binaryResultsForLlm [{:type "image" :mimeType "image/png" :data "AQ==" :metadata metadata}]}}]
               ["postToolUseFailure" :on-post-tool-use-failure {:additional-context "failure"} {:additionalContext "failure"}]
               ["userPromptSubmitted" :on-user-prompt-submitted {:decision :block} {:decision "block"}]
               ["userPromptTransformed" :on-user-prompt-transformed {:modified-transformed-prompt "m"} {:modifiedTransformedPrompt "m"}]
               ["sessionStart" :on-session-start {:modified-config {:user_key true}} {:modifiedConfig {:user_key true}}]
               ["sessionEnd" :on-session-end {:cleanup-actions ["exit"]} {:cleanupActions ["exit"]}]
               ["errorOccurred" :on-error-occurred {:error-handling :abort} {:errorHandling "abort"}]
               ["agentStop" :on-agent-stop {:decision :block :reason "tests"} {:decision "block" :reason "tests"}]
               ["subagentStart" :on-subagent-start {:additional-context "context"} {:additionalContext "context"}]
               ["subagentStop" :on-subagent-stop {:modified-response "updated"} {:modifiedResponse "updated"}]]]
    (with-session {:hooks {:handlers (into {} (map (fn [[_ k value]] [k (constantly value)])) cases)}}
      (fn [_ _ _ request!]
        (doseq [[kind _ _ expected] cases]
          (is (= (:result (request! "hooks.invoke" {:hookType kind :input {}})) {:output expected}) kind))))))

(deftest framed-interactions
  (let [seen (atom nil)
        context {:outcome :prompted-user :source :human-response :surface :sdk}
        transform (fn [s] (str s "!"))
        user (fn [p ctx]
               (reset! seen [p ctx])
               (if (= (:question p) "invalid")
                 {:answer "wrong" :was-freeform? true :decision-context {:source :human-response}}
                 {:answer "ok" :was-freeform? false :decision-context context}))]
    (with-session {:interaction {:on-user-input-request user
                                 :on-exit-plan-mode-request (constantly {:approved? false :feedback "no"})
                                 :on-auto-mode-switch-request (fn [p _]
                                                                (if (false? (get-in p [:auto-mode-recovery :allow-always?]))
                                                                  :yes :no))
                                 :on-shell-authentication-request
                                 (fn [{:keys [prompt]} _]
                                   (case prompt
                                     "answer" {:action :accept :value "only-for-shell"}
                                     "bad" {:action :accept :value 123}
                                     "interrupted" (throw (InterruptedException. "private"))
                                     (throw (ex-info "sensitive-error" {:secret true}))))}
                   :system-message {:mode :customize :sections {:environment-context {:action transform}
                                                                :safety {:action (fn [_] (throw (Exception. "private")))}}}}
      (fn [_ _ _ request!]
        (let [question {:question "question" :choices ["ok"] :allowFreeform false :requestId "r"}
              plan {:summary "change" :actions ["exit"] :recommendedAction "exit"}]
          (is (= (:result (request! "userInput.request" question))
                 {:answer "ok" :wasFreeform false :decisionContext {:outcome "prompted_user" :source "human_response" :surface "sdk"}}))
          (is (= @seen [{:question "question" :choices ["ok"] :allow-freeform? false :request-id "r"} {:session-id "s"}]))
          (is (= (get-in (request! "userInput.request" {:question "invalid"}) [:error :code]) -32603))
          (is (= (:result (request! "exitPlanMode.request" plan)) {:approved false :feedback "no"}))
          (is (= (:result (request! "autoModeSwitch.request" {:autoModeRecovery {:requestId "r" :originalModel "model"
                                                                                 :allowAlways false}})) {:response "yes"})))
        (let [p {:requestId "r" :inputKind "secret" :program "ssh" :prompt "answer"}]
          (is (= (:result (request! "shellAuthentication.request" p)) {:action "accept" :value "only-for-shell"}))
          (doseq [invalid [(dissoc p :requestId) (assoc p :inputKind "invalid") (assoc p :prompt "bad")
                           (assoc p :prompt "throw") (assoc p :prompt "interrupted")]]
            (is (= (:result (request! "shellAuthentication.request" invalid)) {:action "cancel"}))))
        (is (= (:result (request! "systemMessage.transform"
                                  {:sections {"environment_context" {:content "cwd"}
                                              "environment-context" {:content "untouched"}
                                              "safety" {:content "fallback"}}}))
               {:sections {:environment_context {:content "cwd!"}
                           :environment-context {:content "untouched"}
                           :safety {:content "fallback"}}}))))))

(deftest legacy-user-input-honors-the-request-image-constraints
  (let [seen (atom nil)
        image {:type :image :data "AQ==" :mime-type "image/png" :display-name "screen" :description ""}
        constraints {:allowedMimeTypes ["image/png"] :maxBytesPerImage 1 :maxCount 1}
        answer {:answer "" :was-freeform? true :attachments [image]}
        reply (atom answer)]
    (with-session {:interaction {:on-user-input-request (fn [p _] (reset! seen p) @reply)}}
      (fn [_ _ _ request!]
        (let [ask #(request! "userInput.request" (merge {:question "Show image"} %))]
          (is (= (:result (ask {:imageAttachmentConstraints constraints}))
                 {:answer "" :wasFreeform true :attachments [{:type "image" :data "AQ==" :mimeType "image/png"
                                                              :displayName "screen" :description ""}]}))
          (is (= (:image-attachment-constraints @seen) {:allowed-mime-types ["image/png"] :max-bytes-per-image 1 :max-count 1}))
          (doseq [bad [{} {:imageAttachmentConstraints nil}
                       {:imageAttachmentConstraints (assoc constraints :maxCount 0)}
                       {:imageAttachmentConstraints (assoc constraints :maxBytesPerImage 0)}
                       {:imageAttachmentConstraints (assoc constraints :allowedMimeTypes ["image/jpeg"])}]]
            (is (= (get-in (ask bad) [:error :code]) -32603)))
          (doseq [bad [(assoc answer :attachments nil) (assoc answer :was-freeform? false)
                       (assoc-in answer [:attachments 0 :data] "AQI=")
                       (assoc-in answer [:attachments 0 :data] "AQ")
                       (assoc-in answer [:attachments 0 :description] nil)]]
            (reset! reply bad)
            (is (= (get-in (ask {:imageAttachmentConstraints constraints}) [:error :code]) -32603)))
          (reset! reply {:answer "ok" :was-freeform? false :attachments []})
          (is (= (:result (ask {:imageAttachmentConstraints constraints})) {:answer "ok" :wasFreeform false :attachments []})))))))

(deftest unsolicited-callbacks-dont-grant-authority
  (with-session {}
    (fn [_ _ _ request!]
      (is (= (:result (request! "exitPlanMode.request" {:summary "" :actions [] :recommendedAction ""}))
             {:approved false}))
      (is (= (:result (request! "autoModeSwitch.request" {})) {:response "no"}))
      (is (= (get-in (request! "userInput.request" {:question "?"}) [:error :code]) -32603))
      (is (= (get-in (request! "elicitation.request" {}) [:error :code]) -32601)))))

(deftest human-prompts-leave-workers-for-other-sessions
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        owner (reverse/open! conn {})
        entered (java.util.concurrent.CountDownLatch. 4)
        interrupted (java.util.concurrent.CountDownLatch. 4)
        hold (promise)
        wait-for-user (fn [_ _]
                        (.countDown entered)
                        (try @hold (catch InterruptedException e (.countDown interrupted) (throw e))))
        reg (reverse/register! owner "A" nil
                               {:interaction {:on-user-input-request wait-for-user :on-exit-plan-mode-request wait-for-user
                                              :on-shell-authentication-request wait-for-user
                                              :on-auto-mode-switch-request wait-for-user}})
        shell {:program "ssh" :prompt "password" :inputKind "secret" :requestId "pw"}
        requests [["user" "userInput.request" {:question "?"}]
                  ["exit" "exitPlanMode.request" {:summary "p" :recommendedAction "done" :actions ["done"]}]
                  ["shell" "shellAuthentication.request" shell]
                  ["auto" "autoModeSwitch.request" {}]]
        send! (fn [id method params]
                (peer/write-framed! ->client {:jsonrpc "2.0" :id id :method method :params params}))
        replies (fn [n]
                  (let [reader (future (into {} (map (juxt :id identity)) (repeatedly n #(peer/read-framed <-client))))]
                    (try (let [result (deref reader 3000 ::timeout)]
                           (when (= ::timeout result) (throw (ex-info "Timed out reading reverse replies" {})))
                           result)
                         (finally (future-cancel reader)))))]
    (try
      (reverse/register! owner "B" nil {:hooks {:handlers {:on-agent-stop (fn [_ _] {:decision :block :reason "review"})}}})
      (doseq [[id method p] requests] (send! id method (assoc p :sessionId "A")))
      (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
      ;; More than the 16 protocol workers: excess prompts must not wait for a human.
      (doseq [i (range 16)]
        (let [[_ method p] (nth requests (mod i 4))]
          (send! (str "surplus" i) method (assoc p :sessionId "A" :requestId (str i)))))
      (send! "hook" "hooks.invoke" {:sessionId "B" :hookType "agentStop" :input {}})
      (let [ready (replies 17)]
        (is (= (get-in ready ["hook" :result :output]) {:decision "block" :reason "review"}))
        (doseq [i (range 16)]
          (let [r (get ready (str "surplus" i))]
            (is (case (mod i 4)
                  0 (= (get-in r [:error :code]) -32000)
                  1 (= (:result r) {:approved false})
                  2 (= (:result r) {:action "cancel"})
                  3 (= (:result r) {:response "no"}))))))
      (send! "cancel" "shellAuthentication.cancel" {:sessionId "A" :requestId "pw"})
      (is (= (update-vals (replies 2) :result) {"shell" {:action "cancel"} "cancel" {}}))
      (reverse/unregister! owner reg)
      (is (= (set (keys (replies 3))) #{"user" "exit" "auto"}))
      (is (.await interrupted 3 java.util.concurrent.TimeUnit/SECONDS))
      (finally
        (deliver hold nil)
        (reverse/close! owner)
        (protocol/disconnect conn)
        (.close ^java.io.OutputStream ->client)
        (.close ^java.io.InputStream <-client)))))

(deftest prompt-admission-is-fixed-at-ingress-and-released-on-protocol-rejection
  (peer/with-peer {:request-handler-threads 2}
    (fn [conn _]
      (let [owner (reverse/open! conn {})
            handler (constantly {:approved? true})
            method "exitPlanMode.request"
            p {:session-id "s" :summary "plan" :recommended-action "exit" :actions ["exit"]}
            {:keys [request-preparer request-handler]} (:connection @(:state-atom conn))
            prepare #(request-preparer method p)
            run (fn [{:keys [params release!]}]
                  (try (a/<!! (request-handler method params))
                       (finally (when release! (release!)))))]
        (try
          (reverse/register! owner "s" nil {:interaction {:on-exit-plan-mode-request handler}})
          (let [first (prepare) surplus (prepare)]
            ;; A rejected protocol submission gives back its admission. A request
            ;; already refused must not claim a later opening or run a callback.
            ((:release! first))
            (is (= (run surplus) {:result {:approved false}}))
            (is (= (run (prepare)) {:result {:approved true}})))
          (finally (reverse/close! owner)))))))

(deftest a-visible-non-shell-answer-frees-its-slot-for-the-next-question
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        owner (reverse/open! conn {})
        occupied (java.util.concurrent.CountDownLatch. 3)
        release-questions (promise)
        answered (promise)
        release-worker (promise)
        calls (atom [])
        offer a/offer!
        ask! (fn [id question]
               (peer/write-framed! ->client {:jsonrpc "2.0" :id id :method "userInput.request"
                                             :params {:sessionId "s" :question question}}))
        read! #(deref (future (peer/read-framed <-client)) 3000 nil)]
    (try
      (reverse/register! owner "s" nil
                         {:interaction {:on-user-input-request
                                        (fn [{:keys [question]} _]
                                          (if (= question "hold")
                                            (do (.countDown occupied) (deref release-questions 5000 nil))
                                            (swap! calls conj question))
                                          {:answer question :was-freeform? true})}})
      (with-redefs [a/offer! (fn [ch msg]
                               (let [sent (offer ch msg)]
                                 (when (and sent (identical? ch (:outgoing-ch conn)) (= (:id msg) "first"))
                                   (deliver answered true)
                                   (deref release-worker 5000 nil))
                                 sent))]
        (dotimes [i 3] (ask! (str i) "hold"))
        (is (.await occupied 3 TimeUnit/SECONDS))
        (ask! "first" "first")
        (is (true? (deref answered 3000 nil)))
        (is (= (get-in (read!) [:result :answer]) "first"))
        (ask! "next" "next")
        (is (= (get-in (read!) [:result :answer]) "next"))
        (is (= @calls ["first" "next"])))
      (finally
        (deliver release-worker true)
        (deliver release-questions true)
        (reverse/close! owner)
        (protocol/disconnect conn)
        (.close ^java.io.OutputStream ->client)
        (.close ^java.io.InputStream <-client)))))

(deftest admitted-prompt-survives-worker-handoff
  (peer/with-peer {:request-handler-threads 2}
    (fn [conn _]
      (let [owner (reverse/open! conn {})
            ^ThreadPoolExecutor pool (:human-pool owner)
            at-handoff (promise) allow-handoff (promise)
            ;; FutureTask publishes its result before done() and before the
            ;; executor can take another job. Hold that actual JDK handoff open.
            finishing (proxy [FutureTask] [(fn [] :complete)]
                        (done [] (deliver at-handoff true) @allow-handoff))
            {:keys [request-preparer request-handler]} (:connection @(:state-atom conn))
            method "exitPlanMode.request"
            p {:session-id "s" :summary "p" :recommended-action "exit" :actions ["exit"]}]
        (try
          (reverse/register! owner "s" nil {:interaction {:on-exit-plan-mode-request (constantly {:approved? true})}})
          (.execute pool finishing)
          (is (= (.get ^FutureTask finishing 3 TimeUnit/SECONDS) :complete))
          (is (= (deref at-handoff 3000 nil) true))
          (let [prepared (request-preparer method p)
                response (future (try (a/<!! (request-handler method (:params prepared)))
                                      (finally ((:release! prepared)))))
                ;; Observe enqueue or rejection without becoming a competing
                ;; consumer on the executor's queue.
                until (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 3))]
            (loop []
              (when-not (or (.peek (.getQueue pool)) (realized? response) (> (System/nanoTime) until))
                (Thread/yield)
                (recur)))
            (is (some? (.peek (.getQueue pool))))
            (deliver allow-handoff true)
            (is (= (deref response 3000 nil) {:result {:approved true}})))
          (finally (deliver allow-handoff true) (reverse/close! owner)))))))

(deftest cancelled-prompt-occupies-its-slot-until-the-handler-returns
  (peer/with-peer {:request-handler-threads 2}
    (fn [conn _]
      (let [owner (reverse/open! conn {})
            entered (promise) ignore-interrupt (promise) release (promise)
            method "shellAuthentication.request"
            p {:session-id "s" :request-id "secret" :program "ssh" :prompt "key" :input-kind "secret"}
            {:keys [request-preparer request-handler]} (:connection @(:state-atom conn))
            run (fn [m {:keys [params release!]}]
                  (try (a/<!! (request-handler m params))
                       (finally (when release! (release!)))))]
        (try
          (reverse/register! owner "s" nil
                             {:interaction {:on-shell-authentication-request
                                            (fn [_ _]
                                              (deliver entered true)
                                              (try @release (catch InterruptedException _
                                                              (deliver ignore-interrupt true)
                                                              @release))
                                              {:action :accept :value "too late"})
                                            :on-auto-mode-switch-request (constantly :yes)}})
          (let [shell (future (run method (request-preparer method p)))]
            (is (= (deref entered 3000 nil) true))
            (run "shellAuthentication.cancel" (request-preparer "shellAuthentication.cancel" p))
            (is (= (deref shell 3000 nil) {:result {:action "cancel"}}))
            (is (= (deref ignore-interrupt 3000 nil) true))
            (let [m "autoModeSwitch.request"
                  surplus (request-preparer m {:session-id "s"})]
              ;; Do not queue work behind a cancelled handler that still owns the worker.
              (is (nil? (:release! surplus)))
              (is (= (run m surplus) {:result {:response "no"}}))
              ;; A task behind the cancelled handler proves its run() has exited.
              (let [drained (.submit ^ThreadPoolExecutor (:human-pool owner) ^Runnable (fn []))]
                (deliver release true)
                (.get drained 3 TimeUnit/SECONDS))
              (is (= (run m (request-preparer m {:session-id "s"})) {:result {:response "yes"}}))))
          (finally (deliver release true) (reverse/close! owner)))))))

(deftest framed-cancel-releases-sole-protocol-worker
  (let [{:keys [conn ->client <-client]} (peer/open-connection {:request-handler-threads 1})
        entered (promise) release (promise)
        owner (reverse/open! conn {})
        wire (fn [id method params]
               (peer/write-framed! ->client {:jsonrpc "2.0" :id id :method method
                                             :params (assoc params :sessionId "s" :requestId "r")}))]
    (try
      (reverse/register! owner "s" nil
                         {:interaction {:on-shell-authentication-request
                                        (fn [_ _]
                                          (deliver entered true)
                                          (try @release (catch InterruptedException _ @release))
                                          {:action :accept :value "too-late"})}})
      (wire "request" "shellAuthentication.request" {:prompt "pw" :program "ssh" :inputKind "secret"})
      (is (= (deref entered 3000 nil) true))
      (wire "cancel" "shellAuthentication.cancel" {})
      (let [replies (future (into {} (map (juxt :id :result))
                                  [(peer/read-framed <-client) (peer/read-framed <-client)]))]
        (try (is (= (deref replies 3000 nil) {"request" {:action "cancel"} "cancel" {}}))
             (finally (future-cancel replies))))
      (finally
        (deliver release true)
        (reverse/close! owner)
        (protocol/disconnect conn)
        (.close ^java.io.OutputStream ->client)
        (.close ^java.io.InputStream <-client)))))
