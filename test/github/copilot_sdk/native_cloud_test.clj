(ns github.copilot-sdk.native-cloud-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-host :as host])
  (:import [java.io PipedInputStream PipedOutputStream]))

(defn- bounded [p]
  (let [v (deref p 5000 ::timeout)]
    (when (= ::timeout v) (throw (ex-info "Timed out waiting for fixture" {})))
    v))

(defn- with-runtime [opts f]
  (with-open [in (PipedInputStream. 65536) to-sdk (PipedOutputStream. in)
              from-sdk (PipedInputStream. 65536) out (PipedOutputStream. from-sdk)]
    (let [send! #(peer/write-framed! to-sdk (assoc % :jsonrpc "2.0"))
          next! #(bounded (future (peer/read-framed from-sdk)))
          reply! (fn [request result] (send! {:id (:id request) :result result}))]
      (with-redefs [host/open! (fn [_] {:in in :out out :configure-ui! (constantly nil) :close! (constantly nil)})]
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

(deftest rollback-deletes-only-the-acknowledged-cloud-id-and-preserves-both-errors
  (doseq [stage [:factory :patch]]
    (with-runtime (if (= stage :factory) fs-options {})
      (fn [c send! next! reply!]
        (let [id "new-cloud"
              failure (ex-info "Factory rejected session" {})
              calls (atom [])
              opts (case stage
                     :factory {:state {:create-session-fs-provider (fn [_] (throw failure))}}
                     :patch {:git {:coauthor-enabled? false}
                             :tools {:definitions [{:name "test-tool" :handler (fn [& _] (swap! calls conj :tool) "ok")}]}})
              opening (try-open c (assoc opts :cloud {} :github {:token-provider (constantly {:kind :cancelled})}))
              create (next!)]
          (reply! create {:sessionId id})
          (when (= stage :patch)
            (let [patch (next!)]
              (is (= [(:method patch) (get-in patch [:params :sessionId])] ["session.options.update" id]))
              (reply! patch {:success false})))
          (let [delete (next!)]
            (is (= [(:method delete) (:params delete)] ["session.delete" {:sessionId id}]))
            (when (= stage :patch)
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
                  (finally (a/close! events)))))
            (send! (token-request (get-in create [:params :github :tokenProviderRegistrationId]) "refresh"))
            (is (= -32001 (get-in (next!) [:error :code])))
            (send! {:id (:id delete) :error {:code -32000 :message "Deletion failed"}}))
          (let [t (bounded opening)]
            (is (if (= stage :factory) (identical? t failure)
                    (= (ex-message t) "Runtime rejected the session options patch")))
            (is (= (mapv ex-message (.getSuppressed t)) ["Deletion failed"]))))))))

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

(deftest shutdown-does-not-silently-orphan-a-late-cloud-acknowledgement
  (with-runtime {}
    (fn [c _send! next! reply!]
      (let [opening (try-open c {:cloud {}})
            create (next!)
            closing (future (sdk/close!! c))
            shutdown (next!)]
        (is (= "runtime.shutdown" (:method shutdown)))
        (reply! create {:sessionId "closing-cloud"})
        (let [cleanup (next!)]
          (is (= [(:method cleanup) (:params cleanup)] ["session.delete" {:sessionId "closing-cloud"}]))
          (reply! cleanup {:success true}))
        (is (= (:type (ex-data (bounded opening))) :client-closed))
        (reply! shutdown {})
        (is (nil? (bounded closing)))))))

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
        (let [resume (future (sdk/resume-session!! c "unconfirmed" {}))
              request (next!)]
          (is (= "session.resume" (:method request)))
          (reply! request {:sessionId "unconfirmed"})
          (is (= "unconfirmed" (sdk/session-id (bounded resume)))))))))

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

(deftest graceful-close-cancels-filesystem-initialization-without-losing-a-cloud-allocation
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
            (let [closing (future (sdk/close!! c))]
              (when cloud?
                (let [delete (next!)]
                  (is (= [(:method delete) (:params delete)] ["session.delete" {:sessionId "allocated"}]))
                  (reply! delete {:success true})))
              (let [shutdown (next!)]
                (is (= (:method shutdown) "runtime.shutdown"))
                (reply! shutdown {}))
              (is (nil? (bounded closing)))
              (is (instance? Exception (bounded opening))))
            (finally (deliver release nil))))))))
