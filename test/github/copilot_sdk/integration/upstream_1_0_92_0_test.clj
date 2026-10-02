(ns github.copilot-sdk.integration.upstream-1-0-92-0-test
  (:require [clojure.core.async :as async]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.integration.support :refer [await-event-type!]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs])
  (:import [java.nio.file Files]))

(defn- read-result! [channel]
  (let [[value port] (async/alts!! [channel (async/timeout 5000)])]
    (when-not (= port channel)
      (throw (ex-info "Timed out reading SDK result" {})))
    (when (instance? Throwable value)
      (throw value))
    value))

(defn- with-client [mode f]
  (let [home (Files/createTempDirectory "copilot-recovery-contract-"
                                        (make-array java.nio.file.attribute.FileAttribute 0))
        server (mock/create-mock-server)
        copilot-client (sdk/client {:auto-start? false :mode mode :copilot-home (str home)})]
    (mock/start-mock-server! server)
    (try
      (let [[in out] (mock/client-streams server)]
        (client/connect-with-streams! copilot-client in out))
      (f server copilot-client)
      (finally
        (try
          (is (= (sdk/stop! copilot-client) []))
          (finally
            (mock/stop-mock-server! server)
            (Files/delete home)))))))

(deftest recovery-option-is-resume-and-join-only
  (doseq [spec [::specs/resume-session-config ::specs/join-session-config]
          [value expected] [[::absent true] [false true] [true true]
                            [nil false] ["" false] [[] false] [{} false] [0 false]]]
    (is (= (s/valid? spec (cond-> {}
                            (not= value ::absent) (assoc :allow-transcript-recovery? value)))
           expected)
        (str spec " " (pr-str value))))
  (with-client
    :copilot-cli
    (fn [server copilot-client]
      (let [requests (atom [])]
        (mock/set-request-hook! server (fn [method _] (swap! requests conj method)))
        (doseq [value [false true nil]
                create [sdk/create-session sdk/<create-session]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (create copilot-client {:allow-transcript-recovery? value}))))
        (doseq [value [nil "" [] {} 0]
                resume [#(sdk/resume-session copilot-client "unused" %)
                        #(sdk/<resume-session copilot-client "unused" %)
                        sdk/join-session]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (resume {:allow-transcript-recovery? value}))))
        (is (= @requests []))))))

(deftest recovery-option-preserves-omission-and-booleans-through-every-builder
  (doseq [mode [:empty :copilot-cli]]
    (with-client
      mode
      (fn [server copilot-client]
        (doseq [operation [:blocking :channel :join]
                value [::absent false true]]
          (testing (str mode " " operation " " value)
            (let [requests (atom [])
                  config (cond-> {:on-permission-request sdk/approve-all
                                  :available-tools []
                                  :skip-custom-instructions false}
                           (not= value ::absent) (assoc :allow-transcript-recovery? value))
                  created (sdk/create-session copilot-client
                                              {:on-permission-request sdk/approve-all
                                               :available-tools []})
                  session-id (sdk/session-id created)]
              (sdk/disconnect! created)
              (mock/set-request-hook! server
                                      (fn [method params]
                                        (swap! requests conj [method params])))
              (let [resumed
                    (case operation
                      :blocking (sdk/resume-session copilot-client session-id config)
                      :channel (read-result! (sdk/<resume-session copilot-client session-id config))
                      :join
                      (:session
                       (with-redefs-fn
                         {#'client/foreground-session-id (constantly session-id)
                          #'client/client (constantly copilot-client)}
                         #(sdk/join-session config))))]
                (try
                  (is (= (mapv #(select-keys (second %) [:allowTranscriptRecovery])
                               (filter #(= (first %) "session.resume") @requests))
                         [(if (= value ::absent) {} {:allowTranscriptRecovery value})]))
                  (is (some #(= (first %) "session.options.update") @requests))
                  (doseq [[method params] @requests :when (not= method "session.resume")]
                    (is (not (contains? params :allowTranscriptRecovery)) method))
                  (finally
                    (sdk/disconnect! resumed)))))))))))

(deftest recovery-report-is-idiomatic-and-cleared-on-clean-resume
  (with-client
    :copilot-cli
    (fn [server copilot-client]
      (let [created (sdk/create-session copilot-client {})
            session-id (sdk/session-id created)]
        (is (nil? (sdk/transcript-recovery created)))
        (sdk/disconnect! created)
        (doseq [resume [sdk/resume-session
                        #(read-result! (sdk/<resume-session %1 %2 %3))]
                [lines moved?] [[[2 7] false] [[] true]]]
          (mock/set-resume-response-extras!
           server {:transcriptRecovery {:plannedBackupPath "events.jsonl.backup-before-recovery"
                                        :invalidLineNumbers lines
                                        :sessionStartMoved moved?}})
          (let [resumed (resume copilot-client session-id {})]
            (try
              (is (= (sdk/transcript-recovery resumed)
                     {:planned-backup-path "events.jsonl.backup-before-recovery"
                      :invalid-line-numbers lines
                      :session-start-moved? moved?}))
              (is (s/valid? ::specs/transcript-recovery (sdk/transcript-recovery resumed)))
              (finally
                (sdk/disconnect! resumed))))
          (mock/set-resume-response-extras! server {})
          (let [resumed (resume copilot-client session-id {})]
            (try
              (is (nil? (sdk/transcript-recovery resumed)))
              (finally
                (sdk/disconnect! resumed)))))))))

(deftest connected-mcp-provenance-survives-live-and-history
  (with-client
    :copilot-cli
    (fn [server copilot-client]
      (let [session (sdk/create-session copilot-client {})
            session-id (sdk/session-id session)
            events (sdk/subscribe-events session)]
        (try
          (doseq [source [::absent "" "user" "future-source"]]
            (let [wire (cond-> {:serverName "example" :status "connected"}
                         (not= source ::absent) (assoc :configSource source))
                  expected (cond-> {:server-name "example" :status "connected"}
                             (not= source ::absent) (assoc :config-source source))]
              (mock/send-session-event! server session-id :copilot/session.mcp_server_status_changed wire)
              (is (= (:data (await-event-type! events :copilot/session.mcp_server_status_changed 5000))
                     expected))
              (mock/set-session-messages! server session-id
                                          [{:type "session.mcp_server_status_changed" :data wire}])
              (is (= (:data (first (sdk/get-messages session))) expected))))
          (finally
            (sdk/unsubscribe-events! session events)
            (sdk/disconnect! session)))))))
