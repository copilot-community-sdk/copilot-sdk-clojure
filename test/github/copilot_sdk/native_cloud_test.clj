(ns github.copilot-sdk.native-cloud-test
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.memory-fs-fixture :as memory-fs]
            [github.copilot-sdk.native-callbacks :as callbacks]
            [github.copilot-sdk.native-client :as client]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.native-offline-fixture :as offline]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.reverse :as reverse]
            [github.copilot-sdk.session-options :as options]
            [github.copilot-sdk.turn :as turn]
            [github.copilot-sdk.util :as util])
  (:import [java.io PipedInputStream PipedOutputStream]
           [java.util.concurrent.locks ReentrantReadWriteLock]))

(defn- bounded [p]
  (let [v (deref p 5000 ::timeout)]
    (when (= ::timeout v) (throw (ex-info "Timed out waiting for fixture" {})))
    v))

(defn- with-runtime [opts f & [release!]]
  (with-open [in (PipedInputStream. 65536) to-sdk (PipedOutputStream. in)
              from-sdk (PipedInputStream. 65536) out (PipedOutputStream. from-sdk)]
    (let [send! (fn ([] (.close to-sdk)) ([msg] (peer/write-framed! to-sdk (assoc msg :jsonrpc "2.0"))))
          next! #(bounded (future (peer/read-framed from-sdk)))
          reply! (fn [request result] (send! {:id (:id request) :result result}))]
      (with-redefs [host/open! (fn [_] {:in in :out out :configure-ui! (constantly nil)
                                        :close! (or release! (constantly nil))})]
        (let [opening (future (sdk/open-client!! (assoc opts :mode :coding-agent)))]
          (reply! (next!) {:protocolVersion 4})
          (when (:session-fs opts) (reply! (next!) {:success true}))
          (let [c (bounded opening)]
            (try (f c send! next! reply!)
                 (finally (sdk/force-close!! c)))))))))

(def ^:private fs-options
  {:session-fs {:initial-cwd "/v" :session-state-path "/v/s" :conventions "posix"}})

(defn- token-request [register reason]
  {:id reason :method "gitHubToken.getToken"
   :params {:registrationId register :host "example.invalid" :reason reason}})

(deftest server-identity-routes-the-frames-immediately-after-create
  (let [id "server-assigned"
        factory (promise)
        release (promise)
        seen (promise)
        token-calls (atom [])
        provider (assoc (zipmap [:read-file :write-file :append-file :stat :mkdir :rm :rename
                                 :readdir :readdir-with-types] (repeat (constantly nil)))
                        :exists #(= "/v" %))]
    (with-runtime fs-options
      (fn [c send! next! reply!]
        (let [opening (future (sdk/open-session!!
                               c {:cloud {}
                                  :state {:create-session-fs-provider
                                          (fn [s] (deliver factory [s (.getName (Thread/currentThread))])
                                            (bounded release) provider)}
                                  :events {:on-event #(deliver seen %)}
                                  :github {:token-provider (fn [args] (swap! token-calls conj args)
                                                             {:kind :cancelled})}}))
              create (next!)
              reg (get-in create [:params :github :tokenProviderRegistrationId])]
          (try
            (is (= "session.create" (:method create)))
            (is (not (contains? (:params create) :sessionId)))
            (send! (token-request reg "initial"))
            (is (= {:kind "cancelled"} (:result (next!))))
            (reply! create {:sessionId id})
            (send! {:method "session.event" :params {:sessionId id :event {:type "session.info" :id "first" :data {}}}})
            (send! {:id "fs" :method "sessionFs.exists" :params {:sessionId id :path "/v"}})
            (send! (token-request reg "refresh"))
            (let [[handle worker] (bounded factory)]
              (is (= (sdk/session-id handle) id))
              (is (= worker "copilot-native-reverse"))
              (is (= (:id (bounded seen)) "first"))
              (is (= (:result (next!)) {:kind "cancelled"}))
              (is (= @token-calls [{:host "example.invalid" :reason :initial}
                                   {:host "example.invalid" :reason :refresh :session-id id}]))
              (send! (assoc-in (token-request reg "refresh") [:params :sessionId] "other"))
              (is (= -32602 (get-in (next!) [:error :code])))
              (let [other (future (sdk/open-session!! c {:cloud {} :session-id "explicit"
                                                         :state {:create-session-fs-provider (constantly provider)}}))
                    explicit (next!)]
                (is (= (get-in explicit [:params :sessionId]) "explicit"))
                (reply! explicit {:sessionId "explicit"})
                (is (= (sdk/session-id (bounded other)) "explicit")))
              (deliver release true)
              (is (= (:result (next!)) {:exists true}))
              (is (identical? handle (bounded opening))))
            (finally (deliver release true))))))))

(defn- try-open [c options]
  (future (try (sdk/open-session!! c options) (catch Exception e e))))

(deftest cloud-create-reconciles-early-decisions-before-ordered-delivery
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [observed (atom [])
            delivered (promise)
            called (atom [])
            admitted (promise)
            answer (a/promise-chan)
            open (try-open c {:cloud {}
                              :permissions {:on-request (fn [request ctx]
                                                          (swap! called conj (:path request))
                                                          (deliver admitted (:cancelled? ctx))
                                                          answer)}
                              :events {:on-event (fn [{:keys [id]}]
                                                   (swap! observed conj id)
                                                   (when (= id "live") (deliver delivered true)))}})
            create (next!)
            emit! (fn [session id type data]
                    (send! {:method "session.event" :params {:sessionId session :event {:type type :id id :data data}}}))
            request! #(emit! "cloud" % "permission.requested" {:requestId % :permissionRequest {:kind "read" :path %}})]
        ;; Stop the router, not the reader. Even a manual response immediately
        ;; after registration must see the completion parsed before the reply.
        (locking (.-lifecycle c)
          (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "created"}})
          (request! "answered")
          (emit! "cloud" "complete" "permission.completed" {:requestId "answered"})
          (request! "pending")
          (emit! "other" "unrelated" "permission.completed" {:requestId "pending"})
          (reply! create {:sessionId "cloud"})
          (let [s (bounded open)]
            (is (= :not-pending
                   (bounded (future (try (sdk/respond!! s :permission {:request-id "answered" :result {:kind :reject}})
                                         (catch Exception e (:type (ex-data e))))))))))
        ;; A completion received after the worker started is ordinary live
        ;; cancellation, not part of the pre-registration reconciliation.
        (let [cancelled? (bounded admitted)]
          (is (false? (cancelled?)))
          (emit! "cloud" "live-complete" "permission.completed" {:requestId "pending"})
          (emit! "cloud" "live" "session.info" {})
          (bounded delivered)
          (is (true? (cancelled?)))
          (is (= @observed ["answered" "complete" "pending" "live-complete" "live"]))
          (is (= @called ["pending"])))))))

(deftest held-claims-precede-manual-responses-and-respect-abort-scope
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [calls (atom []) observations (atom []) observed (promise) abstained (promise)
            opening (try-open c {:cloud {}
                                 :permissions {:on-request (fn [_ {:keys [request-id]}]
                                                             (swap! calls conj request-id)
                                                             {:kind :no-result})}
                                 :events {:on-event (fn [{:keys [id]}]
                                                      (swap! observations conj id)
                                                      (when (= id "end") (deliver observed true)))}})
            create (next!)
            ids ["root" "child" "abort-all" "a" "auto-only" "b" "abort-a" "hook" "hook-copy" "history" "checkpoint" "end"]
            emit! (fn [event] (send! {:method "session.event" :params {:sessionId "cloud" :event event}}))
            request! (fn [id agent hook?]
                       (emit! {:id id :agentId agent :type "permission.requested"
                               :data {:requestId id :permissionRequest {:kind "read"} :resolvedByHook hook?}}))
            answer {:result {:kind :reject}}
            s (locking (.-lifecycle c)
                (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "created"}})
                (request! "root" nil false)
                (request! "child" "a" false)
                (emit! {:id "abort-all" :type "abort"})
                (request! "a" "a" false)
                (request! "auto-only" "a" false)
                (request! "b" "b" false)
                (emit! {:id "abort-a" :type "abort" :agentId "a"})
                (request! "hook" "a" true)
                (emit! {:id "hook-copy" :type "permission.requested" :agentId "a" :checkpoint true
                        :data {:requestId "hook-copy" :permissionRequest {:kind "read"} :resolvedByHook true}})
                (request! "history" nil false)
                (emit! {:id "checkpoint" :type "permission.completed" :checkpoint true :data {:requestId "history"}})
                (reply! create {:sessionId "cloud"})
                (let [s (bounded opening)
                      claims (:claims (get @(:sessions (.-callbacks c)) "cloud"))]
                  (add-watch claims abstained (fn [_ _ _ state]
                                                (when (= :manual (first (get state [:permission "b"])))
                                                  (deliver abstained true))))
                  (doseq [id ["root" "child" "a" "hook" "hook-copy" "b" "history"]]
                    (is (= :not-pending
                           (bounded (future (try (sdk/respond!! s :permission (assoc answer :request-id id))
                                                 (catch Exception e (:type (ex-data e)))))))
                        id))
                  s))]
        (try
          (emit! {:type "session.info" :id "end"})
          (bounded observed)
          (is (= @observations ids))
          (bounded abstained)
          (is (= @calls ["b"]))
          (let [manual (future (sdk/respond!! s :permission (assoc answer :request-id "b")))
                frame (next!)]
            (is (= (get-in frame [:params :requestId]) "b"))
            (reply! frame {:success true})
            (is (= (bounded manual) {:success true})))
          (finally (remove-watch (:claims (get @(:sessions (.-callbacks c)) "cloud")) abstained)))))))

(deftest held-event-capacity-evicts-the-oldest-id-not-the-connection
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [emit! #(send! {:method "session.event" :params {:sessionId %1 :event {:id %2 :type "session.info"}}})
            observe (fn []
                      (let [seen (atom []) done (promise)]
                        [{:on-event (fn [{:keys [id]}]
                                      (swap! seen conj id)
                                      (when (= id "end") (deliver done @seen)))}
                         done]))
            [live-events live-seen] (observe)
            live (try-open c {:session-id "live" :events live-events})
            _ (reply! (next!) {:sessionId "live"})
            _ (bounded live)
            [noisy-events noisy-seen] (observe)
            noisy (try-open c {:cloud {} :events noisy-events}) noisy-request (next!)
            [other-events other-seen] (observe)
            other (try-open c {:cloud {} :events other-events}) other-request (next!)]
        (emit! "noisy" "first")
        (emit! "other" "kept")
        (dotimes [_ 8190] (emit! "noisy" "drop"))
        (emit! "noisy" "tail")
        (emit! "live" "end")
        (is (= (bounded live-seen) ["end"]))
        (reply! other-request {:sessionId "other"})
        (emit! "other" "end")
        (reply! noisy-request {:sessionId "noisy"})
        (emit! "noisy" "end")
        (is (= (bounded other-seen) ["kept" "end"]))
        (is (= (bounded noisy-seen) ["tail" "end"]))
        (is (= (mapv #(sdk/session-id (bounded %)) [noisy other]) ["noisy" "other"]))))))

(deftest held-wire-byte-budget-is-shared-across-creates
  (with-runtime {}
    (fn [c send! next! reply!]
      (with-redefs-fn
        {#'client/held-byte-limit 1600}
        (fn []
          (let [observe (fn []
                          (let [seen (atom []) done (promise)
                                opening (try-open c {:cloud {} :events {:on-event (fn [{:keys [id]}]
                                                                                    (swap! seen conj id)
                                                                                    (when (= "end" id) (deliver done @seen)))}})]
                            [(next!) opening done]))
                [first-create first-open first-seen] (observe)
                [second-create second-open second-seen] (observe)
                [large-create large-open large-seen] (observe)
                event! (fn [session id n]
                         (send! {:method "session.event" :params {:sessionId session
                                                                  :event {:id id :type "session.info" :data {:message (.repeat "x" n)}}}}))]
            (event! "a" "oldest" 500)
            (event! "b" "kept" 500)
            (event! "a" "evicts-oldest" 500)
            (event! "oversized-only" "prior" 0)
            (event! "oversized-only" "oversized" 1601)
            (event! "a" "new" 0)
            (reply! second-create {:sessionId "b"})
            (event! "b" "end" 0)
            (reply! first-create {:sessionId "a"})
            (event! "a" "end" 0)
            (reply! large-create {:sessionId "oversized-only"})
            (event! "oversized-only" "end" 0)
            (is (= (bounded second-seen) ["kept" "end"]))
            (is (= (bounded first-seen) ["evicts-oldest" "new" "end"]))
            (is (= (bounded large-seen) ["end"]))
            (is (= (mapv #(sdk/session-id (bounded %)) [first-open second-open large-open]) ["a" "b" "oversized-only"]))
            (is (zero? (:bytes @(.-heldEvents c))))))))))

(deftest eof-drains-claimed-events-without-starting-held-decisions
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [calls (atom []) seen (promise)
            opts {:cloud {} :permissions {:on-request (fn [req _] (swap! calls conj req) {:kind :reject})}
                  :events {:on-event #(deliver seen (:id %))}}
            claimed (try-open c opts) create (next!)
            unanswered (try-open c opts)
            _ (next!)
            lost (sdk/lifecycle-events c)]
        (try
          ;; Park publication of the first frame, not the independent reader.
          (locking (.-lifecycle c)
            (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "created"}})
            (doseq [id ["never-owned" "owned"]]
              (send! {:method "session.event"
                      :params {:sessionId id :event {:id id :type "permission.requested"
                                                     :data {:requestId id :permissionRequest {:kind "read"}}}}}))
            (reply! create {:sessionId "owned"})
            (is (= (sdk/session-id (bounded claimed)) "owned"))
            (send!)
            (is (= (ex-message (bounded unanswered)) "Connection closed by remote")))
          (is (= (bounded seen) "owned"))
          (is (= (-> (bounded (future (a/<!! (a/into [] lost)))) last :error ex-data :type)
                 :connection-closed))
          (is (empty? @calls))
          (finally (a/close! lost)))))))

(deftest release-during-held-decision-overflow-preserves-the-whole-observer-batch
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [seen (atom []) hold (a/chan) full (promise) resume (promise)
            ids (mapv str (range 35))
            opening (try-open c {:cloud {} :permissions {:on-request (fn [_ _] hold)}
                                 :events {:on-event #(swap! seen conj (:id %))}})
            create (next!) emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
            fail @#'callbacks/fail-session! admit @#'callbacks/try-after-decisions!]
        (try
          ;; Park workers and busy senders before they take the reader lock.
          ;; The 35th request exceeds two workers, 16 queued requests and 16 refusals.
          (with-redefs [callbacks/try-after-decisions! (fn [& args] (bounded resume) (apply admit args))
                        callbacks/fail-session! (fn [& args] (deliver full true) (bounded resume) (apply fail args))]
            (let [[s form observer]
                  (locking (.-lifecycle c)
                    (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
                    (emit! {:id "first" :type "session.info"})
                    (doseq [id ids] (emit! {:id id :type "permission.requested"
                                            :data {:requestId id :permissionRequest {:kind "read"}}}))
                    (emit! {:id "last" :type "session.info"})
                    (reply! create {:sessionId "s" :capabilities {:ui {:elicitation true}}})
                    (let [s (bounded opening) form (sdk/elicit! s {:message "m" :requested-schema {:type "object"}})]
                      (is (= (:method (next!)) "session.ui.elicitation"))
                      [s form @(get-in @(:sessions (.-callbacks c)) ["s" :observer :task])]))]
              (bounded full)
              (let [closing (future (sdk/close!! s)) detach (next!)]
                (is (= (:method detach) "session.detach"))
                (reply! detach {:success true})
                (is (= (-> (bounded (future (a/<!! form))) :error ex-data :type) :session-closed))
                (deliver resume true)
                (bounded closing)
                (.get ^java.util.concurrent.Future observer 5 java.util.concurrent.TimeUnit/SECONDS)
                (is (= @seen (into ["first"] (conj ids "last")))))))
          (finally (deliver resume true) (a/close! hold)))))))

(deftest pre-open-events-belong-to-the-configured-observer-not-a-later-subscriber
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [release (promise) seen (atom []) done (promise)
            observe (fn [{:keys [id error]}]
                      (if error (deliver done error)
                          (do (when (= "0" id) (bounded release))
                              (swap! seen conj id)
                              (when (= "live" id) (deliver done @seen)))))
            opening (try-open c {:cloud {} :events {:on-event observe}})
            create (next!)
            emit! #(send! {:method "session.event" :params {:sessionId "cloud" :event {:id % :type "session.info"}}})
            sub (locking (.-lifecycle c)
                  (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "session.updated"}})
                  (dotimes [i 80] (emit! (str i)))
                  (reply! create {:sessionId "cloud"})
                  (sdk/subscribe (bounded opening)))]
        (try
          (emit! "live")
          (let [[event port] (a/alts!! [sub (a/timeout 5000)])]
            (is (identical? sub port))
            (is (= (:id event) "live")))
          (deliver release true)
          (is (= (bounded done) (conj (mapv str (range 80)) "live")))
          (finally (deliver release true) (a/close! sub)))))))

(deftest storage-setup-gates-cloud-decisions-but-not-observations
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (doseq [fail? [true false]]
        (let [id (if fail? "failed" "working")
              entered (promise) release (promise) observed (promise) requests (atom [])
              opts {:cloud {}
                    :state {:create-session-fs-provider
                            (fn [_]
                              (deliver entered true) (bounded release)
                              (if fail? (throw (ex-info "Storage unavailable" {}))
                                  (memory-fs/provider (atom {}))))}
                    :permissions {:on-request (fn [_ ctx]
                                                (swap! requests conj (:request-id ctx))
                                                {:kind :reject})}
                    :events {:on-event #(when (= "fence" (:id %)) (deliver observed true))}}
              opening (try-open c opts) create (next!)
              emit! (fn [event] (send! {:method "session.event" :params {:sessionId id :event event}}))
              request! (fn [r] (emit! {:id r :type "permission.requested"
                                       :data {:requestId r :permissionRequest {:kind "read"}}}))]
          (try
            (request! "held")
            (reply! create {:sessionId id})
            (bounded entered)
            (emit! {:type "permission.completed" :data {:requestId "held"}})
            (request! "pending")
            (emit! {:id "fence" :type "session.info"})
            (bounded observed)
            (deliver release true)
            (let [frame (next!)]
              (reply! frame {:success true})
              (if fail?
                (do (is (= (:method frame) "session.detach"))
                    (is (= (ex-message (bounded opening)) "Storage unavailable"))
                    (is (empty? @requests)))
                (do (is (= [(:method frame) (get-in frame [:params :requestId])]
                           ["session.permissions.handlePendingPermissionRequest" "pending"]))
                    (is (= (sdk/session-id (bounded opening)) id))
                    (is (= @requests ["pending"])))))
            (finally (deliver release true))))))))

(deftest cloud-create-fails-when-its-held-batch-cannot-be-queued
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [queue (:notification-queue (.-connection c))
            opening (try-open c {:cloud {}}) create (next!)
            marker {:method "session.lifecycle" :params {:sessionId "barrier" :type "session.updated"}}]
        (locking (.-lifecycle c)
          (send! marker)
          (send! {:method "session.event" :params {:sessionId "s" :event {:id "early" :type "session.info"}}})
          ;; Do not overflow while filling: the create reply must be the first failed offer.
          (loop []
            (when (pos? (.remainingCapacity queue))
              (dotimes [_ (min 1024 (.remainingCapacity queue))] (send! marker))
              (send! {:id "fence" :method "unknown" :params {}})
              (is (= (:id (next!)) "fence"))
              (recur)))
          (reply! create {:sessionId "s"})
          (is (= (:type (ex-data (bounded opening))) :connection-closed)))))))

(deftest storage-readiness-cannot-overtake-an-admitted-tool-completion
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [ready (promise) held (promise) completed (promise) calls (atom [])
            emit! (fn [type data] (send! {:method "session.event" :params {:sessionId "s" :event {:type type :data data}}}))
            opening (try-open c {:cloud {}
                                 :state {:create-session-fs-provider (fn [_] (bounded ready) (memory-fs/provider (atom {})))}
                                 :tools {:definitions [{:name "count" :handler (fn [_ ctx] (swap! calls conj (:request-id ctx)) "ok")}]}
                                 :events {:on-event (fn [{:keys [type]}]
                                                      (case type
                                                        :copilot/external_tool.requested (deliver held true)
                                                        :copilot/external_tool.completed (deliver completed true)
                                                        nil))}})
            create (next!)]
        (try
          (emit! "external_tool.requested" {:requestId "old" :sessionId "s" :toolName "count" :toolCallId "call" :arguments {}})
          (reply! create {:sessionId "s"})
          (bounded held)
          (locking (.-lifecycle c)
            (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "session.updated"}})
            (emit! "external_tool.completed" {:requestId "old"})
            (send! {:id "read" :method "unknown" :params {}})
            (is (= (:id (next!)) "read"))
            (deliver ready true)
            (is (= (sdk/session-id (bounded opening)) "s")))
          (bounded completed)
          (is (empty? @calls))
          (finally (deliver ready true)))))))

(deftest storage-activation-is-ordered-after-a-notification-being-decoded
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [ready (promise) first-seen (promise) decoding (promise) finish-decode (promise)
            opener (promise) called (promise) calls (atom []) completed (promise) parse json/read-str
            opening (try-open c {:cloud {}
                                 :state {:create-session-fs-provider (fn [_] (bounded ready) (memory-fs/provider (atom {})))}
                                 :tools {:definitions [{:name "count" :handler (fn [_ ctx]
                                                                                 (swap! calls conj (:request-id ctx))
                                                                                 (deliver called true) "ok")}]}
                                 :events {:on-event (fn [{:keys [type]}]
                                                      (case type
                                                        :copilot/external_tool.requested (deliver first-seen true)
                                                        :copilot/external_tool.completed (deliver completed true)
                                                        nil))}})
            create (next!)
            emit! #(send! {:method "session.event" :params {:sessionId "s" :event {:type %1 :data %2}}})
            offer! rpc/offer-notification!]
        (try
          (emit! "external_tool.requested" {:requestId "old" :sessionId "s" :toolName "count" :toolCallId "call" :arguments {}})
          (reply! create {:sessionId "s"})
          (bounded first-seen)
          (with-redefs [json/read-str (fn [& args]
                                        (let [msg (apply parse args)]
                                          (when (= "external_tool.completed" (get-in msg ["params" "event" "type"]))
                                            (deliver decoding true) (bounded finish-decode))
                                          msg))
                        rpc/offer-notification! (fn [conn msg & opts]
                                                  (when (= ::client/storage-ready (:method msg))
                                                    (deliver opener (Thread/currentThread)))
                                                  (apply offer! conn msg opts))]
            (emit! "external_tool.completed" {:requestId "old"})
            (bounded decoding)
            (deliver ready true)
            (let [thread (bounded opener) until (+ (System/nanoTime) 3000000000)]
              (loop []
                (when-not (or (= Thread$State/BLOCKED (.getState ^Thread thread)) (realized? called))
                  (when (> (System/nanoTime) until) (throw (ex-info "Neither readiness nor handler reached the boundary" {})))
                  (Thread/yield)
                  (recur))))
            (deliver finish-decode true)
            (is (= (sdk/session-id (bounded opening)) "s"))
            (bounded completed)
            (is (empty? @calls)))
          (finally (deliver ready true) (deliver finish-decode true)))))))

(deftest reverse-interactions-reject-while-session-storage-is-unavailable
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (doseq [fail? [false true]]
        (let [id (if fail? "broken-fs" "ready-fs")
              entered (promise) ready (promise) calls (atom [])
              opening (try-open c {:cloud {}
                                   :state {:create-session-fs-provider
                                           (fn [_] (deliver entered true) (bounded ready)
                                             (if fail? (throw (ex-info "Storage unavailable" {}))
                                                 (memory-fs/provider (atom {}))))}
                                   :interaction {:on-user-input-request
                                                 (fn [_ _] (swap! calls conj :input) {:answer "ok" :was-freeform? true})}
                                   :hooks {:handlers {:on-pre-tool-use
                                                      (fn [_ _] (swap! calls conj :hook) {:permission-decision :deny})}}})
              create (next!)
              ask! (fn []
                     (send! {:id "input" :method "userInput.request" :params {:sessionId id :question "Continue?"}})
                     (send! {:id "hook" :method "hooks.invoke"
                             :params {:sessionId id :hookType "preToolUse" :input {:toolName "test"}}})
                     (into {} (map (juxt :id identity)) [(next!) (next!)]))]
          (try
            (reply! create {:sessionId id})
            (bounded entered)
            (is (= (update-vals (ask!) #(get-in % [:error :code])) {"input" -32001 "hook" -32001}))
            (is (empty? @calls))
            (deliver ready true)
            (if fail?
              (let [detach (next!)]
                (is (= (:method detach) "session.detach"))
                (reply! detach {:success true})
                (is (= (ex-message (bounded opening)) "Storage unavailable")))
              (do (is (= (sdk/session-id (bounded opening)) id))
                  (is (= (update-vals (ask!) :result)
                         {"input" {:answer "ok" :wasFreeform true} "hook" {:output {:permissionDecision "deny"}}}))
                  (is (= (frequencies @calls) {:input 1 :hook 1}))))
            (finally (deliver ready true))))))))

(deftest manual-permission-replies-wait-for-storage-event-reconciliation
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [ready (promise) observed (promise) waiting (promise)
            opening (try-open c {:cloud {}
                                 :state {:create-session-fs-provider (fn [_] (bounded ready) (memory-fs/provider (atom {})))}
                                 :events {:on-event #(when (= "old" (:id %)) (deliver observed true))}})
            create (next!) alts!! a/alts!!
            emit! (fn [type data]
                    (send! {:method "session.event" :params {:sessionId "s" :event {:type type :id "old" :data data}}}))]
        (try
          (emit! "permission.requested" {:requestId "old" :permissionRequest {:kind "read"}})
          (reply! create {:sessionId "s"}) (bounded observed)
          (let [[s answer]
                (locking (.-lifecycle c)
                  (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "session.updated"}})
                  (emit! "permission.completed" {:requestId "old"})
                  (send! {:id "fence" :method "unknown" :params {}})
                  (is (= (:id (next!)) "fence"))
                  (deliver ready true)
                  (let [s (bounded opening)
                        result (future
                                 (with-redefs [a/alts!! (fn [& args] (deliver waiting true) (apply alts!! args))]
                                   (try (sdk/respond!! s :permission {:request-id "old" :result {:kind :reject}})
                                        (catch Exception e (:type (ex-data e))))))]
                    (bounded waiting)
                    [s result]))]
            (is (= (bounded answer) :not-pending))
            ;; Restored work can have an authoritative ID with no repeated request event.
            (let [live (future (sdk/respond!! s :permission {:request-id "restored" :result {:kind :reject}}))
                  frame (next!)]
              (is (= (get-in frame [:params :requestId]) "restored"))
              (reply! frame {:success true})
              (is (= (bounded live) {:success true}))))
          (finally (deliver ready true)))))))

(deftest saturation-replies-do-not-pass-received-decision-events
  (doseq [settlement [:completion :abort :observation]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [held (a/chan) entered (java.util.concurrent.CountDownLatch. 2) calls (atom []) queued (promise)
              opening (try-open c {:session-id "s" :permissions {:on-request (fn [_ {:keys [request-id]}]
                                                                               (swap! calls conj request-id)
                                                                               (.countDown entered) held)}
                                   :events {:on-event #(when (= "queued-15" (:id %)) (deliver queued true))}})
              _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
              emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
              req! #(emit! {:type "permission.requested" :id % :data {:requestId % :permissionRequest {:kind "read"}}})
              busy (:busy-replies (get @(:sessions (.-callbacks c)) "s")) finished (promise)
              attempted (promise) publish turn/publish! sender rpc/send-request]
          (try
            (req! "a") (req! "b")
            (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
            (dotimes [i 16] (req! (str "queued-" i))) (bounded queued)
            (add-watch busy finished (fn [_ _ before after] (when (and (pos? before) (zero? after)) (deliver finished true))))
            (with-redefs [turn/publish! (fn [turns event & args]
                                          (when (and (identical? turns (.-turns s)) (= "excess" (:id event)))
                                            (bounded attempted))
                                          (apply publish turns event args))
                          rpc/send-request (fn [conn method params & opts]
                                             (if-not (= "excess" (:request-id params))
                                               (apply sender conn method params opts)
                                               (let [ch (apply sender conn method params opts)]
                                                 (deliver attempted true) ch)))]
              (let [life (sdk/lifecycle-events c)]
                (try
                  (locking (.-lifecycle c)
                    (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "park"}})
                    (req! "excess")
                    (emit! (case settlement :completion {:type "permission.completed" :data {:requestId "excess"}}
                                 :abort {:type "abort"} :observation {:type "session.info"}))
                    (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "routed"}})
                    (send! {:id "seen" :method "unknown" :params {}})
                    (is (= (:id (next!)) "seen")))
                  (is (= (mapv :session-id (repeatedly 2 #(bounded (future (a/<!! life))))) ["park" "routed"]))
                  (finally (a/close! life)))))
            (let [ping (future (bounded finished) (sdk/await!! (sdk/ping c "done")))
                  replies (loop [sent []]
                            (let [frame (next!)]
                              (if (= (:method frame) "ping")
                                (do (reply! frame {:message "done"}) sent)
                                (do (reply! frame {:success true})
                                    (recur (conj sent [(get-in frame [:params :requestId]) (get-in frame [:params :result :kind])]))))))]
              (is (= replies (if (= settlement :observation) [["excess" "user-not-available"]] [])) settlement)
              (is (= (bounded ping) {:message "done"}))
              (is (= (set @calls) #{"a" "b"})))
            (finally (remove-watch busy finished) (a/close! held))))))))

(deftest settled-unsent-busy-replies-free-capacity-before-the-router-continues
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [entered (java.util.concurrent.CountDownLatch. 2) hold (a/chan)
            opening (try-open c {:session-id "s" :permissions {:on-request (fn [_ _] (.countDown entered) hold)}})
            _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
            emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
            req! #(emit! {:type "permission.requested" :data {:requestId % :permissionRequest {:kind "read"}}})
            life (sdk/lifecycle-events c) mark! #(send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId %}})
            events (sdk/subscribe s)]
        (try
          (req! "a") (req! "b") (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
          (dotimes [i 16] (req! (str "queued-" i)))
          (mark! "ready") (is (= (:session-id (bounded (future (a/<!! life)))) "ready"))
          (locking (.-lifecycle c)
            (mark! "park")
            (dotimes [i 17]
              (let [id (str "busy-" i)]
                (req! id)
                (emit! {:type "permission.completed" :id id :data {:requestId id}})))
            (mark! "drained")
            (send! {:id "reader" :method "unknown" :params {}})
            (is (= (:id (next!)) "reader")))
          (is (= (mapv :session-id (repeatedly 2 #(bounded (future (a/<!! life))))) ["park" "drained"]))
          (let [seen (loop [n 0]
                       (if-let [event (a/poll! events)]
                         (if (:error event) (:type (ex-data (:error event)))
                             (recur (if (= (:type event) :copilot/permission.completed) (inc n) n)))
                         n))]
            (is (= seen 17)))
          (finally (a/close! life) (a/close! events) (a/close! hold)))))))

(deftest settled-busy-request-cannot-fail-on-an-unrelated-decision-receipt
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [entered (java.util.concurrent.CountDownLatch. 2) hold (a/chan) seen (promise)
            expiry (a/chan) armed (promise)
            opening (try-open c {:session-id "s" :permissions {:on-request (fn [_ _] (.countDown entered) hold)}
                                 :events {:on-event #(when (= "completed" (:id %)) (deliver seen true))}})
            _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
            emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
            req! #(emit! {:type "permission.requested" :data {:requestId % :permissionRequest {:kind "read"}}})
            timer a/timeout]
        (try
          (req! "a") (req! "b") (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
          (dotimes [i 16] (req! (str "queued-" i)))
          (let [blocked (promise) unblock (promise) attempted (promise) worker (promise)
                publish turn/publish! reserve @#'callbacks/reserve-busy-reply! sender rpc/send-request]
            (try
              (with-redefs [callbacks/reserve-busy-reply! (fn [& args]
                                                            (let [start (apply reserve args)]
                                                              #(let [result (start)] (deliver worker result) result)))
                            rpc/send-request (fn [conn method params & opts]
                                               (let [ch (apply sender conn method params opts)]
                                                 (when (= "excess" (:request-id params)) (deliver attempted true))
                                                 ch))
                            a/timeout (fn [^long ms] (if (and (= ms 10000) (not (realized? armed)))
                                                       (do (deliver armed true) expiry)
                                                       (timer ms)))
                            turn/publish! (fn [state event & args]
                                            (when (= "hold-sender" (:session-id event)) (bounded attempted))
                                            (when (= "hold-router" (:session-id event))
                                              (deliver blocked true) (bounded unblock))
                                            (apply publish state event args))]
                (locking (.-lifecycle c)
                  (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "park"}})
                  (req! "excess")
                  (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "hold-sender"}})
                  (emit! {:type "permission.completed" :id "completed" :data {:requestId "excess"}})
                  (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "hold-router"}})
                  (emit! {:type "permission.completed" :data {:requestId "unrelated"}})
                  (send! {:id "seen" :method "unknown" :params {}}) (is (= (:id (next!)) "seen")))
                (bounded seen) (bounded blocked) (a/close! expiry)
                (bounded (future (a/<!! (bounded worker))))
                (is (nil? @(:failure (.-registration s)))))
              (finally (deliver unblock true))))
          (finally (a/close! hold) (a/close! expiry)))))))

(deftest a-released-generation-cannot-fail-accepted-observations-on-a-decision-timeout
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [started (promise) release-result (promise) observer (promise)
            opening (try-open c {:session-id "s" :tools {:definitions [{:name "record" :handler
                                                                        (fn [_ _] (deliver started true) (bounded release-result) "ok")}]}
                                 :events {:on-event #(when (= (:id %) "completed") (deliver observer (:id %)))}})
            _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
            events (sdk/subscribe s) emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
            jobs (:jobs (get @(:sessions (.-callbacks c)) "s")) exited (promise)
            waiting (promise) deadline (a/chan) timer a/timeout]
        (try
          (emit! {:type "external_tool.requested" :id "request" :data {:requestId "r" :toolName "record" :toolCallId "c" :arguments {}}})
          (bounded started)
          (is (= (:id (bounded (future (a/<!! events)))) "request"))
          (add-watch jobs exited (fn [_ _ before after] (when (and (seq before) (empty? after)) (deliver exited true))))
          (let [closing
                (locking (.-lifecycle c)
                  (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "park"}})
                  (emit! {:type "external_tool.completed" :id "completed" :data {:requestId "r"}})
                  (send! {:id "seen" :method "unknown" :params {}}) (is (= (:id (next!)) "seen"))
                  (with-redefs [a/timeout (fn [^long ms]
                                            (if (and (= ms 10000) (= "copilot-native-request" (.getName (Thread/currentThread))))
                                              (do (deliver waiting true) deadline)
                                              (timer ms)))]
                    (deliver release-result true) (bounded waiting)
                    (let [closing (future (sdk/close!! s))]
                      (reply! (next!) {:success true})
                      (send! {:id "released" :method "unknown" :params {}}) (is (= (:id (next!)) "released"))
                      (a/close! deadline)
                      (bounded exited)
                      closing)))]
            (bounded closing)
            (let [terminal (bounded (future (a/<!! (a/into [] events))))]
              (is (= (mapv :id (butlast terminal)) ["completed"]))
              (is (= (some-> terminal peek :error ex-data :type) :session-closed))
              (when (= (:id (first terminal)) "completed") (is (= (bounded observer) "completed")))))
          (finally (a/close! deadline) (deliver release-result true) (remove-watch jobs exited)))))))

(deftest a-winning-decision-failure-owns-pending-forms-before-detach
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "s"})
            _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
            s (bounded opening) events (sdk/subscribe s)
            turn (sdk/send-and-wait! s "pending") request (next!)
            form (sdk/elicit! s {:message "Continue?" :requested-schema {:type "object"}})
            _ (is (= (:method (next!)) "session.ui.elicitation"))
            _ (send! {:method "session.event" :params {:sessionId "s" :event {:type "session.idle" :id "idle"}}})
            _ (is (= (:id (bounded (future (a/<!! events)))) "idle"))
            closing (future (sdk/close!! s)) detach (next!)
            owner (.-callbacks c) reg (get @(:sessions owner) "s")
            failure (ex-info "Decision timed out" {:type :decision-admission-timeout})
            claimed (promise) resume (promise) finish @#'callbacks/finish-failure!]
        (is (= (mapv :method [request detach]) ["session.sendAndWait" "session.detach"]))
        (with-redefs-fn {#'callbacks/finish-failure! (fn [& args] (deliver claimed true) (bounded resume)
                                                       (apply finish args))}
          (fn []
            (let [failing (future (#'callbacks/fail-active-decision! owner reg (constantly true) failure))]
              (try
                (bounded claimed)
                (reply! request {:messageId "late" :aborted false :completionEventId "idle"})
                (reply! detach {:success true})
                (send! {:id "released" :method "unknown" :params {}})
                (is (= (:id (next!)) "released"))
                (deliver resume true)
                (bounded failing)
                (bounded closing)
                (let [{:keys [method params]} (next!)]
                  (is (= [method params] ["$/cancelRequest" {:id (:id request)}])))
                (doseq [[kind ch] [[:turn turn] [:subscription events] [:form form]]]
                  (is (identical? (:error (bounded (future (a/<!! ch)))) failure) kind))
                (finally (deliver resume true) (a/close! events))))))))))

(deftest a-release-before-claim-overflow-still-drains-the-event
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [observed (promise) at-claim (promise) resume (promise) fail @#'callbacks/fail-session!
            open (try-open c {:session-id "s" :events {:on-event #(deliver observed (:id %))}})
            _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
            s (bounded open) stream (sdk/subscribe s) pending (sdk/send-and-wait! s "pending") _ (next!)
            form (sdk/elicit! s {:message "m" :requested-schema {:type "object"}}) _ (next!)]
        (try
          (with-redefs [callbacks/claim-limit 0
                        callbacks/fail-session! (fn [& args] (deliver at-claim true) (bounded resume) (apply fail args))]
            (send! {:method "session.event" :params {:sessionId "s" :event {:type "permission.requested" :id "request"
                                                                            :data {:requestId "r" :permissionRequest {:kind "read"}}}}})
            (bounded at-claim)
            (let [closing (future (sdk/close!! s))]
              (reply! (next!) {:success true})
              (is (= (-> (bounded (future (a/<!! form))) :error ex-data :type) :session-closed))
              (deliver resume true)
              (bounded closing)
              (let [items (bounded (future (a/<!! (a/into [] stream))))]
                (is (= (mapv #(or (:id %) (-> % :error ex-data :type)) items) ["request" :session-closed]))
                (when (= "request" (:id (first items))) (is (= (bounded observed) "request"))))
              (is (= (-> (bounded (future (a/<!! pending))) :error ex-data :type) :session-closed))))
          (finally (deliver resume true) (a/close! stream)))))))

(deftest a-reserved-form-observes-failure-before-an-unacknowledged-detach
  (let [trace (atom (constantly nil)) started (promise) headers (a/promise-chan)]
    (with-runtime {:observability {:on-get-trace-context #(@trace)}}
      (fn [c _ next! reply!]
        (let [open (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
              s (bounded open) events (sdk/subscribe s) cause (ex-info "Timed out" {:type :decision-admission-timeout})]
          (reset! trace #(do (deliver started true) headers))
          (try
            (let [turn (sdk/send-and-wait! s "traced") _ (bounded started)
                  form (sdk/elicit! s {:message "Continue?" :requested-schema {:type "object"}})
                  close (future (sdk/close!! s)) detach (next!)]
              (is (= (:method detach) "session.detach"))
              (let [finishing (promise) resume (promise) advancing (promise)
                    finish @#'callbacks/finish-failure! check! @#'client/check-reservation!]
                (with-redefs [callbacks/finish-failure! (fn [& args] (deliver finishing true) (bounded resume) (apply finish args))
                              client/check-reservation! (fn [session] (deliver advancing true) (check! session))]
                  (let [failing (future (callbacks/fail! (.-callbacks c) (.-registration s) cause))]
                    (try (bounded finishing)
                         (a/close! headers)
                         (bounded advancing)
                         (finally (deliver resume true)))
                    (bounded failing))))
              (doseq [ch [turn events form]]
                (is (identical? (:error (bounded (future (a/<!! ch)))) cause)))
              (reply! detach {:success true})
              (bounded close))
            (finally (a/close! headers) (a/close! events))))))))

(deftest a-positive-release-withdraws-an-unanswered-turn-before-publications-drain
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
            stream (sdk/subscribe s) turn (sdk/send-and-wait! s "go") request (next!)
            emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})]
        (emit! {:id "idle" :type "session.idle"})
        (is (= (:id (bounded (future (a/<!! stream)))) "idle"))
        (let [closing
              (locking (.-lifecycle c)
                (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
                (emit! {:id "queued" :type "session.info"})
                (let [closing (future (sdk/close!! s)) detach (next!)]
                  (is (= (:method detach) "session.detach"))
                  (reply! detach {:success true})
                  (reply! request {:messageId "late" :aborted false :completionEventId "idle"})
                  (is (= (-> (bounded (future (a/<!! turn))) :error ex-data :type) :session-closed))
                  closing))]
          (bounded closing)
          (is (= (mapv #(or (:id %) (-> % :error ex-data :type)) (bounded (future (a/<!! (a/into [] stream)))))
                 ["queued" :session-closed])))))))

(deftest release-drains-an-already-answered-turn-without-an-observer
  (doseq [order [:event-first :reply-first :runtime-error :late-event]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s"})
              s (bounded opening) events (sdk/subscribe s)
              turn (sdk/send-and-wait! s "go") request (next!)
              idle! #(send! {:method "session.event" :params {:sessionId "s" :event {:id "idle" :type "session.idle"}}})
              closing
              (locking (.-lifecycle c)
                (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
                (when (= order :event-first) (idle!))
                (if (= order :runtime-error)
                  (send! {:id (:id request) :error {:code -32001 :message "Rejected" :data {:completionEventId "idle"}}})
                  (reply! request {:aborted false :messageId "m" :completionEventId "idle"
                                   :response {:type "assistant.message" :data {:content "done"}}}))
                (when (#{:reply-first :runtime-error} order) (idle!))
                (let [close (future (sdk/close!! s)) detach (next!)]
                  (is (= (:method detach) "session.detach"))
                  (reply! detach {:success true})
                  (when (= order :late-event) (idle!))
                  (send! {:id "read" :method "unknown" :params {}})
                  (is (= (:id (next!)) "read"))
                  close))]
          (bounded closing)
          (let [{:keys [ok error]} (bounded (future (a/<!! turn)))
                published (bounded (future (a/<!! (a/into [] events))))]
            (is (= (mapv :id (butlast published)) (if (= order :late-event) [] ["idle"])))
            (case order
              :late-event (is (= (-> error ex-data :type) :session-closed))
              :runtime-error (is (= (ex-message error) "Rejected"))
              (is (= (get-in ok [:data :content]) "done")))))))))

(deftest acknowledged-release-ends-observer-bound-turns-before-draining-other-publications
  (doseq [eof? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [entered (promise) resume (promise) events (atom [])
              open (try-open c {:session-id "s" :events {:on-event (fn [{:keys [id]}]
                                                                     (swap! events conj id)
                                                                     (when (= id "idle") (deliver entered true) (bounded resume)))}})
              _ (reply! (next!) {:sessionId "s"}) s (bounded open) stream (sdk/subscribe s)
              turn (sdk/send-and-wait! s "pending") request (next!)
              emit! (fn [id type] (send! {:method "session.event" :params {:sessionId "s" :event {:id id :type type}}}))]
          (try
            (emit! "idle" "session.idle")
            (reply! request {:messageId "m" :aborted false :completionEventId "idle"})
            (bounded entered)
            (let [closing (locking (.-lifecycle c)
                            (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
                            (emit! "queued" "session.info")
                            (let [closing (future (sdk/close!! s)) detach (next!)]
                              (is (= (:method detach) "session.detach"))
                              (reply! detach {:success true})
                              (send! {:id "acknowledged" :method "unknown" :params {}})
                              (is (= (:id (next!)) "acknowledged"))
                              (when eof? (send!))
                              (deliver resume true)
                              (is (= (-> (bounded (future (a/<!! turn))) :error ex-data :type) :session-closed))
                              closing))]
              (bounded closing)
              (let [observations (bounded (future (a/<!! (a/into [] stream))))]
                (is (= (mapv :id (butlast observations)) ["idle" "queued"]))))
            (finally (deliver resume true) (a/close! stream))))))))

(deftest eof-that-claims-pending-requests-does-not-also-fail-the-registration
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
            s (bounded opening) events (sdk/subscribe s) form (sdk/elicit! s {:message "m" :requested-schema {:type "object"}})
            _ (next!) entered (promise) resume (promise) withdraw rpc/withdraw-requests!]
        (try
          (with-redefs [callbacks/claim-limit 0
                        rpc/withdraw-requests! (fn [& args] (deliver entered true) (bounded resume) (apply withdraw args))]
            (send! {:method "session.event" :params {:sessionId "s" :event {:id "seen" :type "permission.requested"
                                                                            :data {:requestId "r" :permissionRequest {:kind "read"}}}}})
            (bounded entered)
            (send!)
            (is (= (ex-message (:error (bounded (future (a/<!! form))))) "Connection closed by remote"))
            (deliver resume true)
            (is (= (mapv #(or (:id %) (-> % :error ex-data :type)) (bounded (future (a/<!! (a/into [] events)))))
                   ["seen" :connection-closed])))
          (finally (deliver resume true) (a/close! events)))))))

(deftest connection-end-during-release-has-one-public-cause
  (with-runtime {}
    (fn [c _ next! reply!]
      (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
            s (bounded opening) events (sdk/subscribe s)
            form (sdk/elicit! s {:message "m" :requested-schema {:type "object"}}) _ (next!)
            at-release (promise) claim (promise) retire reverse/retire-generation!]
        (try
          (with-redefs [reverse/retire-generation! (fn [& args] (apply retire args) (deliver at-release true) (bounded claim))]
            (let [closing (future (sdk/close!! s)) detach (next!)]
              (reply! detach {:success true})
              (bounded at-release)
              (rpc/end-input! (.-connection c) {:code -32000 :message "Runtime ended"})
              (is (= (ex-message (:error (bounded (future (a/<!! form))))) "Runtime ended"))
              (deliver claim true)
              (bounded closing)
              (let [cause (:error (bounded (future (a/<!! events))))]
                (is (= [(:type (ex-data cause)) (ex-message cause)] [:connection-closed "Runtime ended"])))))
          (finally (deliver claim true) (a/close! events)))))))

(deftest connection-end-after-release-claims-its-requests-cannot-preserve-an-observer-bound-turn
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [entered (promise) resume-observer (promise) claimed (promise) finish-release (promise)
            writing (promise) fail-write (promise) eof-thread (promise) eof-closed (promise)
            opening (try-open c {:session-id "s" :events {:on-event (fn [_] (deliver entered true) (bounded resume-observer))}})
            _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
            s (bounded opening) events (sdk/subscribe s) turns (.-turns s)
            form (sdk/elicit! s {:message "pending" :requested-schema {:type "object"}}) _ (next!)
            turn (sdk/send-and-wait! s "go") request (next!)
            withdraw rpc/withdraw-requests! write! @#'rpc/write-message! close-turns turn/close!]
        (try
          (send! {:method "session.event" :params {:sessionId "s" :event {:id "idle" :type "session.idle"}}})
          (reply! request {:messageId "m" :aborted false :completionEventId "idle"})
          (bounded entered)
          (is (= "idle" (:id (bounded (future (a/<!! events))))))
          (with-redefs [rpc/write-message! (fn [ch msg]
                                             (if (= "ping" (:method msg))
                                               (do (deliver writing true) (bounded fail-write) (throw (java.io.IOException. "test writer failure")))
                                               (write! ch msg)))
                        rpc/withdraw-requests! (fn [& args]
                                                 (let [owned (apply withdraw args)]
                                                   (when (seq owned) (deliver claimed true) (bounded finish-release))
                                                   owned))
                        turn/close! (fn [state cause & args]
                                      (if (and (identical? state turns) (= :connection-closed (:type (ex-data cause))))
                                        (do (deliver eof-thread (Thread/currentThread))
                                            (try (apply close-turns state cause args)
                                                 (finally (deliver eof-closed true))))
                                        (apply close-turns state cause args)))]
            (let [closing (future (sdk/close!! s)) detach (next!) ping (sdk/ping c)]
              (bounded writing)
              (reply! detach {:success true})
              (bounded claimed)
              (deliver fail-write true)
              ;; Let EOF either finish or reach the turn monitor held by release.
              (let [^Thread thread (bounded eof-thread) deadline (+ (System/nanoTime) 3000000000)]
                (loop []
                  (when-not (or (realized? eof-closed) (= Thread$State/BLOCKED (.getState thread)))
                    (when (> (System/nanoTime) deadline) (throw (ex-info "EOF did not reach turn retirement" {})))
                    (Thread/yield)
                    (recur))))
              (deliver finish-release true)
              (bounded closing)
              (deliver resume-observer true)
              (doseq [ch [events form turn]]
                (is (= (-> (bounded (future (a/<!! ch))) :error ex-data :type) :session-closed)))
              (is (= (get-in (ex-data (:error (bounded (future (a/<!! ping))))) [:error :data :code]) "write_failed"))))
          (finally (deliver finish-release true) (deliver resume-observer true) (deliver fail-write true) (a/close! events)))))))

(deftest confirmed-deletion-retires-unsent-trace-reservations-before-its-rpc-reply
  (let [trace (atom (constantly nil)) entered (promise) stopped (promise) headers (promise)]
    (with-runtime {:observability {:on-get-trace-context #(@trace)}}
      (fn [c send! next! reply!]
        (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s"})
              s (bounded opening) deadline (a/chan)]
          (reset! trace #(do (deliver entered true) (try @headers (catch InterruptedException _ (deliver stopped true) {}))))
          (try
            (let [turn (with-redefs [turn/start-deadline (fn [_] [deadline (constantly nil)])]
                         (sdk/send-and-wait! s "not submitted"))
                  _ (bounded entered) read (sdk/history s)
                  deleting (future (sdk/delete-session!! c "s")) listing (next!)
                  _ (reply! listing {:sessions [{:sessionId "s"}]}) request (next!)]
              (is (= (mapv :method [listing request]) ["session.list" "session.delete"]))
              (send! {:method "session.lifecycle" :params {:type "session.deleted" :sessionId "s"}})
              (send! {:id "read" :method "unknown" :params {}})
              (is (= (:id (next!)) "read"))
              (a/close! deadline)
              (is (= (-> (bounded (future (a/<!! turn))) :error ex-data :type) :session-closed))
              (is (= (-> (bounded (future (a/<!! read))) :error ex-data :type) :session-closed))
              (is (true? (bounded stopped)))
              (reply! request {:success true})
              (bounded deleting))
            (finally (deliver headers {}) (a/close! deadline))))))))

(deftest wire-preparation-preserves-the-first-session-or-connection-failure
  (doseq [failure-first? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
              s (bounded opening) events (sdk/subscribe s)
              form #(sdk/elicit! s {:message % :requested-schema {:type "object"}})
              submitted (form "submitted") _ (next!) probe (sdk/ping c "EOF fence") _ (next!)
              preparing (promise) resume (promise) convert util/clj->wire]
          (try
            (with-redefs [callbacks/claim-limit 0
                          util/clj->wire (fn [v] (when (= "preparing" (get v "message"))
                                                   (deliver preparing true) (bounded resume))
                                           (convert v))]
              (let [unsent (future (form "preparing"))]
                (bounded preparing)
                (when failure-first?
                  (send! {:method "session.event" :params {:sessionId "s" :event {:type "permission.requested"
                                                                                  :data {:requestId "r" :permissionRequest {:kind "read"}}}}}))
                (let [failure (when failure-first? (:error (bounded (future (a/<!! events)))))]
                  (send!)
                  (is (= (ex-message (:error (bounded (future (a/<!! probe))))) "Connection closed by remote"))
                  (deliver resume true)
                  (doseq [ch [submitted (bounded unsent)]]
                    (let [e (:error (bounded (future (a/<!! ch))))]
                      (if failure-first? (is (identical? e failure))
                          (is (= (ex-message e) "Connection closed by remote"))))))))
            (finally (deliver resume true) (a/close! events))))))))

(deftest retiring-a-session-at-eof-preserves-the-cause-for-its-reservations
  (doseq [close-before-publication? [false true]
          :let [trace (atom (constantly nil)) entered (promise) headers (a/chan)]]
    (with-runtime {:observability {:on-get-trace-context #(@trace)}}
      (fn [c send! next! reply!]
        (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
              s (bounded opening) stream (sdk/subscribe s)]
          (reset! trace #(do (deliver entered true) headers))
          (try
            (let [turn (sdk/send-and-wait! s "traced") _ (bounded entered)
                  form (sdk/elicit! s {:message "queued" :requested-schema {:type "object"}})
                  failures (locking (.-lifecycle c)
                             (when close-before-publication?
                               (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
                               (send! {:method "session.event" :params {:sessionId "s" :event {:type "session.info" :id "last"}}}))
                             (locking c
                               (send!)
                               (.join ^Thread (:read-thread (.-connection c)) 3000)
                               (is (not (.isAlive ^Thread (:read-thread (.-connection c)))))
                               (if close-before-publication? (sdk/close!! s) (#'client/finish-input! c)))
                             (mapv #(:error (bounded (future (a/<!! %)))) [turn form]))
                  events (bounded (future (a/<!! (a/into [] stream))))
                  closed (:error (last events))]
              (is (= (mapv :id (butlast events)) (if close-before-publication? ["last"] [])))
              (is (= (-> closed ex-data :type) :connection-closed))
              (doseq [e failures]
                (if close-before-publication?
                  (is (= [(ex-data e) (ex-message e)] [(ex-data closed) (ex-message closed)]))
                  (is (identical? e closed))))
              (is (= (-> (bounded (future (a/<!! (sdk/send-and-wait! s "new")))) :error ex-data :type) :session-closed))
              (is (= (try (sdk/subscribe s) (catch Exception e (-> e ex-data :type))) :session-closed)))
            (finally (a/close! headers) (a/close! stream))))))))

(deftest preparation-after-the-first-handle-check-keeps-the-selected-session-failure
  (doseq [kind [:form :turn]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
              s (bounded opening) life (sdk/lifecycle-events c) events (sdk/subscribe s)
              preparing (promise) resume (promise)
              helper (if (= kind :form) #'util/opaque-json->wire #'options/send-params)
              original @helper]
          (try
            (with-redefs-fn {helper (fn [& args] (deliver preparing true) (bounded resume) (apply original args))}
              (fn []
                (let [calling (future (if (= kind :form)
                                        (sdk/elicit! s {:message "m" :requested-schema {:type "object"}})
                                        (sdk/send-and-wait! s "t")))]
                  (bounded preparing)
                  (let [cause (ex-info "Callback failure" {:type :callback-overflow})]
                    (callbacks/fail! (.-callbacks c) (.-registration s) cause)
                    (is (identical? (:error (bounded (future (a/<!! events)))) cause))
                    (send!)
                    (is (= (-> (bounded (future (a/<!! life))) :error ex-data :type) :connection-closed))
                    (deliver resume true)
                    (is (identical? (:error (bounded (future (a/<!! (bounded calling))))) cause))))))
            (finally (deliver resume true) (a/close! events) (a/close! life))))))))

(deftest a-session-abort-retires-manual-recovery-even-when-no-request-was-replayed
  (doseq [[label event expected] [[:root {:type "abort"} :not-pending]
                                  [:child {:type "abort" :agentId "child"} {:success true}]
                                  [:history {:type "abort" :checkpoint true} {:success true}]]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [seen (promise) opening (future (sdk/resume-session!! c "s" {:continue-pending-work? true
                                                                          :events {:on-event #(deliver seen (:type %))}}))
              _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
              preparing (promise) resume (promise) convert util/clj->wire]
          (with-redefs [util/clj->wire (fn [v]
                                         (when (= "restored" (:request-id v)) (deliver preparing true) (bounded resume))
                                         (convert v))]
            (try
              (let [result (future (let [out (try (sdk/respond!! s :permission {:request-id "restored" :result {:kind :reject}})
                                                  (catch Exception e (:type (ex-data e))))]
                                     [out (sdk/ping c "done")]))]
                (bounded preparing)
                (send! {:method "session.event" :params {:sessionId "s" :event event}})
                (is (= (bounded seen) :copilot/abort))
                (deliver resume true)
                (let [frame (next!) manual? (= (:method frame) "session.permissions.handlePendingPermissionRequest")
                      ping (if manual? (do (reply! frame {:success true}) (next!)) frame)]
                  (is (= manual? (not= label :root)) label)
                  (reply! ping {:message "done"})
                  (let [[out pong] (bounded result)]
                    (is (= out expected) label)
                    (is (= (sdk/await!! pong) {:message "done"}))))
                (when (= label :root)
                  (let [events (sdk/subscribe s)]
                    (send! {:method "session.event" :params {:sessionId "s" :event {:type "permission.requested"
                                                                                    :data {:requestId "new" :permissionRequest {:kind "read"}}}}})
                    (bounded (future (a/<!! events))) (a/close! events)
                    (let [new (future (sdk/respond!! s :permission {:request-id "new" :result {:kind :reject}}))
                          frame (next!)]
                      (is (= (get-in frame [:params :requestId]) "new"))
                      (reply! frame {:success true})
                      (is (= (bounded new) {:success true}))))))
              (finally (deliver resume true)))))))))

(deftest a-running-tool-submits-its-first-result-only-if-still-live
  (doseq [checkpoint? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [started (promise) release (promise) boundary (promise)
              opening (try-open c {:cloud {} :tools {:definitions [{:name "record" :handler (fn [_ _]
                                                                                              (deliver started true)
                                                                                              (bounded release) "ok")}]}})
              create (next!)
              emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
              _ (emit! {:type "external_tool.requested" :data {:requestId "r" :toolCallId "call" :toolName "record" :arguments {}}})
              _ (reply! create {:sessionId "s"})
              s (bounded opening) _ (bounded started)
              jobs (:jobs (get @(:sessions (.-callbacks c)) "s")) drained (promise) alts!! a/alts!!]
          (add-watch jobs drained (fn [_ _ _ current] (when (empty? current) (deliver drained true))))
          (try
            (let [wire (locking (.-lifecycle c)
                         (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
                         (emit! {:type "external_tool.completed" :checkpoint checkpoint? :data {:requestId "r"}})
                         (send! {:id "read" :method "unknown" :params {}}) (is (= (:id (next!)) "read"))
                         (let [wire (future (let [frame (next!)] (deliver boundary :sent) frame))]
                           (with-redefs [a/alts!! (fn [ports & args]
                                                    (when (identical? (first ports) (:closed (.-registration s)))
                                                      (deliver boundary :waiting))
                                                    (apply alts!! ports args))]
                             (deliver release true)
                             (is (= (bounded boundary) :waiting)))
                           wire))
                  ping (future (bounded drained) (sdk/await!! (sdk/ping c "done")))
                  frame (bounded wire)]
              (is (= (:method frame) (if checkpoint? "session.tools.handlePendingToolCall" "ping")))
              (if (= (:method frame) "ping")
                (reply! frame {:message "done"})
                (do (is (= (get-in frame [:params :result]) "ok"))
                    (reply! frame {:success true})
                    (reply! (next!) {:message "done"})))
              (is (= (bounded ping) {:message "done"})))
            (finally (deliver release true) (remove-watch jobs drained))))))))

(deftest queued-tool-and-fallback-cannot-pass-a-received-checkpoint
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [calls (atom []) queued (promise) done (promise) start-boundary (promise) retry-boundary (promise)
            opening (try-open c {:session-id "s" :tools {:definitions [{:name "record"
                                                                        :handler (fn [_ ctx]
                                                                                   (let [id (:request-id ctx)]
                                                                                     (swap! calls conj id)
                                                                                     (when (= id "queued") (deliver start-boundary :started))
                                                                                     "ok"))}]}
                                 :events {:on-event #(case (:id %) "queued" (deliver queued true)
                                                           "done" (deliver done true) nil)}})
            _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
            emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
            request! #(emit! {:type "external_tool.requested" :id %
                              :data {:requestId % :toolCallId % :toolName "record" :arguments {}}})
            settle! #(emit! {:type "external_tool.completed" :checkpoint true :data {:requestId %}})
            reader! #(do (send! {:id "seen" :method "unknown" :params {}}) (is (= (:id (next!)) "seen")))
            alts!! a/alts!! offer! a/offer!
            signal-wait (fn [ports & args]
                          (when (identical? (:closed (.-registration s)) (first ports))
                            (if (realized? start-boundary) (deliver retry-boundary :waiting) (deliver start-boundary :waiting)))
                          (apply alts!! ports args))
            outbound (fn [ch frame]
                       (when (and (identical? ch (:outgoing-ch (.-connection c))) (= "fallback" (get-in frame [:params :requestId]))
                                  (get-in frame [:params :error]))
                         (deliver retry-boundary :sent))
                       (offer! ch frame))
            _ (request! "fallback") original (next!)
            _ (request! "release") release (next!)]
        (request! "queued") (bounded queued)
        (locking (.-lifecycle c)
          (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
          (settle! "queued") (settle! "fallback") (reader!)
          (with-redefs [a/alts!! signal-wait a/offer! outbound]
            (reply! release {:success true}) (is (= (bounded start-boundary) :waiting))
            (send! {:id (:id original) :error {:code -32602 :message "invalid result"}})
            (is (= (bounded retry-boundary) :waiting))))
        (emit! {:type "session.info" :id "done"}) (bounded done)
        (let [jobs (:jobs (get @(:sessions (.-callbacks c)) "s")) empty-jobs (promise)]
          (add-watch jobs empty-jobs (fn [_ _ _ jobs] (when (empty? jobs) (deliver empty-jobs true))))
          (try
            (let [drained (future (when (seq @jobs) (bounded empty-jobs)) (sdk/await!! (sdk/ping c "done")))
                  sent (loop [extra []]
                         (let [frame (next!)]
                           (if (= "ping" (:method frame))
                             (do (reply! frame {:message "done"}) extra)
                             (do (reply! frame {:success true}) (recur (conj extra (get-in frame [:params :requestId])))))))]
              (is (= (bounded drained) {:message "done"}))
              (is (empty? sent))
              (is (= @calls ["fallback" "release"])))
            (finally (remove-watch jobs empty-jobs))))))))

(deftest a-routed-decision-and-queued-observations-do-not-delay-the-next-decision
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s"})
            s (bounded opening) life (sdk/lifecycle-events c) boundary (promise) alts!! a/alts!!
            emit! #(send! {:method "session.event" :params {:sessionId "s" :event %}})
            mark! #(send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId %}})]
        (try
          (emit! {:type "permission.completed" :data {:requestId "other"}})
          (mark! "caught-up")
          (is (= (:session-id (bounded (future (a/<!! life)))) "caught-up"))
          (let [[wire answer]
                (locking (.-lifecycle c)
                  (mark! "park")
                  (emit! {:type "session.info" :id "unrelated"})
                  (emit! {:type "permission.requested" :checkpoint true
                          :data {:requestId "history" :permissionRequest {:kind "read"}}})
                  (emit! {:type "abort" :checkpoint true})
                  (send! {:id "read" :method "unknown" :params {}})
                  (is (= (:id (next!)) "read"))
                  (let [wire (future (let [frame (next!)] (deliver boundary (:method frame)) frame))
                        answer (future
                                 (with-redefs [a/alts!! (fn [ports & args]
                                                          (when (identical? (first ports) (:closed (.-registration s)))
                                                            (deliver boundary :waiting))
                                                          (apply alts!! ports args))]
                                   (sdk/respond!! s :permission {:request-id "restored" :result {:kind :reject}})))]
                    (is (= (bounded boundary) "session.permissions.handlePendingPermissionRequest"))
                    [wire answer]))]
            (reply! (bounded wire) {:success true})
            (is (= (bounded answer) {:success true})))
          (finally (a/close! life)))))))

(deftest a-manual-reply-cannot-preempt-an-already-received-automatic-request
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [s-call (future (sdk/resume-session!! c "s"
                                                 {:continue-pending-work? true
                                                  :permissions {:on-request (fn [_ _] {:kind :reject :feedback "automatic"})}}))
            _ (reply! (next!) {:sessionId "s"})
            s (bounded s-call) waiting (promise)
            signal-wait (fn [f] (fn [& args] (deliver waiting true) (apply f args)))
            request {:type "permission.requested" :data {:requestId "p" :permissionRequest {:kind "read"}}}
            manual (locking (.-lifecycle c)
                     (send! {:method "session.lifecycle" :params {:sessionId "fence" :type "session.updated"}})
                     (send! {:method "session.event" :params {:sessionId "s" :event request}})
                     (let [history (sdk/history s)]
                       (reply! (next!) {:events [request]})
                       (let [id (get-in (first (sdk/await!! history)) [:data :request-id])
                             answer (future (with-redefs [a/alts!! (signal-wait a/alts!!)
                                                          rpc/await-bounded-response!! (signal-wait rpc/await-bounded-response!!)]
                                              (try (sdk/respond!! s :permission {:request-id id :result {:kind :reject}})
                                                   (catch Exception e (:type (ex-data e))))))]
                         (bounded waiting) answer)))]
        (let [frame (next!)]
          (reply! frame {:success true})
          (is (= (get-in frame [:params :result]) {:kind "reject" :feedback "automatic"})))
        (is (= (bounded manual) :not-pending))))))

(defn- next-release! [next! reply! method id]
  (when (= method "session.delete")
    (let [listing (next!)]
      (is (= (:method listing) "session.list"))
      (reply! listing {:sessions [{:sessionId id}]})))
  (next!))

(deftest cloud-create-cannot-replay-across-failure-or-reuse
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [emit! #(send! {:method "session.event" :params {:sessionId %1 :event {:id %2 :type "session.info"}}})
            fence! #(do (send! {:id "reader-fence" :method "unknown" :params {}})
                        (is (= (:id (next!)) "reader-fence")))
            failed (try-open c {:cloud {}}) failed-request (next!)
            seen (atom []) done (promise)
            observer (fn [{:keys [id]}] (swap! seen conj id) (when (= id "end") (deliver done @seen)))]
        (emit! "next" "before-second-create")
        (fence!)
        (let [later-first (promise)
              later (try-open c {:cloud {} :events {:on-event #(deliver later-first (:id %))}})
              later-request (next!)
              opening (try-open c {:cloud {} :events {:on-event observer}})
              request (next!)]
          (send! {:id (:id failed-request) :error {:code -32000 :message "Create refused"}})
          (is (= (ex-message (bounded failed)) "Create refused"))
          (reply! later-request {:sessionId "next"})
          (emit! "next" "live")
          (is (= (bounded later-first) "live"))
          (is (= (sdk/session-id (bounded later)) "next"))
          (emit! "reused" "early")
          (reply! request {:sessionId "reused"})
          (emit! "reused" "end")
          (is (= (bounded done) ["early" "end"]))
          (let [s (bounded opening) closing (future (sdk/close!! s))]
            (reply! (next!) {:success true})
            (bounded closing))
          (emit! "reused" "detached")
          (fence!)
          (let [fresh (promise)
                reopened (try-open c {:cloud {} :events {:on-event #(deliver fresh (:id %))}})]
            (reply! (next!) {:sessionId "reused"})
            (emit! "reused" "replacement")
            (is (= (bounded fresh) "replacement"))
            (is (= (sdk/session-id (bounded reopened)) "reused"))))))))

(deftest anonymous-create-only-claims-events-after-its-outbound-offer
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [first-open (try-open c {:cloud {}}) first-create (next!)
            preparing (promise) resume (promise) observed (promise)
            offer! a/offer!
            emit! #(send! {:method "session.event" :params {:sessionId "assigned" :event {:type "session.info" :id %}}})]
        (try
          (with-redefs [a/offer! (fn [ch msg]
                                   (when (and (identical? ch (:outgoing-ch (.-connection c)))
                                              (= "new-open" (get-in msg [:params "clientName"])))
                                     (deliver preparing true) (bounded resume))
                                   (offer! ch msg))]
            (let [opening (try-open c {:cloud {} :client-name "new-open" :events {:on-event #(deliver observed (:id %))}})]
              (bounded preparing)
              (emit! "before-offer")
              (send! {:id "fence" :method "unknown" :params {}})
              (is (= (:id (next!)) "fence"))
              (deliver resume true)
              (let [create (next!)]
                (emit! "after-offer")
                (reply! create {:sessionId "assigned"})
                (is (= (sdk/session-id (bounded opening)) "assigned"))
                (is (= (bounded observed) "after-offer")))))
          (finally (deliver resume true) (reply! first-create {:sessionId "other"}) (bounded first-open)))))))

(deftest a-rejected-cloud-registration-cannot-consume-another-openers-batch
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [existing (try-open c {:session-id "Alias"}) _ (reply! (next!) {:sessionId "Alias"}) s (bounded existing)
            colliding (try-open c {:cloud {}}) first-create (next!)
            seen (promise)
            eligible (try-open c {:cloud {} :events {:on-event #(deliver seen (:id %))}}) second-create (next!)
            emit! #(send! {:method "session.event" :params {:sessionId "alias" :event {:id % :type "session.info"}}})]
        (emit! "early")
        (reply! first-create {:sessionId "alias"})
        (is (= (:type (ex-data (bounded colliding))) :session-id-case-conflict))
        (let [closing (future (sdk/close!! s))]
          (reply! (next!) {:success true})
          (bounded closing))
        (reply! second-create {:sessionId "alias"})
        (emit! "live")
        (is (= (sdk/session-id (bounded eligible)) "alias"))
        (is (= (bounded seen) "early"))))))

(deftest explicit-reservation-cuts-an-in-progress-held-event-before-id-reuse
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [seen (promise) anonymous (try-open c {:cloud {} :events {:on-event #(deliver seen (:id %))}})
            create (next!) ^Thread reader (:read-thread (.-connection c))
            hold @#'client/make-room discard @#'client/discard-held-events!
            entered (promise) opener (promise) discarded (promise)
            emit! #(send! {:method "session.event" :params {:sessionId "reuse" :event {:id % :type "session.info"}}})]
        (with-redefs-fn
          {#'client/make-room
           (fn [state bytes]
             ;; Freeze the insert after its ownership check until the explicit
             ;; opener either finishes its cut or waits for this reader's monitor.
             (when (identical? reader (Thread/currentThread))
               (deliver entered true)
               (let [^Thread t (bounded opener)
                     mx (java.lang.management.ManagementFactory/getThreadMXBean)
                     until (+ (System/nanoTime) 5000000000)]
                 (loop []
                   (when-not (or (realized? discarded)
                                 (= (.getId reader) (.getLockOwnerId (.getThreadInfo mx (.getId t)))))
                     (when (> (System/nanoTime) until) (throw (ex-info "Explicit opener did not reach its cut" {})))
                     (Thread/yield) (recur)))))
             (hold state bytes))
           #'client/discard-held-events!
           (fn [held id]
             (deliver opener (Thread/currentThread))
             (let [result (discard held id)] (deliver discarded true) result))}
          (fn []
            (emit! "prior")
            (bounded entered)
            (let [opening (try-open c {:session-id "reuse"})]
              (reply! (next!) {:sessionId "reuse"})
              (let [s (bounded opening) closing (future (sdk/close!! s))]
                (reply! (next!) {:success true})
                (bounded closing)))))
        (reply! create {:sessionId "reuse"})
        (emit! "live")
        (is (= (sdk/session-id (bounded anonymous)) "reuse"))
        (is (= (bounded seen) "live"))))))

(deftest unregistered-delete-cuts-held-events-at-the-first-confirmation
  (doseq [boundary [:external :refused :ack :lifecycle-then-ack]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [seen (atom []) delivered (promise)
              deleting (when-not (= :external boundary)
                         (future (try (sdk/delete-session!! c "id") (catch Exception e e))))
              delete (when deleting (next-release! next! reply! "session.delete" "id"))
              opening (try-open c {:cloud {} :events {:on-event (fn [{:keys [id]}]
                                                                  (swap! seen conj id)
                                                                  (when (= "end" id) (deliver delivered @seen)))}})
              create (next!)
              event! #(send! {:method "session.event" :params {:sessionId "id" :event {:type "session.info" :id %}}})
              deleted! #(send! {:method "session.lifecycle" :params {:type "session.deleted" :sessionId "id"}})
              received (promise) resume (promise) result! rpc/response-result!]
          (try
            (with-redefs [rpc/response-result! (fn [method response]
                                                 (when (= method "session.delete") (deliver received true) (bounded resume))
                                                 (result! method response))]
              (event! "old")
              (event! "during")
              (when (#{:external :lifecycle-then-ack} boundary) (deleted!))
              (when (= :lifecycle-then-ack boundary) (event! "replacement"))
              (when delete
                (reply! delete {:success (not= boundary :refused)})
                (bounded received)
                (reply! delete {:success true}))
              (event! "after")
              (reply! create {:sessionId "id"})
              (event! "end")
              (let [result (bounded opening)]
                (is (client/session? result) boundary)
                (when (client/session? result)
                  (is (= (bounded delivered)
                         (case boundary :refused ["old" "during" "after" "end"]
                               :lifecycle-then-ack ["replacement" "after" "end"]
                               ["after" "end"])) boundary)))
              (deliver resume true)
              (when deleting (is (= (instance? Throwable (bounded deleting)) (= :refused boundary)))))
            (finally (deliver resume true))))))))

(deftest written-delete-timeout-and-lifecycle-confirmation-have-one-winner
  (doseq [open? [false true] first-confirmed? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [id "saved" expired (a/chan) timer a/timeout withdrawn (promise)
              state (:state-atom (.-connection c)) events (sdk/lifecycle-events c)
              notify! (fn [] (send! {:method "session.lifecycle" :params {:type "session.deleted" :sessionId id}})
                        (is (= (:session-id (bounded (future (a/<!! events)))) id)))]
          (when open?
            (let [opening (try-open c {:session-id id})]
              (reply! (next!) {:sessionId id}) (bounded opening)))
          (let [[deleting request]
                (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) expired (timer ms)))]
                  (let [d (future (try (sdk/delete-session!! c id) (catch Exception e e)))]
                    [d (next-release! next! reply! "session.delete" id)]))
                pending [:connection :pending-requests (:id request)]]
            (add-watch state withdrawn (fn [_ _ old new]
                                         (when (and (get-in old pending) (not (get-in new pending))) (deliver withdrawn true))))
            (try
              (when first-confirmed? (notify!))
              (locking c
                (a/close! expired)
                (bounded withdrawn)
                (when-not first-confirmed? (notify!)))
              (is (= (ex-message (bounded deleting)) "Request timeout"))
              (is (= (boolean (get-in @state [:connection :late-responses (:id request)]))
                     (and open? (not first-confirmed?))))
              (let [opening (try-open c {:session-id id})
                    ping (future (bounded opening) (sdk/await!! (sdk/ping c "fence")))
                    first-frame (next!)
                    fence (if (= (:method first-frame) "session.create")
                            (do (reply! first-frame {:sessionId id}) (next!)) first-frame)
                    result (bounded opening)]
                (is (= (:method fence) "ping"))
                (reply! fence {:message "done"}) (bounded ping)
                (is (= (if (client/session? result) :opened (-> result ex-data :type))
                       (if first-confirmed? :opened :session-cleanup-unconfirmed))))
              (finally (a/close! events) (remove-watch state withdrawn)))))))))

(deftest a-delete-dequeued-before-its-ownership-check-is-still-unwritten
  (doseq [open? [false true]]
    (with-runtime {}
      (fn [c _ next! reply!]
        (let [id "saved" checked (promise) resume (promise)
              expired (a/chan) clock a/timeout send-request rpc/send-request
              s (when open? (let [opening (try-open c {:session-id id})]
                              (reply! (next!) {:sessionId id}) (bounded opening)))]
          (try
            (with-redefs [rpc/send-request
                          (fn [conn method params & [opts]]
                            (send-request conn method params
                                          (if (= method "session.delete")
                                            (update opts :on-write (fn [check!] (fn [& args] (deliver checked true)
                                                                                  (bounded resume) (apply check! args))))
                                            opts)))
                          a/timeout (fn [^long ms] (if (and (= ms 10000) (not (realized? resume))) expired (clock ms)))]
              (let [deleting (future (try (sdk/delete-session!! c id) (catch Exception e e)))]
                (reply! (next!) {:sessions [{:sessionId id}]})
                (bounded checked)
                (a/close! expired)
                (let [failure (bounded deleting)]
                  (is (= (ex-message failure) "Request timeout"))
                  (is (true? (::rpc/unwritten (meta (:error (ex-data failure)))))))
                (deliver resume true)
                (let [ping (sdk/ping c "done") frame (next!)
                      fence (if (= (:method frame) "session.delete") (do (reply! frame {:success true}) (next!)) frame)]
                  (is (= (:method frame) "ping"))
                  (reply! fence {:message "done"}) (sdk/await!! ping))
                (is (if s (= :open (:status @(.-lifecycle s))) (not (contains? @(.-sessions c) id))))))
            (finally (deliver resume true) (a/close! expired))))))))

(deftest a-claimed-deletion-cannot-be-fenced-while-its-callbacks-retire
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [id "saved" credential (fn [_] {:kind :cancelled})
            opening (try-open c {:session-id id :github {:token-provider credential}})
            _ (reply! (next!) {:sessionId id}) s (bounded opening)
            expired (a/chan) timer a/timeout taken (promise) resume (promise) withdrawn (promise)
            state (:state-atom (.-connection c)) retire reverse/retire-generation!
            matching (promise) publish-result (promise) match! @#'turn/match-completion!
            deleter (promise) ^Thread reader (:read-thread (.-connection c))]
        (try
          (with-redefs [turn/match-completion! (fn [& args] (deliver matching true) (bounded publish-result) (apply match! args))
                        reverse/retire-generation! (fn [& args] (deliver taken true) (bounded resume) (apply retire args))]
            (let [result (sdk/send-and-wait! s "confirmed") send-rpc (next!)
                  _ (send! {:method "session.event" :params {:sessionId id :event {:id "idle" :type "session.idle"}}})
                  _ (reply! send-rpc {:messageId "m" :aborted false :completionEventId "idle"
                                      :response {:type "assistant.message" :data {:content "done"}}})
                  _ (bounded matching)
                  [deleting request] (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) expired (timer ms)))]
                                       (let [d (future (deliver deleter (Thread/currentThread))
                                                       (try (sdk/delete-session!! c id) (catch Exception e e)))]
                                         [d (next-release! next! reply! "session.delete" id)]))
                  pending [:connection :pending-requests (:id request)]]
              (add-watch state withdrawn (fn [_ _ old new]
                                           (when (and (get-in old pending) (not (get-in new pending))) (deliver withdrawn true))))
              (send! {:method "session.lifecycle" :params {:sessionId id :type "session.deleted"}})
              (bounded taken)
              (a/close! expired)
              (bounded withdrawn)
              (let [^Thread worker (bounded deleter) mx (java.lang.management.ManagementFactory/getThreadMXBean)
                    until (+ (System/nanoTime) 3000000000)]
                ;; Let the caller either finish cleanup or join the reader's claimed confirmation.
                (loop []
                  (when-not (or (realized? deleting) (= (.getId reader) (some-> (.getThreadInfo mx (.getId worker)) .getLockOwnerId)))
                    (when (> (System/nanoTime) until) (throw (ex-info "Delete caller did not reach cleanup" {})))
                    (Thread/yield) (recur))))
              (deliver resume true)
              (is (= (ex-message (bounded deleting)) "Request timeout"))
              (send! {:id "confirmed" :method "unknown" :params {}})
              (is (= (:id (next!)) "confirmed"))
              (is (= :closed (:status @(.-lifecycle s))))
              (is (not (contains? @(.-lifecycle s) ::client/delete-boundary)))
              (deliver publish-result true)
              (is (= "done" (get-in (bounded (future (a/<!! result))) [:ok :data :content])))
              (let [reopening (try-open c {:session-id id})
                    ping (future (bounded reopening) (sdk/await!! (sdk/ping c)))
                    frame (next!)
                    fence (if (= (:method frame) "session.create") (do (reply! frame {:sessionId id}) (next!)) frame)]
                (is (= (:method fence) "ping"))
                (reply! fence {:message "done"}) (bounded ping)
                (is (client/session? (bounded reopening))))))
          (finally (deliver resume true) (deliver publish-result true) (remove-watch state withdrawn)))))))

(deftest connection-failure-cannot-reopen-a-concurrently-confirmed-delete
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [id "saved" opening (try-open c {:session-id id}) _ (reply! (next!) {:sessionId id}) s (bounded opening)
            deleting (future (try (sdk/delete-session!! c id) (catch Exception e e)))
            _ (next-release! next! reply! "session.delete" id)
            conn (.-connection c) checked (promise) confirmed (promise)
            released? @#'client/released? retire reverse/retire-generation!
            deleted! @#'client/deleted! reading (promise)]
        (with-redefs [client/released? (fn [session]
                                         (let [answer (released? session)]
                                           (when-not answer
                                             (deliver checked true) (bounded confirmed))
                                           answer))
                      reverse/retire-generation! (fn [& args] (apply retire args) (deliver confirmed true))
                      client/deleted! (fn [& args] (deliver reading true) (bounded checked) (apply deleted! args))]
          (locking (.-lifecycle c)
                ;; Keep EOF retirement behind the router; the delete caller owns
                ;; the handle's cleanup even if the connection ends first.
            (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "park"}})
            (send! {:method "session.lifecycle" :params {:type "session.deleted" :sessionId id}})
            (bounded reading)
            (rpc/end-input! conn {:code -32000 :message "Runtime ended"})
            (is (= (ex-message (bounded deleting)) "Runtime ended"))
            (is (= (:status @(.-lifecycle s)) :closed))
            (is (not (identical? s (get @(.-sessions c) id))))
            (is (some? (:closed @(.-turns s))))))))))

(deftest a-queued-delete-cannot-write-after-the-id-was-reassigned
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [deleting (future (try (sdk/delete-session!! c "id") (catch Exception e e)))
            list-rpc (next!)
            opening (try-open c {:cloud {}}) create (next!)
            blocked (promise) release (promise) queued (promise)
            write! @#'rpc/write-message! offer! a/offer!]
        (try
          (with-redefs-fn
            {#'rpc/write-message! (fn [ch msg]
                                    (when (= (:method msg) "ping") (deliver blocked true) (bounded release))
                                    (write! ch msg))
             #'a/offer! (fn [ch msg]
                          (let [ok (offer! ch msg)]
                            (when (and ok (= (:method msg) "session.delete")) (deliver queued true))
                            ok))}
            (fn []
              (let [ping (sdk/ping c)]
                (bounded blocked)
                (reply! list-rpc {:sessions [{:sessionId "id"}]})
                (bounded queued)
                (send! {:method "session.lifecycle" :params {:type "session.deleted" :sessionId "id"}})
                (reply! create {:sessionId "id"})
                (is (= (sdk/session-id (bounded opening)) "id"))
                (deliver release true)
                (reply! (next!) {:message "ready"})
                (sdk/await!! ping)
                (let [fence (sdk/ping c)
                      frame (next!)
                      last-ping (if (= "session.delete" (:method frame)) (do (reply! frame {:success true}) (next!)) frame)]
                  (is (= (:method frame) "ping"))
                  (reply! last-ping {:message "done"})
                  (sdk/await!! fence)
                  (is (= (get-in (ex-data (bounded deleting)) [:error :data :code])
                         "session_ownership_changed"))))))
          (finally (deliver release true)))))))

(deftest unregistered-delete-cannot-overtake-an-unidentified-create
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [id (str (random-uuid))
            live (try-open c {:session-id "known"}) _ (reply! (next!) {:sessionId "known"}) _ (bounded live)
            opening (try-open c {:cloud {}}) create (next!)
            lifecycle (sdk/lifecycle-events c)]
        (try
          ;; A prior anonymous allocation does not block a known live owner.
          (let [deleting (future (sdk/delete-session!! c "known")) delete (next-release! next! reply! "session.delete" "known")]
            (is (= "known" (get-in delete [:params :sessionId])))
            (reply! delete {:success true}) (bounded deleting))
          (send! {:method "session.lifecycle" :params {:sessionId id :type "session.created"}})
          (is (= id (:session-id (bounded (future (a/<!! lifecycle))))))
          (let [failure (bounded (future (try (sdk/delete-session!! c id) (catch Exception e e))))]
            (is (= (get-in (ex-data failure) [:error :data :code]) "session_allocation_unresolved")))
          ;; Neither an inventory lookup nor an ID reservation precedes refusal.
          (let [ping (sdk/ping c) frame (next!)]
            (is (= "ping" (:method frame)))
            (reply! frame {:message "alive"})
            (sdk/await!! ping))
          (reply! create {:sessionId id})
          (is (= (sdk/session-id (bounded opening)) id))
          ;; A confirmed allocation lifts the restriction on other saved IDs.
          (let [retry (future (sdk/delete-session!! c "other")) delete (next-release! next! reply! "session.delete" "other")]
            (is (= "other" (get-in delete [:params :sessionId])))
            (reply! delete {:success true}) (is (nil? (bounded retry))))
          (finally (a/close! lifecycle)))))))

(deftest an-unwritten-delete-cannot-displace-an-acknowledged-cloud-create
  (doseq [phase [:listed :case-variant :unlisted :refused :failed-open]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [id "edcb4321-9876-4123-abcd-fedcba987654"
              assigned (if (= phase :case-variant) (.toUpperCase id java.util.Locale/ROOT) id)
              seen (promise) events (sdk/lifecycle-events c)
              deleting (future (try (sdk/delete-session!! c id) (catch Exception e e)))
              listing (next!) opening (try-open c {:cloud {} :events {:on-event #(deliver seen (:id %))}})
              create (next!) refused (promise) finish (promise) result! rpc/response-result!]
          (try
            (send! {:method "session.lifecycle" :params {:type "session.created" :sessionId assigned}})
            (is (= assigned (:session-id (bounded (future (a/<!! events))))))
            (send! {:method "session.event" :params {:sessionId assigned :event {:type "session.info" :id "early"}}})
            (with-redefs [rpc/response-result! (fn [method response]
                                                 (when (and (= phase :refused) (= method "session.delete"))
                                                   (deliver refused true) (bounded finish))
                                                 (result! method response))]
              (when (= phase :refused)
                (reply! listing {:sessions [{:sessionId id}]})
                (bounded refused))
              (if (= :failed-open phase)
                (do (send! {:id (:id create) :error {:code -32000 :message "Create refused"}})
                    (is (= (ex-message (bounded opening)) "Create refused")))
                (do (reply! create {:sessionId assigned})
                    (let [s (bounded opening)]
                      (is (client/session? s) phase)
                      (when (client/session? s) (is (= (bounded seen) "early"))))))
              (deliver finish true)
              (when-not (= phase :refused)
                (reply! listing {:sessions (if (= phase :unlisted) [] [{:sessionId id}])}))
              ;; Fence the outcome even on the broken implementation, which
              ;; sends a delete after rejecting the acknowledged allocation.
              (let [ping (future @deleting (sdk/await!! (sdk/ping c)))
                    frame (next!)
                    fence (if (= "session.delete" (:method frame))
                            (do (reply! frame {:success true}) (next!)) frame)]
                (is (= (:method frame) (if (= phase :failed-open) "session.delete" "ping")) phase)
                (reply! fence {:message "done"})
                (bounded ping)
                (is (= (instance? Throwable (bounded deleting)) (not= phase :failed-open)) phase)))
            (finally (a/close! events) (deliver finish true))))))))

(deftest graceful-close-joins-delete-after-the-id-is-free
  (doseq [release [:lifecycle :ack :refusal]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [opening (try-open c {:session-id "other"}) _ (reply! (next!) {:sessionId "other"}) _ (bounded opening)
              deleting (future (try (sdk/delete-session!! c "saved") (catch Exception e e)))
              delete (next-release! next! reply! "session.delete" "saved")
              parked (promise) finish (promise) result! rpc/response-result!]
          (try
            (with-redefs [rpc/response-result! (fn [method response]
                                                 (when (= "session.delete" method) (deliver parked true) (bounded finish))
                                                 (result! method response))]
              (if (= release :lifecycle)
                (do (send! {:method "session.lifecycle" :params {:sessionId "saved" :type "session.deleted"}})
                    (send! {:id "read" :method "unknown" :params {}})
                    (is (= (:id (next!)) "read")))
                (do (reply! delete {:success (= release :ack) :error "refused"}) (bounded parked)))
              (let [closing (future (try (sdk/close!! c) (catch Exception e e)))
                    detach (next!)]
                (is (= [(:method detach) (get-in detach [:params :sessionId])] ["session.detach" "other"]))
                (reply! detach {:success true})
                (when (= release :lifecycle) (reply! delete {:success false :error "refused"}))
                (deliver finish true)
                (let [shutdown (next!)]
                  (is (= (:method shutdown) "runtime.shutdown"))
                  (reply! shutdown {}))
                (let [delete-error (bounded deleting) close-error (bounded closing)]
                  (if (= :ack release)
                    (is (= [delete-error close-error] [nil nil]))
                    (is (= (mapv (comp :reason ex-data) [delete-error close-error]) ["refused" "refused"]))))))
            (finally (deliver finish true))))))))

(deftest timeout-withdraws-an-unwritten-create-or-delete
  (doseq [kind [:create :named :resume :detach :saved-delete :live-delete]]
    (with-runtime {}
      (fn [c _ next! reply!]
        (let [delete? (#{:saved-delete :live-delete} kind)
              method (case kind (:create :named) "session.create" :resume "session.resume"
                           :detach "session.detach" "session.delete")
              s (when (#{:detach :live-delete} kind)
                  (let [opening (try-open c {:session-id "saved"})]
                    (reply! (next!) {:sessionId "saved"}) (bounded opening)))
              deleting (when delete? (future (try (sdk/delete-session!! c "saved") (catch Exception e e))))
              listing (when deleting (next!))
              blocked (promise) write-now (promise) queued (promise) popped (promise) finish (promise)
              expire (a/chan) timer a/timeout offer! a/offer! write! @#'rpc/write-message! cancel! rpc/cancel-request!]
          (try
            (with-redefs-fn
              {#'rpc/write-message! (fn [ch msg]
                                      (when (= "ping" (:method msg)) (deliver blocked true) (bounded write-now))
                                      (write! ch msg))
               #'a/offer! (fn [ch msg]
                            (let [ok (offer! ch msg)]
                              (when (and ok (= method (:method msg))) (deliver queued true)) ok))
               #'rpc/cancel-request! (fn [conn reply]
                                       (let [removed (cancel! conn reply)]
                                         (when removed (deliver popped true) (bounded finish)) removed))}
              (fn []
                (let [ping (sdk/ping c)
                      _ (bounded blocked)
                      operation (with-redefs [a/timeout (fn [^long ms] (if (= ms (if (or delete? s) 10000 60000)) expire (timer ms)))]
                                  (let [op (case kind
                                             :create (try-open c {:cloud {}})
                                             :named (try-open c {:session-id "saved"})
                                             :resume (future (try (sdk/resume-session!! c "saved" {}) (catch Exception e e)))
                                             :detach (future (try (sdk/close!! s) (catch Exception e e)))
                                             (do (reply! listing {:sessions [{:sessionId "saved"}]}) deleting))]
                                    (bounded queued)
                                    (a/close! expire)
                                    (bounded popped)
                                    op))]
                  ;; The owner cannot finish timeout cleanup until after the wire fence.
                  (deliver write-now true)
                  (reply! (next!) {:message "ready"})
                  (sdk/await!! ping)
                  (let [fence (sdk/ping c) frame (next!)
                        last-ping (if (= method (:method frame)) (do (reply! frame {}) (next!)) frame)]
                    (is (= (:method frame) "ping") method)
                    (reply! last-ping {:message "fence"})
                    (sdk/await!! fence))
                  (deliver finish true)
                  (is (= (ex-message (bounded operation)) "Request timeout"))
                  (is (identical? s (get @(.-sessions c) "saved")))
                  (when (= kind :create)
                    (let [retry (try-open c {:cloud {}})]
                      (reply! (next!) {:sessionId "retry"})
                      (is (= (sdk/session-id (bounded retry)) "retry"))))
                  (when s
                    (let [abort (sdk/abort! s) request (next!)]
                      (is (= [(:method request) (:params request)] ["session.abort" {:sessionId "saved"}]))
                      (reply! request {:success true})
                      (is (nil? (sdk/await!! abort))))))))
            (finally (deliver write-now true) (deliver finish true) (a/close! expire))))))))

(deftest interactive-form-waits-for-an-answer-or-its-session
  (with-runtime {}
    (fn [c _ next! reply!]
      (let [opening (try-open c {:session-id "form"})
            _ (reply! (next!) {:sessionId "form" :capabilities {:ui {:elicitation true}}})
            session (bounded opening)
            ask! #(sdk/elicit! session {:message "Continue?" :requested-schema {:type "object" :properties {}}})
            expire (a/chan)
            timer a/timeout
            answer (with-redefs [a/timeout (fn [^long ms] (if (= 60000 ms) expire (timer ms)))] (ask!))
            first-form (next!)
            result! #(first (a/alts!! [% (a/timeout 3000)] :priority true))]
        (is (= (:method first-form) "session.ui.elicitation"))
        (a/close! expire)
        (let [ping (sdk/ping c)]
          (reply! (next!) {:message "still open"})
          (is (= (sdk/await!! ping) {:message "still open"})))
        (reply! first-form {:action "accept" :content {"answer" true}})
        (is (= (result! answer) {:ok {:action :accept :content {"answer" true}}}))
        (let [pending (ask!)
              form (next!)
              close (future (sdk/close!! session))
              detach (next!)]
          (is (= (:method detach) "session.detach"))
          (is (= (-> (ask!) result! :error ex-data) {:type :session-closed :session-id "form"}))
          (reply! detach {:success true})
          (is (nil? (bounded close)))
          (is (= (-> pending result! :error ex-data :type) :session-closed))
          (is (= (-> (ask!) result! :error ex-data) {:type :session-closed :session-id "form"}))
          (is (not (contains? (get-in @(:state-atom (.-connection c)) [:connection :pending-requests]) (:id form)))))
        (let [replacement (try-open c {:session-id "form"})
              _ (reply! (next!) {:sessionId "form"})
              open (bounded replacement)
              unsupported (result! (sdk/elicit! open {:message "Continue?" :requested-schema {:type "object"}}))]
          (is (= (ex-message (:error unsupported)) "Host does not support elicitation")))))))

(deftest outbound-forms-are-bounded-until-the-runtime-answers
  (with-runtime {}
    (fn [c _ next! reply!]
      (let [open (try-open c {:session-id "forms"})
            _ (reply! (next!) {:sessionId "forms" :capabilities {:ui {:elicitation true}}})
            s (bounded open)
            other-open (try-open c {:session-id "other"})
            _ (reply! (next!) {:sessionId "other" :capabilities {:ui {:elicitation true}}})
            other (bounded other-open)
            ask! (fn [session message] (sdk/elicit! session {:message message :requested-schema {:type "object"}}))
            forms (mapv (fn [n]
                          (let [result (ask! s (str n)) frame (next!)]
                            (a/close! result)
                            frame)) (range 64))
            excess (ask! other "excess")
            barrier (sdk/ping c "barrier")
            next-frame (next!)]
        (is (= (:method next-frame) "ping"))
        (when (= (:method next-frame) "ping")
          (reply! next-frame {:message "alive"})
          (is (= (sdk/await!! barrier) {:message "alive"}))
          (is (= (-> (bounded (future (a/<!! excess))) :error ex-data)
                 {:type :elicitation-capacity :limit 64}))
          (reply! (first forms) {:action "decline"})
          ;; A reply before the next request is the admission boundary, not
          ;; the application reading its closed channel.
          (let [ping (sdk/ping c)]
            (reply! (next!) {})
            (sdk/await!! ping))
          (let [replacement (ask! other "replacement") frame (next!)]
            (is (= [(:method frame) (get-in frame [:params :message])] ["session.ui.elicitation" "replacement"]))
            ;; Keep all 64 slots occupied until detach retires the first session's forms.
            (let [close (future (sdk/close!! s))
                  release (next!)]
              (is (= (:method release) "session.detach"))
              (reply! release {:success true})
              (is (nil? (bounded close))))
            (let [after (ask! other "after detach") after-frame (next!)]
              (is (= (get-in after-frame [:params :message]) "after detach"))
              (reply! after-frame {:action "decline"})
              (is (= (sdk/await!! after) {:action :decline})))
            (reply! frame {:action "cancel"})
            (is (= (sdk/await!! replacement) {:action :cancel}))))))))

(deftest submitted-form-keeps-the-first-settlement
  (doseq [queued? [false true] failure-first? [true false] error? [false true]]
    (let [gate (a/chan)
          headers (atom {})]
      (with-runtime (if queued? {:observability {:on-get-trace-context #(deref headers)}} {})
        (fn [c send! next! reply!]
          (let [open! (fn [id]
                        (let [opening (try-open c {:session-id id})]
                          (reply! (next!) {:sessionId id :capabilities {:ui {:elicitation true}}})
                          (bounded opening)))
                ask! #(sdk/elicit! % {:message "Name?" :requested-schema {:type "object"}})
                s (open! "form")
                other (ask! (open! "other"))
                other-form (next!)
                _ (reset! headers gate)
                head (when queued? (sdk/send! s "trace head"))
                submit @#'client/submit
                submitted (promise) resume (promise)
                cause (ex-info "Callback registration failed" {:type :fixture-failure})
                fail! #(callbacks/fail! (.-callbacks c) (.-registration s) cause)
                wire-error {:code -32602 :message "Invalid form" :data {:field "name"}}]
            (with-redefs [client/submit (fn [& args]
                                          (let [request (apply submit args)]
                                            (deliver submitted true)
                                            (bounded resume)
                                            request))]
              (try
                (let [asking (future (ask! s))]
                  (bounded submitted)
                  (when head
                    (a/close! gate)
                    (let [sent (next!)]
                      (is (= (:method sent) "session.send"))
                      (reply! sent {:messageId "sent"})
                      (is (= (bounded (future (sdk/await!! head))) "sent"))))
                  (let [form (next!)]
                    (is (= (:method form) "session.ui.elicitation"))
                    (when failure-first? (fail!))
                    (if error?
                      (send! {:id (:id form) :error wire-error})
                      (reply! form {:action "accept" :content {"name" "Ada"}}))
                    ;; The reader has processed the answer, but elicit! cannot yet take it.
                    (send! {:id "fence" :method "unknown" :params {}})
                    (is (= (:id (next!)) "fence"))
                    (when-not failure-first? (fail!))
                    (deliver resume true)
                    (reply! other-form {:action "decline"})
                    (is (= (bounded (future (sdk/await!! other))) {:action :decline}))
                    (let [result (bounded (future (a/<!! (bounded asking))))]
                      (cond
                        failure-first? (is (identical? (:error result) cause))
                        error? (is (= (ex-data (:error result)) {:method (:method form) :error wire-error}))
                        :else (is (= result {:ok {:action :accept :content {"name" "Ada"}}}))))))
                (finally (deliver resume true) (a/close! gate))))))))))

(deftest concurrent-tools-can-await-forms-without-occupying-their-responders
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [handle (promise)
            ask! (fn [label]
                   (sdk/elicit! @handle {:message label :requested-schema {:type "object" :properties {}}}))
            opening (try-open c {:session-id "s"
                                 :tools {:definitions [{:name "ask" :parameters {:type "object"}
                                                        :handler (fn [{:keys [label]} _]
                                                                   (name (:action (sdk/await!! (ask! label)))))}]}
                                 :interaction {:on-elicitation-request
                                               (fn [{:keys [message]}]
                                                 {:action (if (= message "A") :accept :decline)})}})
            _ (reply! (next!) {:sessionId "s" :capabilities {:ui {:elicitation true}}})
            _ (deliver handle (bounded opening))
            emit! (fn [type data] (send! {:method "session.event"
                                          :params {:sessionId "s" :event {:type type :data data}}}))]
        (doseq [label ["A" "B"]]
          (emit! "external_tool.requested" {:requestId label :toolCallId label :toolName "ask"
                                            :arguments {:label label}}))
        (let [forms (into {} (for [_ (range 2) :let [form (next!)]]
                               (do (is (= (:method form) "session.ui.elicitation"))
                                   [(get-in form [:params :message]) form])))]
          (is (= (set (keys forms)) #{"A" "B"}))
          (doseq [label (keys forms)]
            (emit! "elicitation.requested" {:requestId label :message label :requestedSchema {:type "object"}}))
          (doseq [frame (vec (repeatedly 2 next!))]
            (is (= (:method frame) "session.ui.handlePendingElicitation"))
            (reply! frame {:success true})
            (reply! (forms (get-in frame [:params :requestId])) (get-in frame [:params :result])))
          (let [results (into {} (for [_ (range 2) :let [frame (next!)]]
                                   (do (is (= (:method frame) "session.tools.handlePendingToolCall"))
                                       (reply! frame {:success true})
                                       ((juxt :requestId :result) (:params frame)))))]
            (is (= results {"A" "accept" "B" "decline"}))))))))

(deftest nested-form-admission-follows-the-client-not-the-session-id
  (with-runtime {}
    (fn [other _ other-next! other-reply!]
      (let [open! (fn [client next! reply! id opts]
                    (let [opening (try-open client (assoc opts :session-id id))]
                      (reply! (next!) {:sessionId id :capabilities {:ui {:elicitation true}}})
                      (bounded opening)))
            independent (open! other other-next! other-reply! "s" {})]
        (with-runtime {}
          (fn [c send! next! reply!]
            (let [source (promise)
                  local (open! c next! reply! "local" {})
                  attempts (promise)
                  form {:message "Nested" :requested-schema {:type "object" :properties {}}}
                  handler (fn [_]
                            (deliver attempts (mapv #(sdk/elicit! % form) [@source local independent]))
                            {:action :decline})]
              (deliver source (open! c next! reply! "s" {:interaction {:on-elicitation-request handler}}))
              (send! {:method "session.event" :params {:sessionId "s" :event {:type "elicitation.requested"
                                                                              :data {:requestId "outer" :message "Outer"}}}})
              (let [[same sibling remote] (bounded attempts)
                    call (other-next!)
                    disposition (fn [ch] (-> (bounded (future (a/<!! ch))) :error ex-data))]
                (is (= [(:method call) (get-in call [:params :sessionId])] ["session.ui.elicitation" "s"]))
                (other-reply! call {:action "accept"})
                (is (= (bounded (future (sdk/await!! remote))) {:action :accept}))
                (is (= (disposition same) {:type :recursive-elicitation :session-id "s"}))
                (let [answer (next!)]
                  (is (= [(:method answer) (get-in answer [:params :result])]
                         ["session.ui.handlePendingElicitation" {:action "decline"}]))
                  (reply! answer {:success true}))
                (is (= (disposition sibling) {:type :recursive-elicitation :session-id "local"}))))))))))

(deftest invalid-hook-result-does-not-disconnect-an-unrelated-request
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [value (atom (java.time.Duration/ofMillis 10))
            opening (try-open c {:session-id "hook"
                                 :hooks {:handlers {:on-post-tool-use
                                                    (fn [_ _] {:modified-result {:text-result-for-llm "ok" :result-type :success
                                                                                 :elapsed @value}})}}})
            _ (reply! (next!) {:sessionId "hook"})
            _ (bounded opening)
            ping (sdk/ping c)
            ping-frame (next!)
            hook! #(send! {:id "hook-result" :method "hooks.invoke"
                           :params {:sessionId "hook" :hookType "postToolUse" :input {}}})]
        (hook!)
        (let [frame (a/thread (try (next!) (catch Exception e e)))
              [outcome port] (a/alts!! [ping frame (a/timeout 4000)])]
          (is (= (:id outcome) "hook-result"))
          (is (= (get-in outcome [:error :code]) -32603))
          (when (= port frame)
            (reply! ping-frame {:message "pong"})
            (is (= (bounded (future (sdk/await!! ping))) {:message "pong"}))
            (reset! value {:lab/count 2 :model/count 7})
            (hook!)
            (is (= (get-in (next!) [:result :output :modifiedResult])
                   {:textResultForLlm "ok" :resultType "success" :elapsed {:lab/count 2 :model/count 7}}))))))))

(deftest a-parked-shell-cancellation-listener-cannot-block-the-rpc-reader
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [armed (promise)
            entered (promise)
            release (promise)
            handler (fn [_ {:keys [cancel-ch]}]
                      (a/take! cancel-ch (fn [_] (deliver entered (Thread/currentThread)) (bounded release)))
                      (deliver armed true)
                      (a/chan))
            opening (try-open c {:session-id "shell" :interaction {:on-shell-authentication-request handler}})
            _ (reply! (next!) {:sessionId "shell"})
            _ (bounded opening)
            ping (sdk/ping c)
            frame (next!)
            reader (:read-thread (.-connection c))]
        (try
          (send! {:id "prompt" :method "shellAuthentication.request"
                  :params {:sessionId "shell" :requestId "r" :program "demo" :inputKind "confirm" :prompt "Continue?"}})
          (bounded armed)
          (send! {:id "cancel" :method "shellAuthentication.cancel" :params {:sessionId "shell" :requestId "r"}})
          (let [listener (bounded entered)]
            (is (not (identical? listener reader)))
            (reply! frame {:message "pong"})
            (is (= (first (a/alts!! [ping (a/timeout 1500)])) {:ok {:message "pong"}})))
          (finally (deliver release true)))))))

(deftest an-observer-can-close-itself-after-eof-without-interrupting-its-caller
  (doseq [closer [:handoff :session :client]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [entered (promise) proceed (promise) ended (promise) close-result (promise) hold (promise)
              handle (promise)
              observer (fn [_]
                         (deliver entered true)
                         @proceed
                         (let [close! #(let [result (sdk/close!! @handle)]
                                         (deliver close-result [result (.isInterrupted (Thread/currentThread))]))]
                           (if (= closer :handoff) (future (close!)) (close!))
                           (deliver ended (try @hold (catch InterruptedException _ :interrupted)))))
              opening (try-open c {:session-id "observer" :events {:on-event observer}})
              _ (reply! (next!) {:sessionId "observer"})
              _ (deliver handle (bounded opening))
              life (sdk/lifecycle-events c)
              deadline (a/chan) clock a/timeout]
          (try
            (send! {:method "session.event" :params {:sessionId "observer" :event {:type "session.info" :id "one"}}})
            (bounded entered)
            (with-redefs [a/timeout (fn [^long ms] (if (= ms 500) deadline (clock ms)))]
              (send!)
              (is (= (-> (bounded (future (a/<!! life))) :error ex-data :type) :connection-closed))
              (deliver proceed true)
              (is (= (bounded close-result) [nil false]))
              (case closer
                :session (is (nil? (sdk/close!! @handle)))
                :client (is (nil? (sdk/force-close!! c)))
                :handoff nil)
              (deliver hold :not-interrupted)
              (is (= (bounded ended) :interrupted)))
            (finally (deliver proceed true) (deliver hold :released) (a/close! deadline) (a/close! life))))))))

(deftest a-finished-eof-drain-does-not-interrupt-the-self-closed-callback
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [handle (promise) release (promise) stopped (promise) entered (promise) proceed (promise)
            self-closed (promise) timer-fired (promise)
            open (try-open c {:session-id "timer" :events
                              {:on-event (fn [_]
                                           (deliver entered true)
                                           @proceed
                                           (deliver self-closed (sdk/close!! @handle))
                                           (deliver stopped (try @release (catch InterruptedException _ :interrupted))))}})
            _ (reply! (next!) {:sessionId "timer"})
            _ (deliver handle (bounded open))
            life (sdk/lifecycle-events c)
            deadline (a/chan) timeout a/timeout take! a/take!]
        (try
          (send! {:method "session.event" :params {:sessionId "timer" :event {:type "session.info" :id "one"}}})
          (bounded entered)
          (with-redefs [a/timeout (fn [^long ms] (if (= ms 500) deadline (timeout ms)))
                        a/take! (fn [port f & args]
                                  (apply take! port (if (identical? port deadline)
                                                      (fn [v] (try (f v) (finally (deliver timer-fired true)))) f) args))]
            (send!)
            (is (= (-> (bounded (future (a/<!! life))) :error ex-data :type) :connection-closed))
            (deliver proceed true)
            (is (nil? (bounded self-closed)))
            (a/close! deadline)
            (bounded timer-fired)
            ;; The timer callback has returned, so any requested interrupt has been submitted.
            (is (zero? (.getTaskCount ^java.util.concurrent.ThreadPoolExecutor (:interrupts (.-callbacks c)))))
            (deliver release :finished)
            (is (= (bounded stopped) :finished)))
          (finally (deliver proceed true) (deliver release :finished) (a/close! life) (a/close! deadline)))))))

(deftest repeated-close-preserves-ordinary-drain-and-cancels-only-a-self-detached-observer
  (doseq [self? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [handle (promise) entered (promise) stopped (promise) detached (promise) hold (promise) queued (promise)
              observer (fn [{:keys [id]}]
                         (if (= id "queued")
                           (deliver queued id)
                           (do (deliver entered true)
                               (when self? (deliver detached (sdk/close!! @handle)))
                               (deliver stopped (try @hold (catch InterruptedException _ :interrupted))))))
              open (try-open c {:session-id "reused" :events {:on-event observer}})
              _ (reply! (next!) {:sessionId "reused"})
              _ (deliver handle (bounded open))
              deadline (a/chan) clock a/timeout]
          (try
            (with-redefs [a/timeout (fn [^long ms] (if (= ms 500) deadline (clock ms)))]
              (send! {:method "session.event" :params {:sessionId "reused" :event {:type "session.info" :id "old"}}})
              (bounded entered)
              (when-not self? (future (deliver detached (sdk/close!! @handle))))
              (let [detach (next!)]
                (is (= (:method detach) "session.detach"))
                (send! {:method "session.event" :params {:sessionId "reused" :event {:type "session.info" :id "queued"}}})
                (reply! detach {:success true}))
              (is (nil? (bounded detached)))
              (let [seen (promise) new (try-open c {:session-id "reused" :events {:on-event #(deliver seen (:id %))}})]
                (reply! (next!) {:sessionId "reused"})
                (bounded new)
                (is (nil? (sdk/close!! @handle)))
                (deliver hold :not-interrupted)
                (is (= (bounded stopped) (if self? :interrupted :not-interrupted)))
                (when-not self? (is (= (bounded queued) "queued")))
                (send! {:method "session.event" :params {:sessionId "reused" :event {:type "session.info" :id "new"}}})
                (is (= (bounded seen) "new"))))
            (finally (deliver hold :released) (a/close! deadline))))))))

(deftest observer-stream-close-errors-do-not-mask-the-open-error-or-fail-a-shutdown-joiner
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [reading (promise) failed-close (promise) release (java.util.concurrent.CompletableFuture.)
            input (proxy [java.io.InputStream] []
                    (read ([_ _ _] (deliver reading true) (.join release) -1))
                    (close [] (deliver failed-close true) (throw (IllegalStateException. "Fixture stream close failed"))))
            observer (fn [_]
                       (with-open [ch (java.nio.channels.Channels/newChannel input)]
                         (.read ch (java.nio.ByteBuffer/allocate 1))))
            live (try-open c {:session-id "live"})
            _ (reply! (next!) {:sessionId "live"})
            _ (bounded live)
            open (try-open c {:session-id "notes" :git {:coauthor-enabled? false} :events {:on-event observer}})
            _ (reply! (next!) {:sessionId "notes"})
            patch (next!)]
        (try
          (send! {:method "session.event" :params {:sessionId "notes" :event {:type "session.info" :id "one"}}})
          (bounded reading)
          (reply! patch {:success false})
          (let [rollback (next!)
                _ (is (= [(:method rollback) (get-in rollback [:params :sessionId])] ["session.detach" "notes"]))
                closing (future (try (sdk/close!! c) (catch Exception e e)))
                live-detach (next!)]
            (is (true? (bounded failed-close)))
            (is (= [(:method live-detach) (get-in live-detach [:params :sessionId])] ["session.detach" "live"]))
            (reply! live-detach {:success true})
            (reply! rollback {:success true})
            (let [shutdown (next!) error (bounded open)]
              (is (= (:method shutdown) "runtime.shutdown"))
              (reply! shutdown {})
              (is (= (ex-message error) "Runtime rejected the session options patch"))
              (is (empty? (.getSuppressed error)))
              (.complete release true)
              (is (nil? (bounded closing)))))
          (finally (.complete release true)))))))

(deftest failed-lifecycle-owner-cancels-its-observer-after-eof-retirement
  (doseq [method ["session.detach" "session.delete" "session.options.update"]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [opening? (= method "session.options.update")
              entered (promise)
              release (promise)
              stopped (promise)
              received (promise)
              resume (promise)
              deadline (a/chan)
              timer a/timeout
              result! rpc/response-result!
              lifecycle (sdk/lifecycle-events c)]
          (try
            (with-redefs [a/timeout (fn [^long ms] (if (= ms 500) deadline (timer ms)))
                          rpc/response-result! (fn [m v]
                                                 (when (= m method) (deliver received true) (bounded resume))
                                                 (result! m v))]
              (let [opening (try-open c (cond-> {:session-id "s"
                                                 :events {:on-event (fn [_]
                                                                      (deliver entered true)
                                                                      (deliver stopped (try @release
                                                                                            (catch InterruptedException _ :interrupted))))}}
                                          opening? (assoc :git {:coauthor-enabled? false})))
                    _ (reply! (next!) {:sessionId "s"})
                    operation (if opening? opening
                                  (let [s (bounded opening)]
                                    (future (try (if (= method "session.detach") (sdk/close!! s) (sdk/delete-session!! c "s"))
                                                 (catch Exception e e)))))
                    request (next-release! next! reply! method "s")]
                (is (= (:method request) method))
                (send! {:method "session.event" :params {:sessionId "s" :event {:id "first" :type "session.info" :data {}}}})
                (bounded entered)
                (send! {:id (:id request) :error {:code -32001 :message "Refused"}})
                (bounded received)
                (send!)
                ;; Client lifecycle closes after the router has retired the handle.
                (is (= (-> (bounded (future (a/<!! lifecycle))) :error ex-data :type) :connection-closed))
                (is (= (count @(:draining (.-callbacks c))) 1))
                (deliver resume true)
                (is (= (ex-message (bounded operation)) "Refused"))
                (is (empty? (keys @(:draining (.-callbacks c)))))
                (deliver release :not-interrupted)
                (is (= (bounded stopped) :interrupted))))
            (finally (deliver resume true) (deliver release :not-interrupted) (a/close! deadline))))))))

(deftest post-ack-events-can-belong-to-a-new-open-while-old-publications-drain
  (doseq [[method boundary] [["session.detach" :ack] ["session.delete" :ack]
                             ["session.delete" :deleted-first] ["session.delete" :deleted-then-refused]]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [old-seen (promise) decisions (atom []) answered (promise) after-reply (promise)
              decide (fn [generation]
                       (fn [_ ctx] (swap! decisions conj [generation (:request-id ctx)])
                         (deliver (if (= "new-late" (:request-id ctx)) after-reply answered) true)
                         {:kind :no-result}))
              opening (try-open c {:session-id "reuse" :events {:on-event #(deliver old-seen (:id %))}
                                   :permissions {:on-request (decide :old)}})
              _ (reply! (next!) {:sessionId "reuse"})
              s (bounded opening) old-events (sdk/subscribe s)
              releasing (future (try (if (= method "session.delete") (sdk/delete-session!! c "reuse") (sdk/close!! s))
                                     (catch Exception e e)))
              release (next-release! next! reply! method "reuse")
              emit! #(send! {:method "session.event" :params {:sessionId "reuse" :event {:type "session.info" :id %}}})
              request! #(send! {:method "session.event" :params {:sessionId "reuse"
                                                                 :event {:type "permission.requested" :id %
                                                                         :data {:requestId % :permissionRequest {:kind "read"}}}}})
              fence! #(do (send! {:id "read" :method "unknown" :params {}}) (is (= (:id (next!)) "read")))
              seen (atom []) done (promise)
              observer (fn [{:keys [id]}] (swap! seen conj id) (when (= id "live") (deliver done @seen)))
              [replacement second-close]
              (locking (.-lifecycle c)
                (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "session.updated"}})
                (request! "old")
                (if (= boundary :ack)
                  (reply! release {:success true})
                  (send! {:method "session.lifecycle" :params {:sessionId "reuse" :type "session.deleted"}}))
                (fence!)
                (emit! "before-new-open")
                (fence!)
                (let [replacement (try-open c {:cloud {} :events {:on-event observer} :permissions {:on-request (decide :new)}})
                      create (next!)]
                  (send! {:method "session.lifecycle" :params {:sessionId "reuse" :type "session.created"}})
                  (request! "new-early")
                  (reply! create {:sessionId "reuse"})
                  (fence!)
                  (let [replacement (bounded replacement)
                        done (:done @(.-lifecycle s)) entered (promise) alts!! a/alts!!
                        closing (future
                                  (with-redefs [a/alts!! (fn [ports & args]
                                                           (when (identical? done (first ports)) (deliver entered :joined))
                                                           (apply alts!! ports args))]
                                    (let [result (try (sdk/close!! s) (catch Exception e (:type (ex-data e))))]
                                      (deliver entered result)
                                      result)))]
                    (is (= (bounded entered) (if (= method "session.detach") :joined :session-busy)))
                    [replacement closing])))]
          (when-not (= boundary :ack)
            (reply! release {:success (= boundary :deleted-first) :error "delete refused"}))
          (let [outcome (bounded releasing)]
            (if (= boundary :deleted-then-refused)
              (is (= (:reason (ex-data outcome)) "delete refused"))
              (is (nil? outcome))))
          (is (= (bounded second-close) (when (= method "session.delete") :session-busy)))
          (is (= (bounded old-seen) "old"))
          (is (= (-> (bounded (future (a/<!! old-events))) :id) "old"))
          (is (= (-> (bounded (future (a/<!! old-events))) :error ex-data :type) :session-closed))
          (when (not= boundary :ack) (request! "new-late"))
          (emit! "live")
          (is (client/session? replacement))
          (when (client/session? replacement)
            (is (= (sdk/session-id replacement) "reuse"))
            (is (= (bounded done) (if (= boundary :ack) ["new-early" "live"] ["new-early" "new-late" "live"])))
            (bounded answered)
            (when (not= boundary :ack) (bounded after-reply))
            (is (= (set @decisions) (cond-> #{[:new "new-early"]} (not= boundary :ack) (conj [:new "new-late"]))))))))))

(deftest graceful-close-joins-a-displaced-live-deleter-and-detaches-only-the-replacement
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "saved"}) _ (reply! (next!) {:sessionId "saved"}) _ (bounded opening)
            deleting (future (try (sdk/delete-session!! c "saved") (catch Exception e e)))
            delete (next-release! next! reply! "session.delete" "saved")
            replacement (try-open c {:cloud {}}) create (next!)]
        (send! {:method "session.lifecycle" :params {:sessionId "saved" :type "session.deleted"}})
        (reply! create {:sessionId "saved"})
        (is (= (sdk/session-id (bounded replacement)) "saved"))
        (let [closing (future (try (sdk/close!! c) (catch Exception e e))) detach (next!)]
          (is (= [(:method detach) (get-in detach [:params :sessionId])] ["session.detach" "saved"]))
          (reply! detach {:success true})
          (reply! delete {:success false :error "old delete refused"})
          (let [shutdown (next!)]
            (is (= (:method shutdown) "runtime.shutdown"))
            (reply! shutdown {}))
          (is (= (mapv (comp :reason ex-data bounded) [deleting closing]) ["old delete refused" "old delete refused"])))))))

(deftest a-deletion-notification-before-live-delete-admission-cannot-authorize-the-write
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "saved"}) _ (reply! (next!) {:sessionId "saved"})
            s (bounded opening) events (sdk/subscribe s)
            deleted! #(send! {:method "session.lifecycle" :params {:type "session.deleted" :sessionId %}})
            info! #(send! {:method "session.event" :params {:sessionId "saved" :event {:type "session.info" :id %}}})
            take-id! #(:id (bounded (future (a/<!! events))))]
        (deleted! "saved")
        (info! "external")
        (is (= (take-id!) "external"))
        (let [deleting (future (try (sdk/delete-session!! c "saved") (catch Exception e e)))
              listing (next!)]
          (deleted! "saved")
          (reply! listing {:sessions [{:sessionId "saved"}]})
          (is (= (get-in (ex-data (bounded deleting)) [:error :data :code]) "session_ownership_changed")))
        (let [deleting (future (try (sdk/delete-session!! c "saved") (catch Exception e e)))
              delete (next-release! next! reply! "session.delete" "saved")]
          (deleted! "other")
          (info! "different-id")
          (is (= (take-id!) "different-id"))
          (reply! delete {:success false :error "refused"})
          (is (= (:reason (ex-data (bounded deleting))) "refused"))
          (deleted! "saved")
          (info! "after-refusal")
          (is (= (take-id!) "after-refusal")))))))

(deftest graceful-close-preserves-a-joined-deletes-ownership-check
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "saved"}) _ (reply! (next!) {:sessionId "saved"})
            events (sdk/subscribe (bounded opening))
            outcome (fn [f] (try (f) (catch Exception e (get-in (ex-data e) [:error :data :code]))))
            deleting (future (outcome #(sdk/delete-session!! c "saved"))) listing (next!)
            closing (future (outcome #(sdk/close!! c)))]
        (is (= (-> (bounded (future (a/<!! events))) :error ex-data :type) :session-closed))
        (send! {:method "session.lifecycle" :params {:type "session.deleted" :sessionId "saved"}})
        (reply! listing {:sessions [{:sessionId "saved"}]})
        (let [frame (next!)
              shutdown (if (= (:method frame) "session.delete") (do (reply! frame {:success false}) (next!)) frame)]
          (is (= "runtime.shutdown" (:method frame)))
          (reply! shutdown {}))
        (is (= (mapv bounded [deleting closing]) ["session_ownership_changed" "session_ownership_changed"]))))))

(deftest post-ack-events-still-belong-to-a-pending-create-during-final-unregister
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "reuse"}) _ (reply! (next!) {:sessionId "reuse"})
            old (bounded opening) lifecycle (.-lifecycle old) at-cleanup (promise) release (promise)
            seen (promise) emit! #(send! {:method "session.event" :params {:sessionId "reuse" :event {:type "session.info" :id %}}})]
        (add-watch lifecycle ::drain (fn [_ _ _ new]
                                       (when (= :closed (:status new)) (deliver at-cleanup true) (bounded release))))
        (try
          (let [closing (future (sdk/close!! old)) detach (next!)
                replacement (try-open c {:cloud {} :events {:on-event #(deliver seen (:id %))}}) create (next!)]
            (reply! detach {:success true})
            (bounded at-cleanup)
            (emit! "after-ack")
            (send! {:id "read" :method "unknown" :params {}})
            (is (= "read" (:id (next!))))
            (deliver release true)
            (bounded closing)
            (reply! create {:sessionId "reuse"})
            (emit! "live")
            (is (= (sdk/session-id (bounded replacement)) "reuse"))
            (is (= (bounded seen) "after-ack")))
          (finally (deliver release true) (remove-watch lifecycle ::drain)))))))

(deftest force-close-retires-both-generations-during-publication-handoff
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [entered (promise) stopped (promise) unblock (promise) fresh-calls (atom 0)
            original (try-open c {:session-id "reuse"
                                  :events {:on-event (fn [_]
                                                       (deliver entered true)
                                                       (try (bounded unblock)
                                                            (catch InterruptedException _ (deliver stopped true))))}})
            _ (reply! (next!) {:sessionId "reuse"})
            s (bounded original) old-stream (sdk/subscribe s)
            emit! #(send! {:method "session.event" :params {:sessionId "reuse" :event {:type "session.info" :id %}}})]
        (try
          (emit! "entered")
          (bounded entered)
          (is (= (:id (bounded (future (a/<!! old-stream)))) "entered"))
          (let [closing (future (sdk/close!! s)) detach (next!)
                forced
                (locking (.-lifecycle c)
                  (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "session.updated"}})
                  (emit! "old")
                  (reply! detach {:success true})
                  (let [new (try-open c {:cloud {} :events {:on-event (fn [_] (swap! fresh-calls inc))}})
                        create (next!)]
                    (emit! "early")
                    (reply! create {:sessionId "reuse"})
                    (let [new-stream (sdk/subscribe (bounded new))
                          forced (future (sdk/force-close!! c))]
                      (is (true? (bounded stopped)))
                      (is (= (-> (bounded (future (a/<!! new-stream))) :error ex-data :type) :session-closed))
                  ;; Release the router before the force-close owner finishes
                  ;; closing the client lifecycle channel.
                      forced)))]
            (is (nil? (bounded forced)))
            (is (nil? (bounded closing)))
            (is (zero? @fresh-calls))
            (is (empty? @(.-sessions c))))
          (finally (deliver unblock true)))))))

(deftest direct-release-preserves-observations-before-but-not-after-the-ack
  (doseq [method ["session.detach" "session.delete"]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [handle (promise)
              closed (promise)
              replacement (promise)
              observed (a/chan 4)
              decisions (atom [])
              emit (fn [id] (send! {:method "session.event"
                                    :params {:sessionId "named" :event {:id id :type "permission.requested"
                                                                        :data {:requestId id :permissionRequest {:kind "read"}}}}}))
              open! (fn [handler]
                      (let [opening (try-open c {:session-id "named" :events {:on-event handler}
                                                 :permissions {:on-request (fn [_ _] (swap! decisions conj true) {:kind :no-result})}})]
                        (reply! (next!) {:sessionId "named"})
                        (bounded opening)))
              s (open! (fn [{:keys [id]}]
                         (a/offer! observed [:old id])
                         (when (= id "close")
                           (deliver closed (if (= method "session.detach")
                                             (sdk/close!! @handle) (sdk/delete-session!! c "named")))
                           (bounded replacement))))
              stream (sdk/subscribe s)
              take! #(bounded (future (a/<!! %)))]
          (deliver handle s)
          (send! {:method "session.event" :params {:sessionId "named" :event {:id "close" :type "session.info" :data {}}}})
          (let [request (next-release! next! reply! method "named")]
            (is (= method (:method request)))
            (locking (:sessions (.-callbacks c))
              (emit "before")
              (reply! request {:success true})
              (emit "after")
              (send! {:id "reader-barrier" :method "gitHubToken.getToken" :params {}})
              (is (= "reader-barrier" (:id (next!))))))
          (is (nil? (bounded closed)))
          (is (= (mapv :id [(take! stream) (take! stream)]) ["close" "before"]))
          (is (= (some-> (take! stream) :error ex-data :type) :session-closed))
          (let [fresh (open! #(a/offer! observed [:fresh (:id %)]))
                fresh-stream (sdk/subscribe fresh)]
            (send! {:method "session.event" :params {:sessionId "named" :event {:id "new" :type "session.info" :data {}}}})
            (is (= (:id (take! fresh-stream)) "new"))
            (deliver replacement true)
            (is (= (set (repeatedly 3 #(take! observed))) #{[:old "close"] [:old "before"] [:fresh "new"]}))
            (is (empty? @decisions))))))))

(deftest lifecycle-subscribers-start-at-frame-arrival
  (with-runtime {}
    (fn [c send! next! _]
      (let [earlier (sdk/lifecycle-events c)
            emit! #(send! {:method "session.lifecycle" :params {:sessionId % :type "session.updated"}})
            later (locking (.-lifecycle c)
                    (dotimes [i 67] (emit! (str i)))
                    (send! {:id "reader-fence" :method "unknown" :params {}})
                    (is (= (:id (next!)) "reader-fence"))
                    (sdk/lifecycle-events c))]
        (emit! "new")
        (is (= (bounded (future (a/<!! later))) {:type :session.updated :session-id "new"}))
        (is (= (:session-id (bounded (future (a/<!! earlier)))) "0"))
        (a/close! earlier)
        (a/close! later)))))

(deftest live-backlog-does-not-reach-later-subscriptions-or-turns
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "live"})
            _ (reply! (next!) {:sessionId "live"})
            s (bounded opening)
            earlier (sdk/subscribe s)
            lifecycle (sdk/lifecycle-events c)
            marker! #(send! {:method "session.lifecycle" :params {:sessionId % :type "session.updated"}})
            idle! #(send! {:method "session.event" :params {:sessionId "live" :event {:type "session.idle" :id %}}})
            [stream turn request]
            (locking (.-lifecycle c)
              (marker! "park")
              (dotimes [i 65] (idle! (str "prior-" i)))
              (send! {:id "reader-fence" :method "unknown" :params {}})
              (is (= (:id (next!)) "reader-fence"))
              (let [stream (sdk/subscribe s)
                    turn (sdk/send-and-wait! s "next")
                    request (next!)]
                (idle! "current")
                (marker! "drained")
                [stream turn request]))]
        (is (= (mapv :session-id (repeatedly 2 #(bounded (future (a/<!! lifecycle))))) ["park" "drained"]))
        (is (= (:id (a/poll! earlier)) "prior-0"))
        (a/close! earlier)
        (reply! request {:aborted false :messageId "m" :completionEventId "current"})
        (let [result (bounded (future (a/<!! turn)))]
          (is (= (or (some-> result :error ex-data :type) result) {:ok nil})))
        (a/close! stream)
        (let [events (bounded (future (a/<!! (a/into [] stream))))]
          (is (= (mapv #(or (:id %) (-> % :error ex-data :type)) events) ["current"])))
        (a/close! lifecycle)))))

(deftest shutdown-cannot-confirm-a-turn-from-a-skipped-publication
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [seen (atom []) open (try-open c {:session-id "s" :events {:on-event #(swap! seen conj (:id %))}})
            _ (reply! (next!) {:sessionId "s"}) s (bounded open)
            turn (sdk/send-and-wait! s "pending") request (next!)
            lifecycle (sdk/lifecycle-events c) retired (promise) resume (promise) retire @#'client/retire-session!]
        (try
          (with-redefs [client/retire-session! (fn [& args] (deliver retired true) (bounded resume) (apply retire args))]
            (let [closing
                  (locking (.-lifecycle c)
                    (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "park"}})
                    (send! {:method "session.event" :params {:sessionId "s" :event {:type "session.idle" :id "idle"}}})
                    (reply! request {:messageId "m" :aborted false :completionEventId "idle"})
                    (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "routed"}})
                    (send! {:id "read" :method "unknown" :params {}})
                    (is (= (:id (next!)) "read"))
                    (let [close (future (sdk/close!! c))] (bounded retired) close))]
              (is (= (mapv :session-id (repeatedly 2 #(bounded (future (a/<!! lifecycle))))) ["park" "routed"]))
              (deliver resume true)
              (doseq [method ["session.detach" "runtime.shutdown"]]
                (let [frame (next!)]
                  (is (= (:method frame) method))
                  (reply! frame {:success true})))
              (bounded closing)
              (is (= [(-> (bounded (future (a/<!! turn))) :error ex-data :type) @seen] [:session-closed []]))))
          (finally (deliver resume true) (a/close! lifecycle)))))))

(deftest eof-preserves-a-confirmed-turn-in-either-frame-order
  (doseq [order [:event-first :response-first :missing-event :missing-response :runtime-error :turn-timeout :drain-timeout]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [release (promise)
              opening (try-open c {:session-id "s" :events {:on-event (fn [_] @release)}})
              _ (reply! (next!) {:sessionId "s"})
              s (bounded opening)
              stream (sdk/subscribe s)
              deadline (a/chan)
              turn (with-redefs [turn/start-deadline (fn [_] [deadline (constantly nil)])]
                     (sdk/send-and-wait! s {:prompt "hello" :timeout-ms 5000}))
              request (next!)
              event! #(send! {:method "session.event" :params {:sessionId "s" :event {:type "session.idle" :id "last"}}})
              result! #(reply! request {:aborted false :messageId "m" :completionEventId "last"
                                        :response {:type "assistant.message" :data {:content "done"}}})]
          (try
            (case order
              (:event-first :turn-timeout :drain-timeout) (do (event!) (result!))
              :response-first (do (result!) (event!))
              :missing-event (result!)
              :missing-response (event!)
              :runtime-error (do (event!) (send! {:id (:id request) :error {:code -32001 :message "Rejected"
                                                                            :data {:completionEventId "last"}}})))
            (send!)
            (let [events (bounded (future (a/<!! (a/into [] stream))))]
              (is (= (mapv :id (butlast events)) (if (= order :missing-event) [] ["last"])))
              (is (= (-> events last :error ex-data :type) :connection-closed)))
            (case order
              :turn-timeout (a/close! deadline)
              :drain-timeout nil
              (deliver release true))
            (let [{:keys [ok error]} (bounded (future (a/<!! turn)))]
              (case order
                (:event-first :response-first) (is (= (get-in ok [:data :content]) "done"))
                :runtime-error (is (= (ex-message error) "Rejected"))
                :turn-timeout (is (= (:type (ex-data error)) :turn-timeout))
                :drain-timeout (is (= (:type (ex-data error)) :observer-drain-timeout))
                (:missing-event :missing-response) (is (= (ex-message error) "Connection closed by remote"))))
            (is (empty? (:waiters @(.-turns s))))
            (finally (deliver release true))))))))

(deftest eof-during-session-release-preserves-accepted-publications
  (doseq [method ["session.detach" "session.delete" "session.list" nil] observe? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [seen (atom []) opening (try-open c (cond-> {:session-id "s"}
                                                   observe? (assoc :events {:on-event #(swap! seen conj (:id %))})))
              _ (reply! (next!) {:sessionId "s"}) s (bounded opening) stream (sdk/subscribe s)
              turn (sdk/send-and-wait! s "go") request (next!)
              releasing (when method
                          (future (try (if (= method "session.detach") (sdk/close!! s) (sdk/delete-session!! c "s"))
                                       (catch Exception e e))))]
          (when method (is (= (:method (next-release! next! reply! method "s")) method)))
          (locking (.-lifecycle c)
            (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
            (send! {:method "session.event" :params {:sessionId "s" :event {:id "idle" :type "session.idle"}}})
            (reply! request {:messageId "m" :aborted false :completionEventId "idle"
                             :response {:type "assistant.message" :data {:content "done"}}})
            (send! {:id "accepted" :method "unknown" :params {}})
            (is (= (:id (next!)) "accepted"))
            (send!)
            (if releasing
              (is (= (ex-message (bounded releasing)) "Connection closed by remote"))
              (let [^Thread reader (:read-thread (.-connection c))]
                (.join reader 3000)
                (is (not (.isAlive reader)))
                (is (nil? (sdk/close!! s)))))
            (is (= (try (sdk/subscribe s) (catch Exception e (:type (ex-data e)))) :session-closed))
            (is (not (contains? @(.-lifecycle s) ::client/delete-boundary))))
          (let [items (bounded (future (a/<!! (a/into [] stream))))
                result (bounded (future (a/<!! turn)))]
            (is (= (mapv #(or (:id %) (-> % :error ex-data :type)) items) ["idle" :connection-closed]))
            (is (= (if observe? (-> result :error ex-data :type) (get-in result [:ok :data :content]))
                   (if observe? :session-closed "done")))
            (is (empty? @seen))))))))

(deftest client-shutdown-does-not-join-a-detach-that-already-failed
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "s"}) _ (reply! (next!) {:sessionId "s"}) s (bounded opening)
            detach (future (try (sdk/close!! s) (catch Exception e (:method (ex-data e)))))]
        (is (= (:method (next!)) "session.detach"))
        (locking (.-lifecycle c)
          ;; Keep the handle in the router after its detach caller has returned.
          (send! {:method "session.lifecycle" :params {:sessionId "park" :type "session.updated"}})
          (send! {:method "session.event" :params {:sessionId "s" :event {:id "queued" :type "session.info"}}})
          (send!)
          (is (= (bounded detach) "session.detach"))
          (is (= (try (sdk/close!! c) (catch Exception e (:method (ex-data e)))) "runtime.shutdown")))))))

(deftest failed-self-detach-keeps-observer-interruption-with-the-explicit-closer
  (doseq [external-close? [false true] overflow? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [handle (promise) proceed (promise) entered (promise) detached (promise)
              release (promise) stopped (promise) drain-timer? (atom false)
              calls (atom [])
              observer (fn [{:keys [id error]}]
                         (swap! calls conj (or id (:type (ex-data error))))
                         (when-not error
                           (deliver entered true) (bounded proceed)
                           (deliver detached (try (sdk/close!! @handle) (catch Exception e (ex-message e))))
                           (deliver stopped (try @release (catch InterruptedException _ :interrupted)))))
              opening (try-open c {:session-id "s" :events {:on-event observer}})
              _ (reply! (next!) {:sessionId "s"}) s (bounded opening) _ (deliver handle s)
              stream (sdk/subscribe s) deadline (a/chan) clock a/timeout
              worker @(get-in @(:sessions (.-callbacks c)) ["s" :observer :task])
              emit! #(send! {:method "session.event" :params {:sessionId "s" :event {:type "session.info" :id %}}})]
          (try
            (with-redefs [a/timeout (fn [^long ms] (if (= ms 500) (do (reset! drain-timer? true) deadline) (clock ms)))]
              (emit! "first")
              (bounded entered)
              (is (= (:id (bounded (future (a/<!! stream)))) "first"))
              (when overflow?
                (dotimes [n 65] (emit! (str n)) (bounded (future (a/<!! stream)))))
              (deliver proceed true)
              (is (= (:method (next!)) "session.detach"))
              (locking (.-lifecycle c)
                (send! {:method "session.lifecycle" :params {:type "session.updated" :sessionId "park"}})
                (emit! "last")
                (send!)
                (is (= (bounded detached) "Connection closed by remote"))
                (when external-close? (is (nil? (sdk/close!! s)))))
              (is (= (mapv :id (butlast (bounded (future (a/<!! (a/into [] stream)))))) ["last"]))
              (when-not external-close? (is (false? @drain-timer?)))
              (deliver release :finished)
              (is (= (bounded stopped) (if external-close? :interrupted :finished)))
              (when-not external-close?
                (.get ^java.util.concurrent.Future worker 5 java.util.concurrent.TimeUnit/SECONDS)
                (is (= @calls ["first"]))))
            (finally (deliver proceed true) (deliver release :finished) (a/close! deadline))))))))

(deftest an-observer-that-failed-its-session-remains-interruptible
  (doseq [end [:eof :detach]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [handle (promise) failed (promise) hold (promise) stopped (promise)
              observer (fn [_]
                         (deliver failed (try (sdk/respond!! @handle :tool {:request-id "restored" :result "ok"})
                                              (catch Exception e (:type (ex-data e)))))
                         (deliver stopped (try @hold (catch InterruptedException _ :interrupted))))
              opening (try-open c {:session-id "s" :events {:on-event observer}})
              _ (reply! (next!) {:sessionId "s"}) s (bounded opening) _ (deliver handle s)
              life (sdk/lifecycle-events c) deadline (a/chan) timer a/timeout]
          (try
            (with-redefs [callbacks/claim-limit 1
                          a/timeout (fn [^long ms] (if (= ms 500) deadline (timer ms)))]
              (send! {:method "session.event" :params {:sessionId "s" :event {:type "permission.completed"
                                                                              :data {:requestId "spent"}}}})
              (is (= (bounded failed) :callback-claim-limit))
              (if (= end :eof)
                (do (send!)
                    (is (= (-> (bounded (future (a/<!! life))) :error ex-data :type) :connection-closed))
                    (sdk/close!! s))
                (let [closing (future (sdk/close!! s))]
                  (reply! (next!) {:success true})
                  (bounded closing)
                  (sdk/force-close!! c)))
              (deliver hold :not-interrupted)
              (is (= (bounded stopped) :interrupted)))
            (finally (deliver hold :finished) (a/close! deadline) (a/close! life))))))))

(deftest a-turn-started-by-its-observer-can-complete-before-its-follow-up-is-observed
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [handle (promise) nested (promise) first? (atom true)
            seen (a/chan 4)
            observer (fn [{:keys [id]}]
                       (a/offer! seen id)
                       (when (compare-and-set! first? true false)
                         (deliver nested (a/<!! (sdk/send-and-wait! @handle {:prompt "inner" :timeout-ms 1500})))))
            opening (try-open c {:session-id "notes" :events {:on-event observer}})
            _ (reply! (next!) {:sessionId "notes"})
            session (bounded opening)
            _ (deliver handle session)
            outer (sdk/send-and-wait! session "outer")
            finish! (fn [request id]
                      (is (= [(:method request) (get-in request [:params :prompt])] ["session.sendAndWait" id]))
                      (send! {:method "session.event" :params {:sessionId "notes" :event {:type "session.idle" :id id}}})
                      (reply! request {:aborted false :messageId (str "m-" id) :completionEventId id
                                       :response {:type "assistant.message" :data {:content id}}}))]
        (finish! (next!) "outer")
        (finish! (next!) "inner")
        (is (= (get-in (bounded nested) [:ok :data :content]) "inner"))
        (is (= (get-in (sdk/await!! outer) [:data :content]) "outer"))
        (is (= (mapv (fn [_] (bounded (future (a/<!! seen)))) (range 2)) ["outer" "inner"]))))))

(deftest a-retained-observation-can-start-a-turn-without-waiting-on-itself
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [handle (promise) finished (promise) observed (promise)
            observe (fn [{:keys [id]}]
                      (case id
                        "early" (deliver finished (a/<!! (sdk/send-and-wait! (bounded handle) "next")))
                        "done" (deliver observed true)
                        nil))
            opening (try-open c {:cloud {} :events {:on-event observe}})
            create (next!)
            event! #(send! {:method "session.event" :params {:sessionId "cloud" :event %}})]
        (event! {:id "early" :type "session.info"})
        (reply! create {:sessionId "cloud"})
        (deliver handle (bounded opening))
        (let [request (next!)]
          (is (= (:method request) "session.sendAndWait"))
          (event! {:id "done" :type "session.idle"})
          (reply! request {:aborted false :messageId "m" :completionEventId "done"
                           :response {:type "assistant.message" :data {:content "ok"}}}))
        (is (= (get-in (bounded finished) [:ok :data :content]) "ok"))
        (is (true? (bounded observed)))))))

(deftest explicit-client-close-cancels-observers-already-draining-after-eof
  (doseq [force? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [entered (promise)
              interrupted (promise)
              release (promise)
              seen (atom [])
              opening (try-open c {:session-id "s" :events {:on-event (fn [{:keys [id]}]
                                                                        (swap! seen conj id)
                                                                        (deliver entered true)
                                                                        (try @release (catch InterruptedException _
                                                                                        (deliver interrupted true))))}})
              _ (reply! (next!) {:sessionId "s"})
              s (bounded opening)
              stream (sdk/subscribe s)
              timer a/timeout
              deadline (a/chan)]
          (try
            (doseq [id ["first" "buffered"]]
              (send! {:method "session.event" :params {:sessionId "s" :event {:id id :type "session.info" :data {}}}}))
            (bounded entered)
            ;; The drain timer cannot fire; only explicit client close can interrupt the worker.
            (with-redefs [a/timeout (fn [^long ms] (if (= ms 500) deadline (timer ms)))]
              (send!)
              (is (= (mapv :id (butlast (bounded (future (a/<!! (a/into [] stream)))))) ["first" "buffered"]))
              (if force?
                (is (nil? (sdk/force-close!! c)))
                (is (= (try (sdk/close!! c) (catch Exception e (:type (ex-data e)))) :connection-closed)))
              (is (true? (bounded interrupted)))
              (is (= @seen ["first"])))
            (finally (deliver release true) (a/close! deadline))))))))

(defn- assert-no-queued-request! [c next! reply!]
  (let [ping (sdk/ping c "after-rollback")
        frame (next!)]
    (is (= [(:method frame) (:params frame)] ["ping" {:message "after-rollback"}]))
    (reply! frame {:message "pong: after-rollback"})
    (is (= "pong: after-rollback" (:message (sdk/await!! ping))))))

(deftest explicit-id-mismatch-reserves-and-detaches-only-the-returned-id
  (doseq [[method requested returned accepted?]
          [["session.resume" "build-owner" "Build-Owner" true]
           ["session.create" "requested" "allocated" false]]]
    (with-runtime {}
      (fn [c _ next! reply!]
        (let [open! #(try-open c {:session-id %})
              opening (if (= method "session.resume")
                        (future (try (sdk/resume-session!! c requested {}) (catch Exception e e)))
                        (open! requested))
              request (next!)]
          (is (= [(:method request) (get-in request [:params :sessionId])] [method requested]))
          (reply! request {:sessionId returned})
          (let [detach (next!)]
            (is (= [(:method detach) (:params detach)] ["session.detach" {:sessionId returned}]))
            (doseq [id [requested returned]]
              (is (= (:type (ex-data (bounded (open! id)))) :session-already-open)))
            (let [other (open! "independent")]
              (reply! (next!) {:sessionId "independent"})
              (is (= (sdk/session-id (bounded other)) "independent")))
            (reply! detach {:success accepted? :error "Refused"}))
          (let [failure (bounded opening)]
            (is (= (ex-data failure) {:type :session-id-mismatch :session-id requested :returned-session-id returned}))
            (is (= (mapv ex-data (.getSuppressed failure))
                   (if accepted? [] [{:session-id returned :reason "Refused"}]))))
          (if accepted?
            (let [again (open! returned)]
              (reply! (next!) {:sessionId returned})
              (is (= (sdk/session-id (bounded again)) returned)))
            (do
              (is (= (:type (ex-data (bounded (open! returned)))) :session-cleanup-unconfirmed))
              (let [again (open! requested)]
                (reply! (next!) {:sessionId requested})
                (is (= (sdk/session-id (bounded again)) requested)))))
          (assert-no-queued-request! c next! reply!))))))

(deftest mismatched-reply-cannot-release-an-existing-or-in-flight-owner
  (doseq [pending? [false true]]
    (with-runtime {}
      (fn [c _ next! reply!]
        (let [original (try-open c {:session-id "resident"})
              create (next!)
              _ (when-not pending? (reply! create {:sessionId "resident"}) (bounded original))
              intruder (try-open c {:session-id "unrelated"})]
          (reply! (next!) {:sessionId "resident"})
          (let [failure (bounded intruder)]
            (is (= (:type (ex-data failure)) :session-id-mismatch))
            (is (= (mapv ex-data (.getSuppressed failure)) [{:type :session-already-open :session-id "resident"}])))
          (when pending? (reply! create {:sessionId "resident"}))
          (let [history (sdk/history (bounded original))
                request (next!)]
            (is (= [(:method request) (:params request)] ["session.getMessages" {:sessionId "resident"}]))
            (reply! request {:events []})
            (is (= (sdk/await!! history) []))))))))

(deftest interrupted-mismatch-keeps-its-shutdown-join-through-canonical-detach
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [live (try-open c {:session-id "live"})
            _ (reply! (next!) {:sessionId "live"})
            _ (bounded live)
            opener (promise)
            opening (future (deliver opener (Thread/currentThread))
                            (try (sdk/resume-session!! c "requested" {})
                                 (catch Exception e [e (.isInterrupted (Thread/currentThread))])))
            request (next!)
            closing (future (try (sdk/close!! c) (catch Exception e e)))
            live-detach (next!)]
        (is (= [(:method live-detach) (:params live-detach)] ["session.detach" {:sessionId "live"}]))
        (.interrupt ^Thread (bounded opener))
        (reply! live-detach {:success true})
        (reply! request {:sessionId "canonical"})
        (let [cleanup (next!)]
          (is (= [(:method cleanup) (:params cleanup)] ["session.detach" {:sessionId "canonical"}]))
          (send! {:id (:id cleanup) :error {:code -32000 :message "Refused"}}))
        (let [[failure interrupted?] (bounded opening)]
          (is (instance? InterruptedException failure))
          (is interrupted?)
          (is (= (mapv #(or (:type (ex-data %)) (ex-message %)) (.getSuppressed failure))
                 [:session-id-mismatch "Refused"])))
        (let [shutdown (next!)]
          (is (= (:method shutdown) "runtime.shutdown"))
          (reply! shutdown {}))
        (is (= (ex-message (bounded closing)) "Refused"))))))

(deftest an-invalid-session-id-does-not-authorize-cleanup
  (with-runtime {}
    (fn [c _ next! reply!]
      (doseq [[id response] [["missing" {}] ["blank" {:sessionId " "}] ["non-string" {:sessionId 19}]]]
        (let [opening (try-open c {:session-id id})]
          (reply! (next!) response)
          (is (= (:type (ex-data (bounded opening))) :missing-session-id))
          (is (= (:type (ex-data (bounded (try-open c {:session-id id})))) :session-cleanup-unconfirmed))))
      (assert-no-queued-request! c next! reply!))))

(defn -main [base-url _]
  (try
    (sdk/with-client [c {:mode :empty :base-directory (System/getenv "HOME") :github {:use-logged-in-user? false}}]
      (let [opts {:tools {:available-tools []} :model "fixture/chat"
                  :inference {:providers [{:name "fixture" :provider-type :openai :base-url base-url}]
                              :models [{:id "chat" :provider "fixture" :model-id "gpt-4o"}]}
                  :github {:capi {:enabled? false}}}
            id "facade00-1234-4321-abcd-0123456789ab"
            sid (.toUpperCase id java.util.Locale/ROOT)
            blank "unused-exact-id"
            delete-error #(try (sdk/delete-session!! c %) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
            unlisted (sdk/with-session [_ c (assoc opts :session-id blank)] (delete-error blank))
            detached-unlisted (delete-error blank)
            ;; Query the runtime directly: there is deliberately no Clojure handle after rollback.
            history #(try (rpc/send-request! (.-connection c) "session.getMessages" {:session-id id} 10000)
                          :resident
                          (catch clojure.lang.ExceptionInfo e (ex-message e)))
            live (sdk/with-session [_ c (assoc opts :session-id id :name "Offline identity test")] (history))
            detached (history)
            mismatch (try (with-open [_ (sdk/resume-session!! c sid opts)] :unexpected)
                          (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
            after (history)
            reopened (with-open [_ (sdk/resume-session!! c id opts)] (history))
            listed? #(some (fn [row] (= id (:session-id row))) (sdk/await!! (sdk/list-sessions c)))
            named-cleanup [(boolean (listed?)) (sdk/delete-session!! c id) (boolean (listed?))]]
        (spit "result.edn" (pr-str {:history [live detached mismatch after reopened]
                                    :unlisted [unlisted detached-unlisted]
                                    :named-cleanup named-cleanup}))))
    (finally (shutdown-agents))))

(deftest ^:native a-rejected-canonical-session-is-not-left-resident
  (let [{:keys [event requests]} (offline/exercise-turn! ["-m" "github.copilot-sdk.native-cloud-test"]
                                                         (fn [_] (throw (ex-info "Unexpected model request" {}))))
        [live detached mismatch after reopened] (:history event)]
    (is (empty? requests))
    (is (= (:unlisted event) [:session-delete-unconfirmed :session-delete-unconfirmed]))
    (is (= (:named-cleanup event) [true nil false]))
    (is (= live reopened :resident))
    (is (re-find #"Session not found:" detached))
    ;; Darwin 1.0.114 canonicalizes the spelling; a case-sensitive store can refuse it.
    (when (= "Mac OS X" (System/getProperty "os.name"))
      (is (= mismatch :session-id-mismatch)))
    (is (= after detached))))

(deftest reverse-callbacks-cannot-shut-down-their-own-client
  (let [released (atom [])]
    (with-runtime {}
      (fn [other _ _ _]
        (with-runtime {}
          (fn [c send! next! reply!]
            (let [forced (promise)
                  after-hook (promise)
                  close-error (fn [close!] (try (close! c) (catch Exception e e)))
                  opening (try-open c {:session-id "s"
                                       :interaction {:on-user-input-request
                                                     (fn [_ _]
                                                       (sdk/force-close!! other)
                                                       (deliver forced (close-error sdk/force-close!!))
                                                       {:answer "ok" :was-freeform? false})}
                                       :hooks {:handlers {:on-agent-stop
                                                          (fn [_ _]
                                                            (deliver after-hook (bound-fn [] (sdk/force-close!! c)))
                                                            {:reason (some-> (close-error sdk/close!!) ex-data :type name)})}}})]
              (reply! (next!) {:sessionId "s"})
              (bounded opening)
              (send! {:id "prompt" :method "userInput.request" :params {:sessionId "s" :question "Continue?"}})
              (let [error (bounded forced)]
                (is (= (ex-data error) {:type :reverse-callback-client-close}) (ex-message error)))
              (is (= @released [:other]))
              (is (= (:result (next!)) {:answer "ok" :wasFreeform false}))
              (send! {:id "hook" :method "hooks.invoke" :params {:sessionId "s" :hookType "agentStop" :input {}}})
              (is (= (:result (next!)) {:output {:reason "reverse-callback-client-close"}}))
              (assert-no-queued-request! c next! reply!)
              (is (nil? ((bounded after-hook))))
              (is (= @released [:other :self]))))
          #(swap! released conj :self)))
      #(swap! released conj :other))))

(deftest reverse-callback-can-hand-client-shutdown-to-another-thread
  (doseq [force? [true false] return-future? [false true]]
    (let [released (atom 0)]
      (with-runtime {}
        (fn [c send! next! reply!]
          (let [started (promise) finished (promise)
                wait-for-cancel (a/chan)
                handler (fn [_ _]
                          (let [shutdown (future
                                           (deliver started true)
                                           (deliver finished (try ((if force? sdk/force-close!! sdk/close!!) c)
                                                                  (catch Exception e e))))]
                            (if return-future?
                              shutdown
                              ;; Model an independent handoff while the callback still
                              ;; has work. Client shutdown interrupts the parked worker.
                              (do (a/<!! wait-for-cancel) {:answer "unused" :was-freeform? false}))))
                opening (try-open c {:session-id "handoff" :interaction {:on-user-input-request handler}})]
            (try
              (reply! (next!) {:sessionId "handoff"})
              (bounded opening)
              (send! {:id "prompt" :method "userInput.request" :params {:sessionId "handoff" :question "Quit?"}})
              (bounded started)
              (when-not force?
                (doseq [method ["session.detach" "runtime.shutdown"]]
                  (let [req (loop [v (next!)] (if (:method v) v (recur (next!))))]
                    (is (= (:method req) method))
                    (reply! req {:success true}))))
              (is (nil? (bounded finished)))
              (is (= @released 1))
              (finally (a/close! wait-for-cancel)))))
        #(swap! released inc)))))

(defn- lifecycle-outcome [f]
  (try (f) :ok (catch Exception e (or (:type (ex-data e)) (class e)))))

(defn- session-event! [send! type data]
  (send! {:method "session.event" :params {:sessionId "s" :event {:type type :id type :data (assoc data :requestId type)}}}))

(deftest broadcast-workers-and-observers-cannot-close-their-client
  (let [released (atom 0)]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [outcomes (atom {})
              observer (promise)
              check! (fn [k]
                       (swap! outcomes assoc k (mapv #(lifecycle-outcome (partial % c)) [sdk/force-close!! sdk/close!!])))
              opening (try-open c {:session-id "s"
                                   :tools {:definitions [{:name "tool" :handler (fn [& _] (check! :tool) "ok")}]}
                                   :permissions {:on-request (fn [& _] (check! :permission) {:kind :reject})}
                                   :interaction {:commands [{:name "cmd" :handler (fn [_] (check! :command))}]
                                                 :on-elicitation-request (fn [_] (check! :form) {:action :decline})}
                                   :mcp {:on-auth-request (fn [& _] (check! :oauth) {:kind :cancelled})}
                                   :events {:on-event (fn [{:keys [type]}]
                                                        (when (= type :copilot/session.info)
                                                          (check! :observer) (deliver observer true)))}})]
          (reply! (next!) {:sessionId "s"})
          (let [interest (next!)]
            (is (= (:method interest) "session.eventLog.registerInterest"))
            (reply! interest {}))
          (bounded opening)
          (doseq [[k type data method]
                  [[:permission "permission.requested" {:permissionRequest {:kind "read"}} "session.permissions.handlePendingPermissionRequest"]
                   [:tool "external_tool.requested" {:toolName "tool" :toolCallId "call" :arguments {}} "session.tools.handlePendingToolCall"]
                   [:command "command.execute" {:commandName "cmd"} "session.commands.handlePendingCommand"]
                   [:form "elicitation.requested" {:message "Choose"} "session.ui.handlePendingElicitation"]
                   [:oauth "mcp.oauth_required" {:reason "initial"} "session.mcp.oauth.handlePendingRequest"]]]
            (session-event! send! type data)
            (let [answer (next!)]
              (is (= (:method answer) method))
              (reply! answer {:success true})
              (is (= (@outcomes k) [:callback-client-close :callback-client-close]) k)))
          (session-event! send! "session.info" {})
          (bounded observer)
          (is (= (@outcomes :observer) [:callback-client-close :callback-client-close]))
          (is (zero? @released))
          (assert-no-queued-request! c next! reply!)))
      #(swap! released inc))))

(deftest decisions-cannot-release-their-own-session-but-can-release-another
  (doseq [reverse? [true false]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [handles (promise)
              results (promise)
              check! (fn [& _]
                       (let [[self other] (bounded handles)]
                         (deliver results [(lifecycle-outcome #(sdk/close!! self))
                                           (lifecycle-outcome #(sdk/delete-session!! c "s"))
                                           (lifecycle-outcome #(sdk/close!! other))
                                           (lifecycle-outcome #(sdk/delete-session!! c "other"))]))
                       (if reverse? {:answer "ok" :was-freeform? false} {:kind :reject}))
              open! (fn [opts]
                      (let [opening (try-open c opts)]
                        (reply! (next!) {:sessionId (:session-id opts)})
                        (bounded opening)))
              self (open! (assoc (if reverse? {:interaction {:on-user-input-request check!}}
                                     {:permissions {:on-request check!}}) :session-id "s"))]
          (deliver handles [self (open! {:session-id "other"})])
          (if reverse?
            (send! {:id "prompt" :method "userInput.request" :params {:sessionId "s" :question "Done?"}})
            (session-event! send! "permission.requested" {:permissionRequest {:kind "read"}}))
          (let [detach (next!)]
            (is (= [(:method detach) (get-in detach [:params :sessionId])] ["session.detach" "other"]))
            (reply! detach {:success true}))
          (let [delete (next-release! next! reply! "session.delete" "other")]
            (is (= [(:method delete) (get-in delete [:params :sessionId])] ["session.delete" "other"]))
            (reply! delete {:success true}))
          (is (= (bounded results) [:callback-session-close :callback-session-close :ok :ok]))
          (let [answer (next!)]
            (if reverse?
              (is (= (:result answer) {:answer "ok" :wasFreeform false}))
              (do (is (= (:method answer) "session.permissions.handlePendingPermissionRequest"))
                  (reply! answer {:success true}))))
          (let [op (sdk/abort! self) req (next!)]
            (is (= (:method req) "session.abort"))
            (reply! req {:success true})
            (is (nil? (sdk/await!! op)))))))))

(deftest filesystem-cannot-join-the-detach-that-needs-its-write
  (doseq [client-close? [false true] conveyed? [false true]]
    (with-runtime fs-options
      (fn [c send! next! reply!]
        (let [handles (promise)
              result (promise)
              store (atom {"/" nil})
              provider (update (memory-fs/provider store) :write-file
                               (fn [write!] (fn [& args]
                                              (let [flush! (fn []
                                                             (let [[self sibling] (bounded handles)]
                                                               (deliver result [(lifecycle-outcome #(sdk/close!! self))
                                                                                (lifecycle-outcome #(sdk/close!! c))
                                                                                (lifecycle-outcome #(sdk/close!! sibling))]))
                                                             (apply write! args))]
                                                (if conveyed? (future (flush!)) (flush!))))))
              open! (fn [id]
                      (let [opening (try-open c {:session-id id :state {:create-session-fs-provider provider}})]
                        (reply! (next!) {:sessionId id})
                        (bounded opening)))
              s (open! "s")
              _ (deliver handles [s (open! "sibling")])
              closing (future (sdk/close!! (if client-close? c s)))
              detaches (if client-close? [(next!) (next!)] [(next!)])]
          (is (every? #(= (:method %) "session.detach") detaches))
          (is (= (set (map #(get-in % [:params :sessionId]) detaches))
                 (if client-close? #{"s" "sibling"} #{"s"})))
          (try
            (send! {:id "flush" :method "sessionFs.writeFile" :params {:sessionId "s" :path "/last" :content "saved"}})
            (when-not client-close?
              (let [sibling (next!)]
                (is (= [(:method sibling) (:params sibling)] ["session.detach" {:sessionId "sibling"}]))
                (reply! sibling {:success true})))
            (is (= (bounded result) [:callback-session-close :reverse-callback-client-close
                                     (if client-close? :reverse-callback-client-close :ok)]))
            (is (= (select-keys (next!) [:id :result]) {:id "flush" :result nil}))
            (is (= (@store "/last") "saved"))
            (finally (doseq [detach detaches] (reply! detach {:success true}))))
          (when client-close?
            (let [shutdown (next!)]
              (is (= (:method shutdown) "runtime.shutdown"))
              (reply! shutdown {})))
          (is (nil? (bounded closing))))))))

(deftest broadcast-workers-can-hand-closure-to-another-thread
  (doseq [[kind operation] [[:permission :detach] [:form :delete] [:observer :shutdown]]]
    (let [released (atom 0)]
      (with-runtime {}
        (fn [c send! next! reply!]
          (let [handle (promise) cancelled (promise) done (promise)
                park (a/chan)
                handler (fn [& _]
                          (future
                            (deliver done (lifecycle-outcome
                                           #(case operation
                                              :detach (sdk/close!! (bounded handle))
                                              :delete (sdk/delete-session!! c "s")
                                              :shutdown (sdk/close!! c)))))
                          (try (a/<!! park) (catch InterruptedException _ (deliver cancelled true))))
                opening (try-open c (assoc (case kind
                                             :permission {:permissions {:on-request handler}}
                                             :form {:interaction {:on-elicitation-request handler}}
                                             :observer {:events {:on-event handler}})
                                           :session-id "s"))]
            (try
              (reply! (next!) {:sessionId "s"})
              (deliver handle (bounded opening))
              (case kind
                :permission (session-event! send! "permission.requested" {:permissionRequest {:kind "read"}})
                :form (session-event! send! "elicitation.requested" {:message "Choose"})
                :observer (session-event! send! "session.info" {}))
              (doseq [method (case operation :detach ["session.detach"] :delete ["session.delete"]
                                   :shutdown ["session.detach" "runtime.shutdown"])]
                (let [frame (next-release! next! reply! method "s")]
                  (is (= (:method frame) method))
                  (reply! frame {:success true})))
              (is (true? (bounded cancelled)))
              (is (= (bounded done) :ok))
              (is (= @released (if (= :shutdown operation) 1 0)))
              (finally (a/close! park)))))
        #(swap! released inc)))))

(deftest factory-lifecycle-follows-its-active-conveyed-scope
  (let [released (atom [])]
    (with-runtime {}
      (fn [other _ _ _]
        (with-runtime fs-options
          (fn [c _ next! reply!]
            (let [later (promise) results (promise)
                  factory (fn [s]
                            (deliver later (bound-fn [] (sdk/force-close!! c)))
                            (let [done (java.util.concurrent.CompletableFuture.)]
                              (future
                                (.complete done [(lifecycle-outcome #(sdk/force-close!! other))
                                                 (lifecycle-outcome #(sdk/force-close!! c))]))
                              ;; join is non-interruptible; bound the fixture even if the worker is cancelled.
                              (deliver results [(.join (.orTimeout done 3 java.util.concurrent.TimeUnit/SECONDS))
                                                (lifecycle-outcome #(sdk/close!! s))
                                                (lifecycle-outcome #(sdk/delete-session!! c "s"))]))
                            (memory-fs/provider (atom {"/" nil})))
                  opening (try-open c {:session-id "s" :state {:create-session-fs-provider factory}})]
              (is (= (bounded results) [[:ok :session-fs-factory-reentry] :session-fs-factory-reentry :session-fs-factory-reentry]))
              (is (= @released [:other]))
              (let [create (next!)]
                (is (= (:method create) "session.create"))
                (reply! create {:sessionId "s"}))
              (is (= (sdk/session-id (bounded opening)) "s"))
              (is (nil? ((bounded later))))
              (is (= @released [:other :self]))))
          #(swap! released conj :self)))
      #(swap! released conj :other))))

(deftest graceful-close-drains-storage-and-only-the-open-sessions-end-hook
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [events (sdk/lifecycle-events c)
            entered (promise)
            decision (a/promise-chan)
            calls (atom [])
            provider (memory-fs/provider (atom {"/" nil}))
            config {:state {:create-session-fs-provider provider}
                    :hooks {:handlers {:on-session-end (fn [_ ctx] (swap! calls conj [:end (:session-id ctx)])
                                                         {:session-summary "done"})
                                       :on-agent-stop (fn [& _] (swap! calls conj :agent-stop) nil)}}
                    :github {:token-provider (fn [_] (swap! calls conj :token) {:kind :cancelled})}
                    :interaction {:on-exit-plan-mode-request (fn [_] (swap! calls conj :plan) {:approved? false})}
                    :permissions {:on-request (fn [_ ctx]
                                                (swap! calls conj :permission)
                                                (deliver entered (:cancelled? ctx))
                                                decision)}
                    :events {:on-event #(swap! calls conj (:id %))}}
            permission! (fn [id request]
                          (send! {:method "session.event"
                                  :params {:sessionId id :event {:id request :type "permission.requested"
                                                                 :data {:requestId request
                                                                        :permissionRequest {:kind "read"}}}}}))
            hook! (fn [id kind]
                    (send! {:id "hook" :method "hooks.invoke" :params {:sessionId id :hookType kind :input {}}})
                    (next!))
            live (try-open c (assoc config :session-id "live"))
            live-create (next!)
            _ (reply! live-create {:sessionId "live"})
            _ (bounded live)
            opening (try-open c (assoc config :cloud {} :git {:coauthor-enabled? false}))
            cloud-create (next!)
            _ (reply! cloud-create {:sessionId "allocated"})
            patch (next!)]
        (permission! "allocated" "before-close")
        (let [cancelled? (bounded entered)
              closing (future (try (sdk/close!! c) (catch Exception e e)))
              detach (next!)]
          (try
            (is (= [(:method patch) (:method detach)] ["session.options.update" "session.detach"]))
            (is (true? (cancelled?)))
            (doseq [[id create] [["live" live-create] ["allocated" cloud-create]]]
              (send! {:id "stored" :method "sessionFs.exists" :params {:sessionId id :path "/"}})
              (is (= (:result (next!)) {:exists true}) id)
              (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "draining"))
              (is (= (:code (:error (next!))) -32001) id)
              (send! {:id "decision" :method "exitPlanMode.request"
                      :params {:sessionId id :summary "work" :actions ["done"] :recommendedAction "done"}})
              (is (= (:code (:error (next!))) -32001) id)
              (permission! id "after-close"))
            (send! {:method "session.lifecycle" :params {:type "created" :sessionId "barrier"}})
            (is (= (:session-id (bounded (future (a/<!! events)))) "barrier"))
            (is (= (:result (hook! "live" "sessionEnd")) {:output {:sessionSummary "done"}}))
            (doseq [[id kind] [["live" "agentStop"] ["allocated" "sessionEnd"] ["other" "sessionEnd"]]]
              (is (= (get-in (hook! id kind) [:error :code]) -32001) [id kind]))
            (reply! detach {:success true})
            (is (= (get-in (hook! "live" "sessionEnd") [:error :code]) -32001))
            (send! {:id "detached" :method "sessionFs.exists" :params {:sessionId "live" :path "/"}})
            (is (= (:code (:error (next!))) -32001))
            (reply! patch {:success false})
            (let [release (next!)]
              (is (= [(:method release) (:params release)] ["session.detach" {:sessionId "allocated"}]))
              (reply! release {:success true}))
            (let [shutdown (next!)]
              (is (= (:method shutdown) "runtime.shutdown"))
              (is (= "Runtime rejected the session options patch" (ex-message (bounded opening))))
              (send! {:id "retired" :method "sessionFs.exists" :params {:sessionId "allocated" :path "/"}})
              (is (= (:code (:error (next!))) -32001))
              (reply! shutdown {}))
            (is (= {:type :session-cleanup-unconfirmed :session-id "allocated" :reason :session-fs-delete-unsupported}
                   (ex-data (bounded closing))))
            (is (= 1 (count (filter #{:permission} @calls))))
            (is (= [[:end "live"]] (filterv vector? @calls)))
            (is (not-any? #{:token :plan :agent-stop "after-close"} @calls))
            (finally (a/close! decision) (a/close! events))))))))

(deftest refused-detach-keeps-only-cleanup-through-runtime-shutdown
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [store (atom {"/" nil})
            opening (try-open c {:session-id "save" :state {:create-session-fs-provider (memory-fs/provider store)}
                                 :github {:token-provider (constantly {:kind :cancelled})}})
            create (next!)
            _ (reply! create {:sessionId "save"})
            s (bounded opening)
            closing (future (try (sdk/close!! c) (catch Exception e e)))
            detach (next!)]
        (is (= (:method detach) "session.detach"))
        (reply! detach {:success false})
        (let [shutdown (next!)]
          (is (= (:method shutdown) "runtime.shutdown"))
          (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "late"))
          (is (= (get-in (next!) [:error :code]) -32001))
          (send! {:id "flush" :method "sessionFs.writeFile"
                  :params {:sessionId "save" :path "/last" :content "saved"}})
          (is (= (select-keys (next!) [:result :error]) {:result nil}))
          (is (= (@store "/last") "saved"))
          ;; Keep final caller-side cleanup out while the reader takes the next frame.
          (locking c
            (reply! shutdown {})
            (send! {:id "after" :method "sessionFs.writeFile"
                    :params {:sessionId "save" :path "/last" :content "late"}})
            (is (= (get-in (next!) [:error :code]) -32001))
            (is (= (@store "/last") "saved"))))
        (is (= (ex-message (bounded closing)) "Runtime did not detach the session"))
        (is (not (contains? @(.-lifecycle s) :reverse-token)))))))

(deftest rollback-keeps-final-storage-only-until-confirmed-release
  (doseq [detached? [false true]]
    (with-runtime fs-options
      (fn [c send! next! reply!]
        (let [live (try-open c {:session-id "live" :state {:create-session-fs-provider (memory-fs/provider (atom {}))}})
              _ (reply! (next!) {:sessionId "live"})
              _ (bounded live)
              store (atom {"/" nil})
              opening (try-open c {:session-id "notes" :git {:coauthor-enabled? false}
                                   :state {:create-session-fs-provider (memory-fs/provider store)}
                                   :github {:token-provider (constantly {:kind :cancelled})}})
              create (next!)
              flush! (fn [value]
                       (send! {:id "flush" :method "sessionFs.writeFile"
                               :params {:sessionId "notes" :path "/journal" :content value}})
                       (select-keys (next!) [:result :error]))]
          (reply! create {:sessionId "notes"})
          (let [patch (next!)]
            (is (= (:method patch) "session.options.update"))
            (reply! patch {:success false}))
          (let [detach (next!)]
            (is (= [(:method detach) (:params detach)] ["session.detach" {:sessionId "notes"}]))
            (reply! detach {:success detached? :error "Still in use"}))
          (let [failure (bounded opening)]
            (is (= (ex-message failure) "Runtime rejected the session options patch"))
            (is (= (mapv #(-> % ex-data :reason) (.getSuppressed failure)) (if detached? [] ["Still in use"]))))
          (let [closing (future (sdk/close!! c))
                live-release (next!)
                _ (is (= [(:method live-release) (:params live-release)] ["session.detach" {:sessionId "live"}]))
                _ (reply! live-release {:success true})
                shutdown (next!)]
            (is (= (:method shutdown) "runtime.shutdown"))
            (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "late"))
            (is (= (get-in (next!) [:error :code]) -32001))
            (let [reply (flush! "final")]
              (if detached?
                (is (= (get-in reply [:error :code]) -32001))
                (is (= reply {:result nil}))))
            (locking c
              (reply! shutdown {})
              (is (= (get-in (flush! "too late") [:error :code]) -32001)))
            (is (= (get @store "/journal") (when-not detached? "final")))
            (is (nil? (bounded closing)))))))))

(deftest an-uncertain-session-keeps-only-storage-until-a-definite-reply
  (doseq [[phase late stays?] [[:detach :success false] [:detach :refused true] [:rollback :success false]
                               [:create :refused false] [:create :mismatch false] [:create :accepted true]]]
    (with-runtime fs-options
      (fn [c send! next! reply!]
        (let [id "notes" store (atom {"/" nil}) expire (a/chan) timer a/timeout
              opts {:state {:create-session-fs-provider (memory-fs/provider store)}
                    :github {:token-provider (constantly {:kind :cancelled})}}
              io! (fn [value]
                    (send! {:id "flush" :method "sessionFs.writeFile"
                            :params {:sessionId id :path "/journal" :content value}})
                    (select-keys (next!) [:result :error]))
              [create pending outcome]
              (with-redefs [a/timeout (fn [^long ms]
                                        (if (= ms (if (= phase :create) 60000 10000)) expire (timer ms)))]
                (let [opening (try-open c (cond-> (assoc opts :session-id id)
                                            (= phase :rollback) (assoc :git {:coauthor-enabled? false})))
                      create (next!)]
                  (is (= (io! "initial") {:result nil}))
                  (if (= phase :create)
                    [create create opening]
                    (do
                      (reply! create {:sessionId id})
                      (let [owner (if (= phase :rollback)
                                    (do (reply! (next!) {:success false}) opening)
                                    (future (try (sdk/close!! (bounded opening)) (catch Exception e e))))
                            detach (next!)]
                        (is (= (:method detach) "session.detach"))
                        [create detach owner])))))]
          (a/close! expire)
          (let [failure (bounded outcome)]
            (is (= (ex-message (if (= phase :rollback) (first (.getSuppressed failure)) failure)) "Request timeout")))
          (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "fenced"))
          (is (= (get-in (next!) [:error :code]) -32001))
          (is (= (io! "uncertain") {:result nil}))
          (case late
            :refused (send! {:id (:id pending) :error {:code -32000 :message "Refused"}})
            :accepted (reply! pending {:sessionId id})
            :mismatch (reply! pending {:sessionId "different"})
            :success (reply! pending {:success true}))
          (let [final (io! "after-reply")]
            (if stays?
              (is (= final {:result nil}))
              (is (= (get-in final [:error :code]) -32001))))
          (is (= (get @store "/journal") (if stays? "after-reply" "uncertain")))
          (is (= (-> (bounded (try-open c (assoc opts :session-id id))) ex-data :type) :session-cleanup-unconfirmed))
          (let [closing (future (sdk/close!! c))
                shutdown (next!)]
            (is (= (:method shutdown) "runtime.shutdown"))
            (if stays?
              (is (= (io! "shutdown") {:result nil}))
              (is (= (get-in (io! "shutdown") [:error :code]) -32001)))
            (reply! shutdown {})
            (is (nil? (bounded closing)))))))))

(deftest client-close-reports-unfinished-provider-work-but-releases-the-native-host
  (let [released (atom 0)]
    (with-runtime fs-options
      (fn [c send! next! reply!]
        (let [entered (promise) finished (promise) stop (java.util.concurrent.CompletableFuture.)
              provider (assoc (memory-fs/provider (atom {})) :read-file
                              (fn [_] (deliver entered true)
                                (try (.join stop) "result" (finally (deliver finished true)))))
              open (try-open c {:session-id "notes" :state {:create-session-fs-provider provider}})
              _ (reply! (next!) {:sessionId "notes"})
              _ (bounded open)
              closing (future (try (sdk/close!! c) (catch Exception e e)))
              detach (next!)]
          (try
            (send! {:id "read" :method "sessionFs.readFile" :params {:sessionId "notes" :path "/note"}})
            (bounded entered)
            (reply! detach {:success true})
            (let [frames (group-by :method [(next!) (next!)])]
              (is (= (get-in frames [nil 0 :error :code]) -32001))
              (is (= (count (get frames "runtime.shutdown")) 1))
              (reply! (first (get frames "runtime.shutdown")) {}))
            (is (= (-> (bounded closing) ex-data :type) :reverse-worker-timeout))
            (is (= @released 1))
            (is (not (realized? finished)))
            (finally (.complete stop true)))
          (is (true? (bounded finished)))))
      #(swap! released inc))))

(deftest confirmed-client-shutdown-wakes-retained-storage
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [entered (promise) interrupted (promise) release (promise)
            provider (assoc (memory-fs/provider (atom {}))
                            :read-file (fn [_]
                                         (deliver entered true)
                                         (try @release (catch InterruptedException _ (deliver interrupted true)))
                                         "retired"))
            opening (try-open c {:session-id "notes" :state {:create-session-fs-provider provider}})
            _ (reply! (next!) {:sessionId "notes"})
            s (bounded opening) deadline (a/chan) clock a/timeout
            [close-session request] (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) deadline (clock ms)))]
                                      (let [task (future (try (sdk/close!! s) (catch Exception e (ex-message e))))]
                                        [task (next!)]))]
        (is (= (:method request) "session.detach"))
        (a/close! deadline)
        (is (= (bounded close-session) "Request timeout"))
        (try
          (send! {:id "pending" :method "sessionFs.readFile" :params {:sessionId "notes" :path "/note"}})
          (bounded entered)
          (let [closing (future (try (sdk/close!! c) (catch Exception e (ex-message e))))
                shutdown (next!)]
            (is (= (:method shutdown) "runtime.shutdown"))
            (reply! shutdown {})
            (is (nil? (bounded closing)))
            (is (true? (bounded interrupted))))
          (finally (deliver release true)))))))

(deftest an-unanswered-detach-retires-the-end-hook-without-interrupting-storage
  (doseq [whole-client? [false true]]
    (with-runtime fs-options
      (fn [c send! next! reply!]
        (let [store (atom {"/" nil}) base (memory-fs/provider store)
              writing (promise) finish-write (promise) invoked (promise) stopped (promise) finish-hook (promise)
              provider (assoc base :write-file (fn [& args] (deliver writing true) @finish-write (apply (:write-file base) args)))
              calls (atom 0)
              hook (fn [& _] (swap! calls inc) (deliver invoked true)
                     (try @finish-hook (catch InterruptedException _ (deliver stopped true)))
                     {:session-summary "do not send"})
              opening (try-open c {:session-id "notes" :state {:create-session-fs-provider provider}
                                   :hooks {:handlers {:on-session-end hook}}})
              _ (reply! (next!) {:sessionId "notes"})
              s (bounded opening)
              timer a/timeout deadline (a/chan)
              [closing detach] (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) deadline (timer ms)))]
                                 (let [op (future (try (sdk/close!! (if whole-client? c s)) (catch Exception e e)))]
                                   [op (next!)]))
              end! (fn [id] (send! {:id id :method "hooks.invoke"
                                    :params {:sessionId "notes" :hookType "sessionEnd" :input {}}}))]
          (try
            (is (= (:method detach) "session.detach"))
            (end! "first")
            (send! {:id "file" :method "sessionFs.writeFile" :params {:sessionId "notes" :path "/journal" :content "saved"}})
            (bounded invoked)
            (bounded writing)
            (a/close! deadline)
            (when-not whole-client? (is (= (ex-message (bounded closing)) "Request timeout")))
            (let [request (next!)]
              ;; The hook reply and shutdown may arrive in either order.
              (if whole-client?
                (let [other (next!)
                      frames (group-by :method [request other])]
                  (is (= (get-in frames [nil 0 :error :code]) -32001))
                  (is (= (count (get frames "runtime.shutdown")) 1))
                  (end! "late")
                  (is (= (get-in (next!) [:error :code]) -32001))
                  (deliver finish-write true)
                  (is (= (select-keys (next!) [:id :result :error]) {:id "file" :result nil}))
                  (reply! (first (get frames "runtime.shutdown")) {}))
                (do
                  (is (= (get-in request [:error :code]) -32001))
                  (end! "late")
                  (is (= (get-in (next!) [:error :code]) -32001))
                  (deliver finish-write true)
                  (is (= (select-keys (next!) [:id :result :error]) {:id "file" :result nil})))))
            (is (true? (bounded stopped)))
            (is (= @calls 1))
            (is (= (get @store "/journal") "saved"))
            (when whole-client? (is (= (ex-message (bounded closing)) "Session detach deadline expired")))
            (finally (deliver finish-hook true) (deliver finish-write true))))))))

(deftest late-release-fences-the-generation-before-the-closing-caller-continues
  (doseq [custom-storage? [false true]]
    (with-runtime (if custom-storage? fs-options {})
      (fn [c send! next! reply!]
        (let [entered (promise) deliver-idle (promise) fenced (promise) finish-close (promise)
              opening (try-open c (cond-> {:session-id "notes" :github {:token-provider (constantly {:kind :cancelled})}
                                           :events {:on-event (fn [_] (deliver entered true) (bounded deliver-idle))}}
                                    custom-storage? (assoc :state {:create-session-fs-provider (memory-fs/provider (atom {}))})))
              create (next!)
              _ (reply! create {:sessionId "notes" :capabilities {:ui {:elicitation true}}})
              s (bounded opening)
              form (sdk/elicit! s {:message "Fixture?" :requested-schema {:type "object"}})
              ask (next!)
              turn (sdk/send-and-wait! s "pending") request (next!)
              _ (send! {:method "session.event" :params {:sessionId "notes" :event {:type "session.idle" :id "idle"}}})
              _ (reply! request {:messageId "m" :aborted false :completionEventId "idle"})
              _ (bounded entered)
              deadline (a/chan) clock a/timeout routes (.-sessions c)
              [closing detach] (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) deadline (clock ms)))]
                                 (let [closing (future (try (sdk/close!! s) (catch Exception e e)))]
                                   [closing (next!)]))]
          (add-watch routes fenced
                     (fn [_ _ old new]
                       (when (and (identical? s (get old "notes")) (= ::client/unconfirmed-cleanup (get new "notes")))
                         (deliver fenced true)
                         (bounded finish-close))))
          (try
            (a/close! deadline)
            (bounded fenced)
            (reply! detach {:success true})
            (reply! ask {:action "accept"})
            (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "refresh"))
            (is (= (get-in (next!) [:error :code]) -32001))
            (is (= (-> (bounded (future (a/<!! form))) :error ex-data :type) :session-closed))
            (deliver deliver-idle true)
            (is (= (-> (bounded (future (a/<!! turn))) :error ex-data :type) :session-closed))
            (deliver finish-close true)
            (is (= (ex-message (bounded closing)) "Request timeout"))
            (finally (deliver deliver-idle true) (deliver finish-close true) (remove-watch routes fenced))))))))

(deftest a-definitive-open-refusal-fences-the-next-storage-call-before-rollback
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [store (atom {"/" nil})
            opts {:session-id "notes" :state {:create-session-fs-provider (memory-fs/provider store)}}
            opening (try-open c opts)
            create (next!)
            write! (fn [value]
                     (send! {:id value :method "sessionFs.writeFile" :params {:sessionId "notes" :path "/note" :content value}})
                     (select-keys (next!) [:result :error]))]
        (is (= (write! "before") {:result nil}))
        (locking c
          (send! {:id (:id create) :error {:code -32000 :message "Refused"}})
          (is (= (get-in (write! "after") [:error :code]) -32001)))
        (is (= (get @store "/note") "before"))
        (is (= (ex-message (bounded opening)) "Refused"))
        (let [retry (try-open c opts)]
          (reply! (next!) {:sessionId "notes"})
          (is (= (sdk/session-id (bounded retry)) "notes")))))))

(deftest an-end-hook-is-not-admitted-before-open-finishes
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [calls (atom 0)
            opening (try-open c {:session-id "notes" :git {:coauthor-enabled? false}
                                 :hooks {:handlers {:on-session-end (fn [& _] (swap! calls inc) {:session-summary "fixture"})}}})]
        (let [create (next!)]
          (send! {:id "pre-create" :method "hooks.invoke" :params {:sessionId "notes" :hookType "sessionEnd" :input {:reason "error"}}})
          (is (= (get-in (next!) [:error :code]) -32001))
          (reply! create {:sessionId "notes"}))
        (let [patch (next!)]
          (is (= (:method patch) "session.options.update"))
          (send! {:id "early" :method "hooks.invoke" :params {:sessionId "notes" :hookType "sessionEnd" :input {:reason "error"}}})
          (is (= (get-in (next!) [:error :code]) -32001))
          (reply! patch {:success false}))
        (let [rollback (next!)]
          (is (= (:method rollback) "session.detach"))
          (reply! rollback {:success true}))
        (is (= (ex-message (bounded opening)) "Runtime rejected the session options patch"))
        (is (zero? @calls))))))

(deftest releasing-a-provider-cannot-run-application-close-on-the-reader-or-under-the-registry
  (with-runtime fs-options
    (fn [c send! next! reply!]
      (let [started (promise) interrupting (promise) release (java.util.concurrent.CompletableFuture.)
            registry (:registry (.-reverseOwner c))
            input (proxy [java.io.InputStream] []
                    (read ([_ _ _] (deliver started true) (.join release) -1))
                    (close [] (deliver interrupting [(.getName (Thread/currentThread)) (Thread/holdsLock registry)])
                      (.join release)))
            provider (assoc (memory-fs/provider (atom {"/" nil})) :read-file
                            (fn [_]
                              (with-open [channel (java.nio.channels.Channels/newChannel input)]
                                (.read channel (java.nio.ByteBuffer/allocate 8)))
                              "done"))
            opening (try-open c {:session-id "notes" :state {:create-session-fs-provider provider}})
            _ (reply! (next!) {:sessionId "notes"})
            s (bounded opening)
            ping (sdk/ping c)
            ping-request (next!)
            closing (future (sdk/close!! s))
            detach (next!)]
        (try
          (send! {:id "read" :method "sessionFs.readFile" :params {:sessionId "notes" :path "/note"}})
          (bounded started)
          (reply! detach {:success true})
          (reply! ping-request {:message "ok"})
          (let [[thread locked?] (bounded interrupting)]
            (is (not= thread "jsonrpc-nio-reader"))
            (is (false? locked?))
            (is (= (deref (future (sdk/await!! ping)) 1500 ::blocked) {:message "ok"})))
          (finally (.complete release true)))
        (is (nil? (bounded closing)))
        (is (= (get-in (next!) [:error :code]) -32001))))))

(defn- gated-read [entered interrupted release]
  (let [stream (proxy [java.io.InputStream] []
                 (read ([_ _ _] (deliver entered true) (.join ^java.util.concurrent.CompletableFuture release) -1))
                 (close [] (deliver interrupted (Thread/currentThread)) (.join ^java.util.concurrent.CompletableFuture release)))]
    (with-open [channel (java.nio.channels.Channels/newChannel stream)]
      (.read channel (java.nio.ByteBuffer/allocate 1)))))

(deftest a-cancelled-shell-prompt-replies-before-application-io-finishes-closing
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [entered (promise) interrupted (promise) release (java.util.concurrent.CompletableFuture.)
            handler (fn [& _] (gated-read entered interrupted release) {:action :accept :value "yes"})
            opening (try-open c {:session-id "shell" :interaction {:on-shell-authentication-request handler}})
            params {:sessionId "shell" :requestId "confirm"}]
        (try
          (reply! (next!) {:sessionId "shell"})
          (bounded opening)
          (send! {:id "ask" :method "shellAuthentication.request"
                  :params (assoc params :program "fixture" :prompt "Continue?" :inputKind "confirm")})
          (bounded entered)
          (send! {:id "cancel" :method "shellAuthentication.cancel" :params params})
          (bounded interrupted)
          ;; Both frames must arrive while the application's close is still blocked.
          (is (= (into {} (map (juxt :id :result)) [(next!) (next!)])
                 {"cancel" {} "ask" {:action "cancel"}}))
          (finally (.complete release true)))))))

(deftest a-completed-decision-cannot-block-another-sessions-observer
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [entered (promise) interrupted (promise) release (java.util.concurrent.CompletableFuture.) seen (promise)
            handler (fn [& _] (gated-read entered interrupted release) {:kind :reject})
            open! (fn [id opts]
                    (let [opening (try-open c (assoc opts :session-id id))]
                      (reply! (next!) {:sessionId id}) (bounded opening)))
            emit! (fn [id type data] (send! {:method "session.event" :params {:sessionId id :event {:type type :data data}}}))]
        (try
          (open! "decision" {:permissions {:on-request handler}})
          (open! "observer" {:events {:on-event #(deliver seen (:type %))}})
          (emit! "decision" "permission.requested" {:requestId "one" :permissionRequest {:kind "read"}})
          (bounded entered)
          (emit! "decision" "permission.completed" {:requestId "one"})
          (bounded interrupted)
          (emit! "observer" "session.info" {})
          (is (= (deref seen 1500 ::blocked) :copilot/session.info))
          (finally (.complete release true)))
        (assert-no-queued-request! c next! reply!)))))

(deftest a-provider-deadline-does-not-wait-for-application-stream-close
  (let [entered (promise) interrupted (promise) release (java.util.concurrent.CompletableFuture.)]
    (with-runtime {:models {:on-list (fn [] (gated-read entered interrupted release) [])}}
      (fn [c _ _ _]
        (let [deadline (a/chan) timer a/timeout
              first (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) deadline (timer ms)))]
                      (sdk/list-models c))
              second (sdk/list-models c)]
          (try
            (bounded entered)
            (a/close! deadline)
            (bounded interrupted)
            (doseq [ch [first second]]
              (let [v (deref (future (a/<!! ch)) 1500 ::blocked)]
                (is (= (some-> v :error ex-data :type) :provider-timeout))))
            (finally (.complete release true))))))))

(deftest unobserved-provider-interruption-reports-a-fatal-stream-error
  (let [reading (promise) uncaught (promise) release (java.util.concurrent.CompletableFuture.)
        failure (LinkageError. "Fixture stream close failed")
        stream (proxy [java.io.InputStream] []
                 (read ([_ _ _] (deliver reading true) (.join release) -1))
                 (close []
                   (.setUncaughtExceptionHandler (Thread/currentThread)
                                                 (reify Thread$UncaughtExceptionHandler
                                                   (uncaughtException [_ _ t] (deliver uncaught t))))
                   (throw failure)))
        list! (fn []
                (with-open [ch (java.nio.channels.Channels/newChannel stream)]
                  (.read ch (java.nio.ByteBuffer/allocate 1)))
                [])]
    (with-runtime {:models {:on-list list!}}
      (fn [c _ _ _]
        (try
          (sdk/list-models c)
          (bounded reading)
          (sdk/force-close!! c)
          (is (identical? failure (deref uncaught 3000 nil)))
          (finally (.complete release true)))))))

(deftest session-callback-workers-do-not-inherit-an-openers-context
  (let [context (InheritableThreadLocal.)]
    (with-runtime fs-options
      (fn [c send! next! reply!]
        (doseq [i (range 5)]
          (let [id (str "notes-" i)
                seen (promise) observed (promise)
                store (atom {"/" nil})
                opts {:session-id id
                      :state {:create-session-fs-provider (fn [_] (deliver seen (.get context)) (memory-fs/provider store))}
                      :events {:on-event (fn [_] (deliver observed (.get context)))}}
                open (future
                       (.set context id)
                       (try (sdk/open-session!! c opts) (finally (.remove context))))]
            (reply! (next!) {:sessionId id})
            (let [s (bounded open)]
              (send! {:method "session.event" :params {:sessionId id :event {:type "session.info" :id "test" :data {}}}})
              (is (nil? (bounded seen)))
              (is (nil? (bounded observed)))
              (send! {:id "write" :method "sessionFs.writeFile" :params {:sessionId id :path "/note" :content id}})
              (is (= (select-keys (next!) [:result :error]) {:result nil}))
              (is (= (get @store "/note") id))
              (let [close (future (sdk/close!! s))]
                (reply! (next!) {:success true})
                (bounded close)))))))))

(deftest retired-busy-decision-does-not-discard-an-accepted-observation
  (doseq [method ["session.detach" "session.delete" :eof]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [entered (java.util.concurrent.CountDownLatch. 2)
              hold (a/chan)
              observed (atom [])
              observed-last (promise)
              opening (try-open c {:session-id "busy"
                                   :permissions {:on-request (fn [_ _] (.countDown entered) hold)}
                                   :events {:on-event (fn [{:keys [id]}]
                                                        (swap! observed conj id)
                                                        (when (= id "last") (deliver observed-last true)))}})
              _ (reply! (next!) {:sessionId "busy"})
              s (bounded opening)
              events (sdk/subscribe s)
              staged (promise)
              resume (promise)
              convert util/clj->wire
              offer a/offer!
              offered (promise)
              permission! (fn [id] (send! {:method "session.event"
                                           :params {:sessionId "busy" :event {:id id :type "permission.requested"
                                                                              :data {:requestId id :permissionRequest {:kind "read"}}}}}))
              info! #(send! {:method "session.event" :params {:sessionId "busy" :event {:id % :type "session.info" :data {}}}})
              expected (into (mapv str (range 18)) ["overflow" "last"])]
          (try
            (with-redefs [util/clj->wire
                          (fn [v]
                            (when (= "overflow" (:request-id v))
                              (deliver staged true) (bounded resume))
                            (convert v))
                          a/offer!
                          (fn [ch frame]
                            (when (and (= "overflow" (get-in frame [:params :requestId]))
                                       (identical? ch (:outgoing-ch (.-connection c))))
                              (deliver offered true))
                            (offer ch frame))]
              (doseq [i (range 2)] (permission! (str i)))
              (is (.await entered 3 java.util.concurrent.TimeUnit/SECONDS))
              (doseq [i (range 2 18)] (permission! (str i)))
              (permission! "overflow")
              (bounded staged)
              (if (= :eof method)
                (let [lost (promise)
                      state (:state-atom (.-connection c))]
                  (add-watch state lost (fn [_ _ _ v] (when-not (get-in v [:connection :running?]) (deliver lost true))))
                  (try
                    (info! "last")
                    (send!)
                    (bounded lost)
                    (deliver resume true)
                    (finally (remove-watch state lost))))
                (let [closing (future (if (= method "session.detach") (sdk/close!! s) (sdk/delete-session!! c "busy")))
                      frame (next-release! next! reply! method "busy")]
                  (is (= method (:method frame)))
                  (info! "last")
                  (reply! frame {:success true})
                  (info! "too-late")
                  (send! {:id "reader-fence" :method "unknown" :params {}})
                  (is (= "reader-fence" (:id (next!))))
                  (deliver resume true)
                  (is (nil? (bounded closing)))))
              (let [received (bounded (future (a/<!! (a/into [] events))))]
                (is (= (mapv :id (butlast received)) expected))
                (is (= (if (= :eof method) :connection-closed :session-closed) (-> received last :error ex-data :type))))
              (is (true? (bounded observed-last)))
              (is (= @observed expected))
              (is (not (realized? offered)))
              (when-not (= :eof method) (assert-no-queued-request! c next! reply!)))
            (finally (deliver resume true) (a/close! hold) (a/close! events))))))))

(deftest a-failed-owner-cannot-submit-a-manual-answer-prepared-before-failure
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "s"})
            _ (reply! (next!) {:sessionId "s"})
            s (bounded opening)
            events (sdk/subscribe s)
            preparing (promise)
            resume (promise)
            convert util/clj->wire]
        (try
          (with-redefs-fn
            {#'callbacks/claim-limit 1
             #'util/clj->wire (fn [v] (when (= "manual" (:request-id v)) (deliver preparing true) (bounded resume))
                                (convert v))}
            (fn []
              (send! {:method "session.event" :params {:sessionId "s" :event {:type "permission.completed"
                                                                              :data {:requestId "already-settled"}}}})
              (is (= :copilot/permission.completed (:type (bounded (future (a/<!! events))))))
              (let [responding (future (try (sdk/respond!! s :tool {:request-id "manual" :result "old"})
                                            (catch Exception e e)))]
                (bounded preparing)
                (send! {:method "session.event" :params {:sessionId "s" :event {:type "permission.requested"
                                                                                :data {:requestId "full"
                                                                                       :permissionRequest {:kind "read"}}}}})
                (let [failure (:error (bounded (future (a/<!! events))))]
                  (is (= :callback-claim-limit (:type (ex-data failure))))
                  ;; Remove the failed registration before the prepared answer can submit.
                  (let [closing (future (sdk/close!! s)) detach (next!)]
                    (is (= (:method detach) "session.detach"))
                    (reply! detach {:success true})
                    (bounded closing))
                  (deliver resume true)
                  (is (identical? failure (bounded responding))))
                (let [ping (sdk/ping c "fence")
                      frames (loop [seen []]
                               (let [frame (next!)]
                                 (if (= "ping" (:method frame))
                                   (do (reply! frame {:message "pong"}) seen)
                                   (do (reply! frame {:success true}) (recur (conj seen (:method frame)))))))]
                  (is (empty? frames))
                  (is (= "pong" (:message (sdk/await!! ping))))))))
          (finally (deliver resume true) (a/close! events)))))))

(deftest release-ack-fences-the-exact-generation-before-the-next-frame
  (doseq [method ["session.detach" "session.delete"]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [entered (promise)
              answer (a/promise-chan)
              callbacks (atom [])
              open! (fn [generation]
                      (let [opening (try-open c {:session-id "named"
                                                 :hooks {:handlers {:on-agent-stop (fn [& _]
                                                                                     (swap! callbacks conj generation)
                                                                                     {:decision :block})}}
                                                 :github {:token-provider (fn [_] (swap! callbacks conj generation)
                                                                            {:kind :cancelled})}
                                                 :permissions {:on-request (fn [_ ctx]
                                                                             (deliver entered (:cancelled? ctx)) answer)}})
                            create (next!)]
                        (reply! create {:sessionId "named" :capabilities {:ui {:elicitation true}}})
                        [(bounded opening) (get-in create [:params :github :tokenProviderRegistrationId])]))
              hook! #(do (send! {:id "hook" :method "hooks.invoke"
                                 :params {:sessionId "named" :hookType "agentStop" :input {}}}) (next!))
              token! #(do (send! (token-request % "refresh")) (next!))
              [s reg] (open! :old)
              events (sdk/subscribe s)
              lifecycle (sdk/lifecycle-events c)
              release! #(future (try (if (= method "session.detach") (sdk/close!! s) (sdk/delete-session!! c "named"))
                                     (catch Exception e e)))
              ask-form! #(sdk/elicit! s {:message "Continue?" :requested-schema {:type "object"}})
              retained (ask-form!) retained-form (next!)
              refused (release!)]
          (try
            (reply! (next-release! next! reply! method "named") {:success false})
            (is (instance? Exception (bounded refused)))
            (reply! retained-form {:action "accept"})
            (is (= (bounded (future (sdk/await!! retained))) {:action :accept}))
            (is (= (:result (hook!)) {:output {:decision "block"}}))
            (is (= (:result (token! reg)) {:kind "cancelled"}))
            (send! {:method "session.event" :params {:sessionId "named" :event {:type "permission.requested"
                                                                                :id "held" :data {:requestId "held"
                                                                                                  :permissionRequest {:kind "read"}}}}})
            (let [cancelled? (bounded entered)
                  _ (is (= (:id (bounded (future (a/<!! events)))) "held"))
                  pending (ask-form!) form (next!)
                  closing (release!)
                  request (next-release! next! reply! method "named")]
              (is (= (:method request) method))
              ;; The reader processes the ACK and every probe before caller-side teardown.
              (locking c
                (reply! request {:success true})
                (is (= (get-in (hook!) [:error :code]) -32001))
                (reply! form {:action "accept"})
                (is (= (-> (bounded (future (a/<!! pending))) :error ex-data :type) :session-closed))
                (is (= (get-in (token! reg) [:error :code]) -32001))
                (is (true? (cancelled?)))
                (send! {:method "session.event" :params {:sessionId "named" :event {:type "session.info" :id "late" :data {}}}})
                (send! {:method "session.lifecycle" :params {:sessionId "barrier" :type "created"}})
                (is (= (:session-id (bounded (future (a/<!! lifecycle)))) "barrier"))
                (is (nil? (a/poll! events)))
                (a/put! answer {:kind :reject}))
              (is (nil? (bounded closing))))
            (let [[_ replacement] (open! :new)]
              (is (= (:result (hook!)) {:output {:decision "block"}}))
              (is (= (:result (token! replacement)) {:kind "cancelled"}))
              (is (= (get-in (token! reg) [:error :code]) -32001))
              (is (= @callbacks [:old :old :new :new])))
            (finally (a/close! answer) (a/close! events) (a/close! lifecycle))))))))

(deftest an-ordinary-reverse-offer-is-ordered-with-the-release-ack
  (doseq [method ["session.detach" "session.delete"]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [open! (fn [answer]
                      (let [f (try-open c {:session-id "same"
                                           :interaction {:on-user-input-request
                                                         (constantly {:answer answer :was-freeform? true})}})]
                        (reply! (next!) {:sessionId "same"})
                        (bounded f)))
              ask! #(send! {:id % :method "userInput.request" :params {:sessionId "same" :question "Continue?"}})
              s (open! "old")
              conn (.-connection c)
              retired? (:token (.-registration ^github.copilot_sdk.native_client.Session s))
              ^ReentrantReadWriteLock barrier (:reply-barrier (.-reverseOwner c))
              offering (promise)
              resume (promise)
              offer a/offer!]
          (try
            (with-redefs [a/offer! (fn [ch msg]
                                     (when (and (identical? ch (:outgoing-ch conn)) (= (:id msg) "old-request"))
                                       (deliver offering true) (bounded resume))
                                     (offer ch msg))]
              (let [closing (future (if (= method "session.detach") (sdk/close!! s) (sdk/delete-session!! c "same")))
                    request (next-release! next! reply! method "same")
                    ^Thread reader (:read-thread conn)]
                (is (= (:method request) method))
                (ask! "old-request")
                (bounded offering)
                (reply! request {:success true})
                ;; The reader either contends at retirement or (regression)
                ;; retires a generation whose offer has not happened yet.
                (let [until (+ (System/nanoTime) 3000000000)]
                  (loop []
                    (when-not (or @retired? (.hasQueuedThread barrier reader) (>= (System/nanoTime) until))
                      (Thread/yield)
                      (recur)))
                  (is (.hasQueuedThread barrier reader))
                  (is (false? @retired?)))
                (deliver resume true)
                (is (= (select-keys (next!) [:id :result]) {:id "old-request" :result {:answer "old" :wasFreeform true}}))
                (is (nil? (bounded closing)))
                (is (true? @retired?))))
            (open! "new")
            (ask! "new-request")
            (is (= (select-keys (next!) [:id :result]) {:id "new-request" :result {:answer "new" :wasFreeform true}}))
            (finally (deliver resume true))))))))

(deftest broadcast-registration-and-queue-offer-are-one-admission
  (doseq [release [:client :detach :delete]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [context (promise)
              opening (try-open c {:session-id "s" :interaction
                                   {:on-elicitation-request (fn [ctx] (deliver context ctx) {:action :accept :content {}})}})
              _ (reply! (next!) {:sessionId "s"})
              s (bounded opening)
              answer-method "session.ui.handlePendingElicitation"
              conn (.-connection c)
              ^ReentrantReadWriteLock barrier (:reply-barrier (.-reverseOwner c))
              checked (promise) resume-check (promise)
              offering (promise) resume-offer (promise)
              sender rpc/send-request
              offer a/offer!]
          (try
            (with-redefs [rpc/send-request
                          (fn [conn m params & opts]
                            (if (= m answer-method)
                              (sender conn m params
                                      (update (first opts) :check-admission!
                                              (fn [check!] #(do (check!) (deliver checked true) (bounded resume-check)))))
                              (apply sender conn m params opts)))
                          a/offer! (fn [ch v]
                                     (when (and (identical? ch (:outgoing-ch conn)) (= answer-method (:method v)))
                                       (deliver offering true) (bounded resume-offer))
                                     (offer ch v))]
              (let [closing-session (case release
                                      :detach (future (sdk/close!! s))
                                      :delete (future (sdk/delete-session!! c "s"))
                                      nil)
                    pending (when closing-session (next-release! next! reply! (if (= release :delete) "session.delete" "session.detach") "s"))]
                (send! {:method "session.event" :params {:sessionId "s" :event {:type "elicitation.requested"
                                                                                :data {:requestId "form" :message "Choose"}}}})
                (bounded checked)
                (let [thread (promise)
                      closing (or closing-session (future (deliver thread (Thread/currentThread)) (sdk/close!! c)))
                      _ (when pending (reply! pending {:success true}))
                      ^Thread revoker (if pending (:read-thread conn) (bounded thread))
                      contending? #(if-not pending (.hasQueuedThread barrier revoker)
                                           (let [info (.getThreadInfo (java.lang.management.ManagementFactory/getThreadMXBean)
                                                                      (.getId revoker))]
                                             (= (some-> info .getLockInfo .getIdentityHashCode)
                                                (System/identityHashCode (:notification-queue conn)))))
                      cancelled? (:cancelled? (bounded context))
                      until (+ (System/nanoTime) 3000000000)]
                  ;; The real ownership check has passed; neither a new pending
                  ;; entry nor an outgoing frame exists while it is suspended.
                  (loop []
                    (when-not (or (cancelled?) (contending?) (>= (System/nanoTime) until))
                      (Thread/yield)
                      (recur)))
                  (is (contending?))
                  (is (false? (cancelled?)))
                  (deliver resume-check true)
                  (bounded offering)
                  (is (contending?))
                  (is (false? (cancelled?)))
                  (deliver resume-offer true)
                  (let [answer (next!)]
                    (is (= [(:method answer) (get-in answer [:params :result :action])] [answer-method "accept"]))
                    (reply! answer {:success true}))
                  (when (= release :client)
                    (doseq [m ["session.detach" "runtime.shutdown"]]
                      (let [frame (next!)] (is (= (:method frame) m)) (reply! frame {:success true}))))
                  (is (nil? (bounded closing))))))
            (finally (deliver resume-check true) (deliver resume-offer true))))))))

(deftest a-retired-user-input-result-cannot-enter-the-queue-after-reopen
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [open! (fn [answer]
                    (let [f (try-open c {:session-id "same" :interaction
                                         {:on-user-input-request (constantly {:answer answer :was-freeform? true})}})]
                      (reply! (next!) {:sessionId "same"})
                      (bounded f)))
            ask! #(send! {:id % :method "userInput.request" :params {:sessionId "same" :question "Continue?"}})
            old (open! "old")
            preparing (promise) resume (promise) convert util/clj->wire]
        (try
          (with-redefs [util/clj->wire (fn [v]
                                         (when (= (:answer v) "old") (deliver preparing true) (bounded resume))
                                         (convert v))]
            (ask! "old-request")
            (bounded preparing)
            (let [closing (future (sdk/close!! old))]
              (reply! (next!) {:success true})
              (bounded closing))
            (open! "new")
            (ask! "new-request")
            (is (= (get-in (next!) [:result :answer]) "new"))
            (deliver resume true)
            (let [r (next!)]
              (is (= [(:id r) (get-in r [:error :code])] ["old-request" -32001]))))
          (finally (deliver resume true)))))))

(deftest detach-ack-revokes-a-prepared-sqlite-error
  (with-runtime (assoc-in fs-options [:session-fs :capabilities] {:sqlite true})
    (fn [c send! next! reply!]
      (let [sqlite {:exists #(throw (ex-info "provider detail" {}))
                    :query (constantly nil) :transaction (constantly [])}
            provider (assoc (memory-fs/provider (atom {})) :sqlite sqlite)
            opening (try-open c {:session-id "s" :state {:create-session-fs-provider provider}})
            _ (reply! (next!) {:sessionId "s"})
            _ (bounded opening)
            ask! #(send! {:id % :method "sessionFs.sqliteExists" :params {:sessionId "s"}})
            closing (future (sdk/close!! c))
            detach (next!)
            preparing (promise) resume (promise) convert util/clj->wire]
        (is (= (:method detach) "session.detach"))
        (ask! "live")
        (is (= (:error (next!)) {:code -32603 :message "provider detail"}))
        (try
          (with-redefs [util/clj->wire (fn [v]
                                         (when (= (:message v) "provider detail")
                                           (deliver preparing true) (bounded resume))
                                         (convert v))]
            (ask! "retired")
            (bounded preparing)
            (reply! detach {:success true})
            (let [shutdown (next!)]
              (is (= (:method shutdown) "runtime.shutdown"))
              (deliver resume true)
              (is (= (select-keys (next!) [:id :error])
                     {:id "retired" :error {:code -32001 :message "Reverse registration is not active"}}))
              (reply! shutdown {})
              (is (nil? (bounded closing)))))
          (finally (deliver resume true)))))))

(deftest an-ack-during-reply-preparation-stops-both-callback-transports
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "named"
                                 :hooks {:handlers {:on-agent-stop (fn [& _] {:decision :block})}}
                                 :permissions {:on-request (fn [& _] {:kind :reject})}})
            _ (reply! (next!) {:sessionId "named"})
            s (bounded opening)
            closing (future (sdk/close!! s))
            detach (next!)
            preparing (java.util.concurrent.CountDownLatch. 2)
            resume (promise)
            convert util/clj->wire]
        (try
          ;; Hold the final wire conversion after both application handlers returned.
          (with-redefs [util/clj->wire (fn [v]
                                         (when (or (= (get-in v [:output :decision]) "block") (= (:request-id v) "held"))
                                           (.countDown preparing)
                                           (bounded resume))
                                         (convert v))]
            (send! {:id "hook" :method "hooks.invoke" :params {:sessionId "named" :hookType "agentStop" :input {}}})
            (send! {:method "session.event" :params {:sessionId "named" :event {:type "permission.requested"
                                                                                :data {:requestId "held"
                                                                                       :permissionRequest {:kind "read"}}}}})
            (is (.await preparing 3 java.util.concurrent.TimeUnit/SECONDS))
            (locking c
              (reply! detach {:success true})
              (send! {:id "reader-fence" :method "unknown" :params {}})
              (is (= (:id (next!)) "reader-fence"))
              (deliver resume true)
              (let [frame (next!)]
                (is (= [(:id frame) (get-in frame [:error :code])] ["hook" -32001])))))
          (is (nil? (bounded closing)))
          ;; The next outbound create cannot overtake an admitted permission response.
          (let [replacement (try-open c {:session-id "named"})
                create (next!)]
            (is (= (:method create) "session.create"))
            (reply! create {:sessionId "named"})
            (is (= (sdk/session-id (bounded replacement)) "named")))
          (finally (deliver resume true)))))))

(deftest a-drain-during-wire-conversion-preserves-only-the-cleanup-reply
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [opening (try-open c {:session-id "named"
                                 :permissions {:on-request (fn [& _] {:kind :reject})}
                                 :hooks {:handlers {:on-agent-stop (fn [& _] {:decision :block})
                                                    :on-session-end (fn [& _] {:session-summary "done"})}}})
            _ (reply! (next!) {:sessionId "named"})
            _ (bounded opening)
            preparing (java.util.concurrent.CountDownLatch. 2)
            resume (promise)
            permission-ready (promise)
            release-permission (promise)
            convert util/clj->wire]
        (try
          (with-redefs [util/clj->wire (fn [v]
                                         (when (:output v) (.countDown preparing) (bounded resume))
                                         (when (= "held" (:request-id v))
                                           (deliver permission-ready true)
                                           (bounded release-permission))
                                         (convert v))]
            (send! {:method "session.event" :params {:sessionId "named" :event {:type "permission.requested"
                                                                                :data {:requestId "held"
                                                                                       :permissionRequest {:kind "read"}}}}})
            (bounded permission-ready)
            (doseq [kind ["agentStop" "sessionEnd"]]
              (send! {:id kind :method "hooks.invoke" :params {:sessionId "named" :hookType kind :input {}}}))
            (is (.await preparing 3 java.util.concurrent.TimeUnit/SECONDS))
            (let [closing (future (sdk/close!! c))
                  detach (next!)]
              ;; Wire conversion holds neither the callback registry nor the
              ;; reply barrier: close can fence decisions and send detach.
              (is (= "session.detach" (:method detach)))
              (deliver resume true)
              (let [replies (into {} (map (juxt :id identity)) [(next!) (next!)])]
                (is (= (get-in replies ["agentStop" :error :code]) -32001))
                (is (= (get-in replies ["sessionEnd" :result]) {:output {:sessionSummary "done"}})))
              (deliver release-permission true)
              (reply! detach {:success true})
              (let [shutdown (next!)]
                (is (= "runtime.shutdown" (:method shutdown)))
                (reply! shutdown {}))
              (is (nil? (bounded closing)))))
          (finally
            (deliver resume true)
            (deliver release-permission true)))))))

(deftest force-abandons-a-pending-unassigned-create-without-reporting-graceful-success
  (with-runtime {}
    (fn [c _ next! _]
      (let [opening (try-open c {:cloud {}})
            create (next!)
            waiting (promise)
            timeout a/timeout]
        (is (= (:method create) "session.create"))
        (with-redefs [a/timeout (fn [^long ms] (when (= ms 10000) (deliver waiting true)) (timeout ms))]
          (let [closing (future (try (sdk/close!! c) (catch Exception e e)))]
            (bounded waiting)
            (is (nil? (bounded (future (sdk/force-close!! c)))))
            (is (instance? Exception (bounded opening)))
            (let [error (bounded closing)]
              ;; Either the join was abandoned or the failed create settled first.
              (is (or (= :graceful-close-interrupted (:type (ex-data error)))
                      (= "session.create" (:method (ex-data error))))))))))))

(deftest client-close-retires-callbacks-without-surrendering-the-session-id
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [entered (promise)
            answer (a/promise-chan)
            open! (fn [id config]
                    (let [f (try-open c (assoc config :session-id id))]
                      (reply! (next!) {:sessionId id})
                      (bounded f)))]
        (open! "a" {:permissions {:on-request (fn [_ ctx] (deliver entered (:cancelled? ctx)) answer)}})
        (open! "b" {})
        (send! {:method "session.event" :params {:sessionId "a" :event {:type "permission.requested"
                                                                        :data {:requestId "pending"
                                                                               :permissionRequest {:kind "read"}}}}})
        (let [cancelled? (bounded entered)
              opening (try-open c {:cloud {}})
              create (next!)
              closing (future (try (sdk/close!! c) (catch Exception e e)))
              detaches (into {} (map (juxt #(get-in % [:params :sessionId]) identity)) [(next!) (next!)])]
          (is (= (set (keys detaches)) #{"a" "b"}))
          (reply! (detaches "a") {:success true})
          ;; A response to this later frame proves the reader processed A's ACK.
          (send! {:id "barrier" :method "gitHubToken.getToken" :params {}})
          (is (= (:id (next!)) "barrier"))
          (is (true? (cancelled?)))
          (a/put! answer {:kind :reject})
          (reply! create {:sessionId "a"})
          (is (= (:type (ex-data (bounded opening))) :session-already-open))
          (reply! (detaches "b") {:success false})
          (let [shutdown (next!)]
            (is (= (:method shutdown) "runtime.shutdown"))
            (reply! shutdown {}))
          (is (= (ex-data (bounded closing)) {:session-id "b"})))))))

(deftest a-detach-joined-by-client-close-reserves-its-id-until-opens-settle
  (with-runtime {}
    (fn [c _ next! reply!]
      (let [live (try-open c {:session-id "live"})
            _ (reply! (next!) {:sessionId "live"})
            held (try-open c {:session-id "held"})
            _ (reply! (next!) {:sessionId "held"})
            _ (bounded held)
            opening (try-open c {:cloud {}})
            create (next!)
            detaching (future (sdk/close!! (bounded live)))
            detach-live (next!)
            closing (future (sdk/close!! c))
            detach-held (next!)]
        (is (= (mapv :params [detach-live detach-held]) [{:sessionId "live"} {:sessionId "held"}]))
        (reply! detach-live {:success true})
        (is (nil? (bounded detaching)))
        (reply! create {:sessionId "live"})
        (is (= (-> (bounded opening) ex-data :type) :session-already-open))
        (reply! detach-held {:success true})
        (let [shutdown (next!)]
          (is (= (:method shutdown) "runtime.shutdown"))
          (reply! shutdown {}))
        (is (nil? (bounded closing)))))))

(deftest force-close-escalates-through-one-native-release
  (doseq [phase [:detach :shutdown :acknowledged]]
    (let [entered (promise)
          proceed (promise)
          releases (atom 0)
          failure (ex-info "Native close outcome unknown" {:phase phase})
          close-result (fn [f c] (future (try (f c) (catch Exception e e))))]
      (with-runtime {}
        (fn [c _ next! reply!]
          (let [pending (when (= phase :detach)
                          (let [s (try-open c {:session-id "held"})]
                            (reply! (next!) {:sessionId "held"})
                            (let [abort (sdk/abort! (bounded s))]
                              (is (= (:method (next!)) "session.abort"))
                              abort)))
                graceful (close-result sdk/close!! c)
                waiting (next!)]
            (is (= (:method waiting) (if (= phase :detach) "session.detach" "runtime.shutdown")))
            (when (= phase :acknowledged)
              (reply! waiting {})
              (bounded entered))
            (let [forced (close-result sdk/force-close!! c)]
              (try
                (is (true? (bounded entered)))
                (is (not (realized? forced)))
                (is (not (realized? graceful)))
                (finally (deliver proceed true)))
              (let [outcome (bounded forced)]
                (if (= phase :shutdown)
                  (is (identical? failure outcome))
                  (is (nil? outcome))))
              (let [outcome (bounded graceful)]
                (if (= phase :acknowledged)
                  (is (nil? outcome))
                  (is (= "Client force closed" (ex-message outcome))))
                (when (= phase :shutdown) (is (= [failure] (vec (.getSuppressed ^Throwable outcome))))))
              (when pending (is (instance? Exception (:error (bounded (future (a/<!! pending)))))))
              (is (= @releases 1)))))
        (fn []
          (when (= 1 (swap! releases inc))
            (deliver entered true)
            (bounded proceed)
            (when (= phase :shutdown) (throw failure))))))))

(defn- caller-outcome [f]
  (let [result (try (f) (catch Exception e e))]
    [result (.isInterrupted (Thread/currentThread))]))

(deftest a-forced-owner-reports-release-to-force-callers-but-not-graceful-success
  (doseq [failure [nil (ex-info "Native release failed" {})]]
    (let [entered (promise)
          proceed (promise)
          attempts (atom 0)]
      (with-runtime {}
        (fn [c _ next! reply!]
          (let [opening (try-open c {:session-id "detaching"})
                _ (reply! (next!) {:sessionId "detaching"})
                detaching (future (caller-outcome #(sdk/close!! (bounded opening))))
                detach (next!)
                forced (future (caller-outcome #(sdk/force-close!! c)))]
            (is (= (:method detach) "session.detach"))
            (try
              (bounded entered)
              ;; The join timeout is allocated after the caller selects the active owner.
              (let [joined (java.util.concurrent.CountDownLatch. 2)
                    timer a/timeout]
                (with-redefs [a/timeout (fn [^long ms] (when (= ms 65000) (.countDown joined)) (timer ms))]
                  (let [graceful (future (caller-outcome #(sdk/close!! c)))
                        force-joiner (future (caller-outcome #(sdk/force-close!! c)))]
                    (is (.await joined 3 java.util.concurrent.TimeUnit/SECONDS))
                    (deliver proceed true)
                    (is (= (bounded forced) [failure false]))
                    (is (= (bounded force-joiner) [failure false]))
                    (let [[error interrupted?] (bounded graceful)]
                      (is (= (:type (ex-data error)) :graceful-close-interrupted))
                      (is (false? interrupted?))
                      (when failure (is (some #{failure} (some-> error .getSuppressed))))))))
              (is (= (ex-message (first (bounded detaching))) "Client force closed"))
              (is (= @attempts 1))
              (is (nil? (sdk/close!! c)))
              (is (nil? (sdk/force-close!! c)))
              (is (= @attempts (if failure 2 1)))
              (finally (deliver proceed true)))))
        (fn []
          (when (= 1 (swap! attempts inc))
            (deliver entered true)
            (bounded proceed)
            (when failure (throw failure))))))))

(deftest a-retained-session-drops-its-reverse-registration-after-force-close
  (doseq [escalate? [false true]]
    (with-runtime fs-options
      (fn [c _ next! reply!]
        (let [provider (memory-fs/provider (atom {"/" nil}))
              credential (fn [_] {:kind :cancelled})
              opening (try-open c {:session-id "retained" :github {:token-provider credential}
                                   :state {:create-session-fs-provider provider}})
              _ (reply! (next!) {:sessionId "retained"})
              s (bounded opening)
              lifecycle (.-lifecycle ^github.copilot_sdk.native_client.Session s)
              token (:reverse-token @lifecycle)]
          ;; Check the strong reference from the handle itself, not GC timing.
          (is (identical? (get-in token [:credential :provider]) credential))
          (is (identical? @(get-in token [:cleanup :provider]) provider))
          (let [graceful (when escalate? (future (caller-outcome #(sdk/close!! c))))]
            (when graceful (is (= (:method (next!)) "session.detach")))
            (is (nil? (sdk/force-close!! c)))
            (when graceful (is (instance? Exception (first (bounded graceful)))))
            (is (not (contains? @lifecycle :reverse-token)))
            (is (nil? (sdk/close!! s)))
            (is (not (contains? @lifecycle :reverse-token)))))))))

(deftest interrupting-a-detach-does-not-interrupt-the-remaining-close-steps
  (let [owner (promise)
        worker (promise)
        stop-worker (promise)
        worker-done (promise)
        released (promise)
        proceed (promise)]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [session (try-open c {:session-id "held" :interaction
                                   {:on-user-input-request
                                    (fn [& _]
                                      (deliver worker true)
                                      (try (bounded stop-worker)
                                           (catch InterruptedException _ (bounded stop-worker))
                                           (finally (deliver worker-done true)))
                                      {:answer "done" :was-freeform? true})}})
              _ (reply! (next!) {:sessionId "held"})
              _ (bounded session)
              _ (send! {:id "prompt" :method "userInput.request" :params {:sessionId "held" :question "ok?"}})
              _ (bounded worker)
              closing (future (deliver owner (Thread/currentThread)) (caller-outcome #(sdk/close!! c)))
              frame (fn [method]
                      (let [f (next!)
                            rpc (if (= "prompt" (:id f)) (next!) f)]
                        (is (= method (:method rpc)))
                        rpc))
              _ (frame "session.detach")]
          (try
            (.interrupt ^Thread (bounded owner))
            (let [shutdown (frame "runtime.shutdown")]
              (deliver stop-worker true)
              (reply! shutdown {}))
            (is (= [false true] (bounded released)) "Native release runs with a clean flag after the worker stops")
            ;; Each 65s join deadline is created after selecting the existing
            ;; owner. Hold its native release until all callers have joined.
            (let [joined (java.util.concurrent.CountDownLatch. 3)
                  impatient (promise)
                  timer a/timeout
                  [graceful forced interrupted-joiner]
                  (with-redefs [a/timeout (fn [^long ms]
                                            (when (= ms 65000) (.countDown joined))
                                            (timer ms))]
                    (let [g (future (caller-outcome #(sdk/close!! c)))
                          f (future (caller-outcome #(sdk/force-close!! c)))
                          i (future (deliver impatient (Thread/currentThread))
                                    (caller-outcome #(sdk/close!! c)))]
                      (is (.await joined 5 java.util.concurrent.TimeUnit/SECONDS))
                      [g f i]))]
              (.interrupt ^Thread (bounded impatient))
              (let [[failure interrupted?] (bounded interrupted-joiner)]
                (is (instance? InterruptedException failure))
                (is interrupted?))
              (deliver proceed true)
              (let [[failure interrupted?] (bounded closing)]
                (is (instance? InterruptedException failure))
                (is interrupted?))
              (let [[failure interrupted?] (bounded graceful)]
                (is (= :lifecycle-interrupted (:type (ex-data failure))))
                (is (seq (ex-message failure)))
                (is (false? interrupted?)))
              (is (= [nil false] (bounded forced))))
            (finally (deliver stop-worker true) (deliver proceed true)))))
      (fn []
        (deliver released [(.isInterrupted (Thread/currentThread)) (realized? worker-done)])
        (bounded proceed)))))

(deftest force-joiner-does-not-inherit-the-native-release-owners-interrupt
  (let [owner (promise)
        entered (promise)
        release (promise)
        attempts (atom 0)]
    (with-runtime {}
      (fn [c _ next! _]
        (let [graceful (future (deliver owner (Thread/currentThread)) (caller-outcome #(sdk/close!! c)))
              shutdown (next!)]
          (is (= (:method shutdown) "runtime.shutdown"))
          (let [forced (future (caller-outcome #(sdk/force-close!! c)))]
            (try
              (bounded entered)
              (.interrupt ^Thread (bounded owner))
              (let [[error interrupted?] (bounded graceful)]
                (is (= "Client force closed" (ex-message error)))
                (is interrupted?)
                (is (instance? InterruptedException (first (.getSuppressed error)))))
              (let [[error interrupted?] (bounded forced)]
                (is (= :lifecycle-interrupted (:type (ex-data error))))
                (is (false? interrupted?)))
              (finally (deliver release true))))))
      (fn []
        (when (= 1 (swap! attempts inc))
          (deliver entered true)
          (bounded release))))))

(deftest collision-preserves-the-live-handle-and-revokes-only-the-new-credential
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [id "already-open"
            github {:token-provider (constantly {:kind :cancelled})}
            first-open (try-open c {:session-id id :github github})
            explicit (next!)
            original-reg (get-in explicit [:params :github :tokenProviderRegistrationId])]
        (reply! explicit {:sessionId id})
        (let [original (bounded first-open)
              second-open (try-open c {:cloud {} :github github})
              create (next!)]
          (reply! create {:sessionId id})
          (is (= (:type (ex-data (bounded second-open))) :session-already-open))
          (let [aborted (sdk/abort! original)
                abort (next!)]
            (is (= [(:method abort) (:params abort)] ["session.abort" {:sessionId id}]))
            (reply! abort {:success true})
            (is (nil? (sdk/await!! aborted))))
          (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "refresh"))
          (is (= -32001 (get-in (next!) [:error :code])))
          (send! (token-request original-reg "refresh"))
          (is (= {:kind "cancelled"} (:result (next!)))))))))

(deftest a-confirmed-rollback-releases-the-id-before-the-old-caller-finishes
  (doseq [kind [:cloud-delete :named-detach :case-mismatch]
          boundary (if (= kind :cloud-delete) [:ack :notice-before-ack :notice-then-refusal] [:ack])]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [open (if (= kind :case-mismatch)
                     (future (try (sdk/resume-session!! c "Reuse" {}) (catch Exception e e)))
                     (try-open c (merge {:git {:coauthor-enabled? false}}
                                        (if (= kind :cloud-delete) {:cloud {}} {:session-id "reuse"}))))
              _ (reply! (next!) {:sessionId "reuse"})
              _ (when-not (= kind :case-mismatch) (reply! (next!) {:success false}))
              release (next!) method (:method release)
              parked (promise) finish (promise) seen (atom []) observed (promise)
              releasing rpc/response-result!
              emit! #(send! {:method "session.event" :params {:sessionId "reuse" :event {:id % :type "session.info"}}})
              replacement (try-open c {:cloud {} :events {:on-event (fn [{:keys [id]}]
                                                                      (swap! seen conj id)
                                                                      (when (= id "live") (deliver observed @seen)))}})
              create (next!)]
          (is (= method (if (= kind :cloud-delete) "session.delete" "session.detach")))
          (try
            (with-redefs [rpc/response-result! (fn [m response]
                                                 (when (= m method) (deliver parked true) (bounded finish))
                                                 (releasing m response))]
              (emit! "old")
              (if (= boundary :ack)
                (do (reply! release {:success true}) (bounded parked))
                (send! {:method "session.lifecycle" :params {:sessionId "reuse" :type "session.deleted"}}))
              (send! {:method "session.lifecycle" :params {:sessionId "reuse" :type "session.created"}})
              (emit! "early")
              (reply! create {:sessionId "reuse"})
              (let [s (bounded replacement)]
                (is (client/session? s) [kind boundary])
                (when (client/session? s)
                  (emit! "live")
                  (is (= (bounded observed) ["early" "live"]) [kind boundary])))
              (when-not (= boundary :ack) (reply! release {:success (= boundary :notice-before-ack) :error "refused"}))
              (deliver finish true)
              (let [error (bounded open)]
                (if (= kind :case-mismatch)
                  (is (= (:type (ex-data error)) :session-id-mismatch))
                  (is (= (ex-message error) "Runtime rejected the session options patch")))
                (is (= (mapv #(-> % ex-data :reason) (.getSuppressed error))
                       (if (= boundary :notice-then-refusal) ["refused"] [])))))
            (finally (deliver finish true))))))))

(deftest rollback-deletes-only-the-acknowledged-cloud-id-and-preserves-both-errors
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [id "new-cloud"
            calls (atom [])
            opts {:cloud {} :git {:coauthor-enabled? false}
                  :tools {:definitions [{:name "test-tool" :handler (fn [& _] (swap! calls conj :tool) "ok")}]}
                  :github {:token-provider (constantly {:kind :cancelled})}}
            opening (try-open c opts)
            create (next!)]
        (reply! create {:sessionId id})
        (let [patch (next!)]
          (is (= [(:method patch) (get-in patch [:params :sessionId])] ["session.options.update" id]))
          (reply! patch {:success false}))
        (let [delete (next!)]
          (is (= [(:method delete) (:params delete)] ["session.delete" {:sessionId id}]))
          (is (= (:type (ex-data (bounded (try-open c {:session-id id})))) :session-already-open))
          (let [events (sdk/lifecycle-events c)]
            (try
              (send! {:method "session.event"
                      :params {:sessionId id :event {:type "external_tool.requested"
                                                     :data {:requestId "late" :toolCallId "late" :toolName "test-tool"
                                                            :arguments {}}}}})
              (send! {:method "session.lifecycle" :params {:type "created" :sessionId "notification-fence"}})
              (is (= "notification-fence" (:session-id (first (a/alts!! [events (a/timeout 5000)])))))
              (is (empty? @calls))
              (finally (a/close! events))))
          (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "refresh"))
          (is (= -32001 (get-in (next!) [:error :code])))
          (send! {:id (:id delete) :error {:code -32000 :message "Deletion failed"}}))
        (let [t (bounded opening)]
          (is (= (ex-message t) "Runtime rejected the session options patch"))
          (is (= (mapv ex-message (.getSuppressed t)) ["Deletion failed"])))))))

(deftest refused-compensation-reserves-the-id-before-replacement-callbacks-can-bind
  (doseq [cloud? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [id "acquired"
              opts {:interaction {:auto-mode-recovery? true :on-auto-mode-switch-request (constantly {:response :no})}}
              opening (try-open c (merge opts (if cloud? {:cloud {}} {:session-id id})))
              _ (reply! (next!) {:sessionId id})
              release (next!)]
          (is (= [(:method release) (:params release)] [(if cloud? "session.delete" "session.detach") {:sessionId id}]))
          (if cloud?
            (send! {:id (:id release) :error {:code -32000 :message "Deletion refused"}})
            (reply! release {:success false :error "Storage is read-only"}))
          (let [failure (bounded opening)]
            (is (= {:type :unsupported-auto-mode-recovery :session-id id} (ex-data failure)))
            (is (= [(if cloud? "Deletion refused" "Runtime did not detach the session")]
                   (mapv ex-message (.getSuppressed failure))))
            (when-not cloud?
              (is (= (-> (.getSuppressed failure) first ex-data :reason) "Storage is read-only"))))
          (let [calls (atom [])
                handler {:hooks {:handlers {:on-agent-stop (fn [_ ctx] (swap! calls conj (:session-id ctx)) nil)}}}
                replacement (future (try (sdk/resume-session!! c id handler) (catch Exception e e)))]
            (is (= {:type :session-cleanup-unconfirmed :session-id id} (ex-data (bounded replacement))))
            (let [other (try-open c (assoc handler :session-id "other"))]
              (reply! (next!) {:sessionId "other"})
              (is (= "other" (sdk/session-id (bounded other)))))
            (doseq [sid [id "other"]]
              (send! {:id sid :method "hooks.invoke" :params {:sessionId sid :hookType "agentStop" :input {}}})
              (let [response (next!)]
                (is (= sid (:id response)))
                (if (= sid id)
                  (is (= -32001 (get-in response [:error :code])))
                  (is (contains? response :result)))))
            (is (= ["other"] @calls))))))))

(deftest interrupted-opening-fences-callbacks-before-compensating-the-bounded-reply
  (doseq [kind [:cloud :explicit]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [id "interrupted-session"
              calls (atom [])
              opts (merge (if (= kind :cloud) {:cloud {}} {:session-id id})
                          {:events {:on-event (fn [evt] (swap! calls conj (:id evt)))}
                           :github {:token-provider (constantly {:kind :cancelled})}
                           :interaction {:on-user-input-request
                                         (fn [& _] (swap! calls conj :reverse) {:answer "stale" :was-freeform? true})}})
              outcome (promise)
              thread (doto (Thread. #(try (deliver outcome (sdk/open-session!! c opts))
                                          (catch Throwable t
                                            (deliver outcome [t (.isInterrupted (Thread/currentThread))])))
                                    "interrupted-session-opener")
                       (.start))
              create (next!)
              reg (get-in create [:params :github :tokenProviderRegistrationId])]
          (.interrupt thread)
          ;; Synchronize on externally observable revocation rather than the
          ;; scheduling gap between Thread.interrupt and the opener waking up.
          (loop [until (+ (System/nanoTime) 5000000000)]
            (send! (token-request reg "refresh"))
            (when-not (:error (next!))
              (if (< (System/nanoTime) until) (recur until)
                  (throw (ex-info "Credential remained active after interruption" {})))))
          (reply! create {:sessionId id})
          (send! {:method "session.event" :params {:sessionId id :event {:type "session.info" :id "late" :data {}}}})
          (send! {:id "reverse" :method "userInput.request" :params {:sessionId id :question "late?"}})
          (let [responses (group-by :method [(next!) (next!)])
                method (if (= kind :cloud) "session.delete" "session.detach")
                cleanup (first (get responses method))]
            (is (= (:params cleanup) {:sessionId id}))
            (is (= -32001 (get-in responses [nil 0 :error :code])))
            (is (= :session-already-open
                   (:type (ex-data (bounded (try-open c {:session-id id}))))))
            (reply! cleanup {:success true}))
          (let [[t interrupted?] (bounded outcome)]
            (is (instance? InterruptedException t))
            (is (true? interrupted?)))
          (let [resume (future (sdk/resume-session!! c id {}))
                request (next!)]
            (is (= "session.resume" (:method request)))
            (reply! request {:sessionId id})
            (is (= id (sdk/session-id (bounded resume)))))
          (is (empty? @calls)))))))

(deftest graceful-close-owns-server-assigned-opens-before-the-create-reply
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [live (try-open c {:session-id "live"})
            _ (reply! (next!) {:sessionId "live"})
            _ (bounded live)
            first-open (try-open c {:cloud {}})
            first-create (next!)
            second-open (try-open c {:cloud {}})
            second-create (next!)
            closing (future (try (sdk/close!! c) (catch Exception e e)))
            detach (next!)]
        ;; The detach proves close has started, independent of opener scheduling.
        (is (= [(:method detach) (:params detach)] ["session.detach" {:sessionId "live"}]))
        (reply! detach {:success true})
        (reply! second-create {:sessionId "allocated-second"})
        (let [delete (next!)]
          (is (= [(:method delete) (:params delete)] ["session.delete" {:sessionId "allocated-second"}]))
          (reply! delete {:success true}))
        (is (= :client-closed (:type (ex-data (bounded second-open)))))
        (reply! first-create {:sessionId "allocated-first"})
        (let [release (next!)]
          (is (= [(:method release) (:params release)] ["session.delete" {:sessionId "allocated-first"}]))
          (send! {:id (:id release) :error {:code -32000 :message "Rollback refused"}}))
        (let [shutdown (next!)]
          (is (= (:method shutdown) "runtime.shutdown"))
          (reply! shutdown {}))
        (let [failure (bounded first-open)]
          (is (= ["Rollback refused"] (mapv ex-message (.getSuppressed failure))))
          (is (= "Rollback refused" (ex-message (bounded closing)))))))))

(deftest alternate-root-cloud-allocation-never-compensates-from-the-default-store
  (doseq [detached? [true false]]
    (with-runtime {:base-directory "/fixture-default"}
      (fn [c _ next! reply!]
        (let [id "root-collision"
              opening (try-open c {:cloud {} :config-directory "/fixture-other" :git {:coauthor-enabled? false}})]
          (reply! (next!) {:sessionId id})
          (let [update (next!)]
            (is (= (:method update) "session.options.update"))
            (reply! update {:success false}))
          (let [release (next!)]
            (is (= [(:method release) (:params release)] ["session.detach" {:sessionId id}]))
            (reply! release {:success detached? :error "Refused"}))
          (let [failure (bounded opening)]
            (is (= (ex-message failure) "Runtime rejected the session options patch"))
            (is (= (mapv ex-data (.getSuppressed failure))
                   (cond-> [{:type :session-cleanup-unconfirmed :session-id id :reason :session-delete-store-mismatch}]
                     (not detached?) (conj {:session-id id :reason "Refused"})))))
          (is (= (:type (ex-data (bounded (try-open c {:session-id id})))) :session-cleanup-unconfirmed))
          (assert-no-queued-request! c next! reply!))))))

(deftest interrupted-open-reports-only-resource-failures-to-graceful-close
  (doseq [allocated? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [live (try-open c {:session-id "live"})
              _ (reply! (next!) {:sessionId "live"})
              _ (bounded live)
              opener (promise)
              opening (future (deliver opener (Thread/currentThread))
                              (caller-outcome #(sdk/open-session!! c {:cloud {}})))
              create (next!)
              closing (future (caller-outcome #(sdk/close!! c)))
              detach (next!)]
          (is (= "session.detach" (:method detach)))
          (.interrupt ^Thread (bounded opener))
          (reply! detach {:success true})
          (if allocated?
            (do
              (reply! create {:sessionId "late"})
              (let [delete (next!)]
                (is (= [(:method delete) (:params delete)] ["session.delete" {:sessionId "late"}]))
                (send! {:id (:id delete) :error {:code -32000 :message "Deletion refused"}})))
            (send! {:id (:id create) :error {:code -32000 :message "Create refused"}}))
          (let [[failure interrupted?] (bounded opening)]
            (is (instance? InterruptedException failure))
            (is interrupted?)
            ;; Inline registration can observe closed admission before the
            ;; opener fences its callback. Both schedules still own the delete.
            (let [messages (mapv ex-message (.getSuppressed failure))]
              (is (if allocated?
                    (contains? #{["Deletion refused"] ["Client is closed" "Deletion refused"]} messages)
                    (= ["Create refused"] messages)))))
          (let [shutdown (next!)]
            (is (= "runtime.shutdown" (:method shutdown)))
            (if allocated?
              (send! {:id (:id shutdown) :error {:code -32000 :message "Shutdown refused"}})
              (reply! shutdown {})))
          (let [[failure interrupted?] (bounded closing)]
            (is (false? interrupted?))
            (if allocated?
              (do (is (= "Deletion refused" (ex-message failure)))
                  (is (= ["Shutdown refused"] (mapv ex-message (.getSuppressed failure))))
                  (let [opener-cleanup (last (.getSuppressed (first (bounded opening))))]
                    (is (empty? (.getSuppressed opener-cleanup)))))
              (is (nil? failure)))))))))

(deftest rejected-provider-setup-does-not-delete-application-owned-data
  (with-runtime fs-options
    (fn [c _send! next! reply!]
      (let [id "allocated"
            detail (Exception. "Factory detail")
            failure (doto (ex-info "Factory failed" {}) (.addSuppressed detail))
            opts {:state {:create-session-fs-provider (fn [_] (throw failure))}}
            opening (try-open c (assoc opts :cloud {}))]
        (reply! (next!) {:sessionId id})
        (let [release (next!)]
          (is (= [(:method release) (:params release)] ["session.detach" {:sessionId id}]))
          (reply! release {:success true}))
        (is (identical? failure (bounded opening)))
        (let [[initial cleanup :as failures] (.getSuppressed failure)]
          (is (= 2 (count failures)))
          (is (identical? initial detail))
          (is (= {:type :session-cleanup-unconfirmed :session-id id :reason :session-fs-delete-unsupported}
                 (ex-data cleanup))))
        (is (= :session-cleanup-unconfirmed (:type (ex-data (bounded (try-open c (assoc opts :session-id id)))))))
        (assert-no-queued-request! c next! reply!)))))

(deftest graceful-close-reports-an-unanswered-cloud-create-without-guessing-an-id
  (doseq [expires [:create :close]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [live (try-open c {:session-id "live"})
              _ (reply! (next!) {:sessionId "live"})
              _ (bounded live)
              create-deadline (a/chan)
              close-deadline (a/chan)
              timer a/timeout
              [opening closing]
              (with-redefs [a/timeout (fn [^long ms] (case ms 60000 create-deadline 10000 close-deadline (timer ms)))]
                (let [opening (try-open c {:cloud {}})
                      create (next!)
                      closing (future (try (sdk/close!! c) (catch Exception e e)))
                      detach (next!)]
                  (is (= (:method create) "session.create"))
                  (is (= (:method detach) "session.detach"))
                  (reply! detach {:success true})
                  [opening closing]))]
          ;; Deliver the detach reply before expiring the shared deadline.
          (send! {:id "fence" :method "gitHubToken.getToken" :params {}})
          (is (= "fence" (:id (next!))))
          (a/close! (if (= expires :create) create-deadline close-deadline))
          (let [shutdown (next!)]
            (is (= (:method shutdown) "runtime.shutdown"))
            (reply! shutdown {}))
          (let [failure (bounded closing)]
            (if (= expires :create)
              (do (is (= "Request timeout" (ex-message failure)))
                  (is (= "Request timeout" (ex-message (bounded opening)))))
              (do (is (= :lifecycle-timeout (:type (ex-data failure))))
                  (is (instance? Exception (bounded opening)))))))))))

(deftest a-create-response-after-its-deadline-cannot-register-or-delete-an-id
  (with-runtime {}
    (fn [c send! next! reply!]
      (let [deadline (a/chan)
            timeout a/timeout
            [create failure] (with-redefs [a/timeout (fn [^long ms] (if (= ms 60000) deadline (timeout ms)))]
                               (let [opening (try-open c {:cloud {}})
                                     create (next!)]
                                 (a/close! deadline)
                                 [create (bounded opening)]))]
        (is (= "Request timeout" (ex-message failure)))
        (reply! create {:sessionId "unconfirmed"})
        (let [events (sdk/lifecycle-events c)]
          (try
            (send! {:method "session.lifecycle" :params {:type "created" :sessionId "ingress-fence"}})
            (is (= "ingress-fence" (:session-id (first (a/alts!! [events (a/timeout 5000)])))))
            (finally (a/close! events))))
        (let [deleting (future (try (sdk/delete-session!! c "saved") (catch Exception e e)))]
          (is (= (get-in (ex-data (bounded deleting)) [:error :data :code]) "session_allocation_unresolved")))
        (let [resume (future (sdk/resume-session!! c "unconfirmed" {}))
              request (next!)]
          (is (= "session.resume" (:method request)))
          (reply! request {:sessionId "unconfirmed"})
          (is (= "unconfirmed" (sdk/session-id (bounded resume)))))))))

(deftest unidentified-allocation-cannot-seed-a-later-or-overlapping-cloud-open
  (doseq [overlap? [false true]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [expire (a/chan) timer a/timeout seen (atom []) called (atom []) live (promise)
              options {:cloud {} :permissions {:on-request (fn [_ ctx] (swap! called conj (:request-id ctx)) {:kind :no-result})}
                       :events {:on-event #(do (swap! seen conj (:id %))
                                               (when (= (:id %) "live") (deliver live true)))}}
              [first-open first-frame] (with-redefs [a/timeout (fn [^long ms] (if (= ms 60000) expire (timer ms)))]
                                         (let [op (try-open c {:cloud {}})] [op (next!)]))
              second-open (when overlap? (try-open c options))
              second-frame (when overlap? (next!))
              send-old! #(send! {:method "session.event" :params {:sessionId "same" :event {:type "permission.requested"
                                                                                            :id % :data {:requestId %
                                                                                                         :permissionRequest {:kind "read"}}}}})]
          (when overlap? (send-old! "before-timeout"))
          (a/close! expire)
          (is (= (ex-message (bounded first-open)) "Request timeout"))
          (let [op (or second-open (try-open c options))]
            (send-old! "late")
            (reply! first-frame {:sessionId "same"})
            (when second-frame (reply! second-frame {:sessionId "same"}))
            (let [result (bounded op)]
              (if overlap?
                (do (is (client/session? result)) (is (= "same" (sdk/session-id result))))
                (is (= "session_allocation_unresolved" (get-in (ex-data result) [:error :data :code]))))
              (when (client/session? result)
                (send! {:method "session.event" :params {:sessionId "same" :event {:id "live" :type "session.info"}}})
                (bounded live))
              (is (= @seen (if overlap? ["live"] [])))
              (is (empty? @called)))))))))

(deftest a-timed-out-cleanup-reserves-the-id-even-after-a-late-acknowledgement
  (with-runtime {}
    (fn [c _send! next! reply!]
      (let [deadline (a/chan)
            timeout a/timeout]
        (with-redefs [a/timeout (fn [^long ms] (if (= ms 10000) deadline (timeout ms)))]
          (let [opening (try-open c {:cloud {} :git {:coauthor-enabled? false}})
                create (next!)]
            (reply! create {:sessionId "uncertain"})
            (reply! (next!) {:success false})
            (let [cleanup (next!)]
              (is (= "session.delete" (:method cleanup)))
              (a/close! deadline)
              (let [failure (bounded opening)]
                (is (= (ex-message failure) "Runtime rejected the session options patch"))
                (is (= (mapv ex-message (.getSuppressed failure)) ["Request timeout"])))
              (reply! cleanup {:success true})
              (is (= :session-cleanup-unconfirmed (:type (ex-data (bounded (try-open c {:session-id "uncertain"}))))))
              (let [other (try-open c {:session-id "another-id"})]
                (reply! (next!) {:sessionId "another-id"})
                (is (= "another-id" (sdk/session-id (bounded other))))))))))))

(deftest unacknowledged-acquisition-fences-a-known-id-but-an-explicit-refusal-does-not
  (doseq [method ["session.create" "session.resume"]]
    (with-runtime {}
      (fn [c send! next! reply!]
        (let [seen (atom [])
              opts {:events {:on-event #(swap! seen conj (:id %))}}
              open #(future (try (if (= method "session.resume")
                                   (sdk/resume-session!! c % opts)
                                   (sdk/open-session!! c (assoc opts :session-id %)))
                                 (catch Exception e e)))
              id "known-id"
              deadline (a/chan)
              timer a/timeout
              request (with-redefs [a/timeout (fn [^long ms] (if (= ms 60000) deadline (timer ms)))]
                        (let [opening (open id)
                              request (next!)]
                          (is (= (:method request) method))
                          (a/close! deadline)
                          (is (= "Request timeout" (ex-message (bounded opening))))
                          request))
              replacement (open id)]
          (reply! request {:sessionId id})
          (send! {:method "session.event" :params {:sessionId id :event {:type "session.info" :id "late" :data {}}}})
          (is (= :session-cleanup-unconfirmed (:type (ex-data (bounded replacement)))))
          (let [refused (open "retryable")
                request (next!)]
            (send! {:id (:id request) :error {:code -32000 :message "Refused" :data {:timeoutMs 60000}}})
            (is (= "Refused" (ex-message (bounded refused)))))
          (let [retry (open "retryable")]
            (reply! (next!) {:sessionId "retryable"})
            (is (= "retryable" (sdk/session-id (bounded retry)))))
          (is (empty? @seen)))))))

(deftest cloud-rollback-never-sends-a-prefix-expanding-delete
  (with-runtime {}
    (fn [c _ next! reply!]
      (let [id "deadbeef"
            opening (try-open c {:cloud {} :git {:coauthor-enabled? false}})
            _ (reply! (next!) {:sessionId id})
            patch (next!)
            refused (try (sdk/delete-session!! c id) (catch Exception e e))]
        (is (= :ambiguous-delete-id (:type (ex-data refused))))
        (reply! patch {:success false})
        (let [failure (bounded opening)]
          (is (= "Runtime rejected the session options patch" (ex-message failure)))
          (is (= [{:type :session-cleanup-unconfirmed :session-id id :reason :ambiguous-delete-id}]
                 (mapv ex-data (.getSuppressed failure)))))
        (is (= :session-cleanup-unconfirmed (:type (ex-data (bounded (try-open c {:session-id id}))))))
        (assert-no-queued-request! c next! reply!)))))

(deftest graceful-close-waits-for-the-opening-cloud-sessions-compensation
  (with-runtime {}
    (fn [c _send! next! reply!]
      (let [live (try-open c {:session-id "live"})
            _ (reply! (next!) {:sessionId "live"})
            _ (bounded live)
            opening (try-open c {:cloud {} :git {:coauthor-enabled? false}})
            _ (reply! (next!) {:sessionId "allocated"})
            patch (next!)
            closing (future (sdk/close!! c))
            detach (next!)]
        (is (= (:method patch) "session.options.update"))
        (is (= [(:method detach) (:params detach)] ["session.detach" {:sessionId "live"}]))
        (reply! detach {:success true})
        (reply! patch {:success false})
        (let [release (next!)]
          (is (= [(:method release) (:params release)] ["session.delete" {:sessionId "allocated"}]))
          (reply! release {:success true}))
        (is (= "Runtime rejected the session options patch" (ex-message (bounded opening))))
        (let [shutdown (next!)]
          (is (= (:method shutdown) "runtime.shutdown"))
          (reply! shutdown {}))
        (is (nil? (bounded closing)))))))

(deftest graceful-close-cancels-filesystem-initialization-and-reports-a-retained-cloud-allocation
  (doseq [cloud? [false true]]
    (with-runtime fs-options
      (fn [c _send! next! reply!]
        (let [entered (promise)
              release (promise)
              opening (try-open c (assoc (if cloud? {:cloud {}} {:session-id "pre-create"})
                                         :state {:create-session-fs-provider
                                                 (fn [_] (deliver entered true) @release)}))]
          (try
            (when cloud? (reply! (next!) {:sessionId "allocated"}))
            (bounded entered)
            (let [closing (future (try (sdk/close!! c) (catch Exception e e)))]
              (when cloud?
                (let [release (next!)]
                  (is (= [(:method release) (:params release)] ["session.detach" {:sessionId "allocated"}]))
                  (reply! release {:success true})))
              (let [shutdown (next!)]
                (is (= (:method shutdown) "runtime.shutdown"))
                (reply! shutdown {}))
              (if cloud?
                (is (= {:type :session-cleanup-unconfirmed :session-id "allocated" :reason :session-fs-delete-unsupported}
                       (ex-data (bounded closing))))
                (is (nil? (bounded closing))))
              (is (instance? Exception (bounded opening))))
            (finally (deliver release nil))))))))
