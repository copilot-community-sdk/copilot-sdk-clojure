(ns github.copilot-sdk.owned-process-exit-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.integration.support :refer [await-atom! await-value!]]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.stdio-runtime-fixture :as rpc])
  (:import [java.io BufferedInputStream]
           [java.lang ProcessHandle]
           [java.net InetAddress ServerSocket]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(defn- fixture-client
  ([mode destination] (fixture-client mode destination {}))
  ([mode destination opts]
   (sdk/client
    (merge
     {:cli-path (str (io/file (System/getProperty "java.home") "bin" "java"))
      :cli-args ["-cp" (System/getProperty "java.class.path") "clojure.main"
                 "-m" "github.copilot-sdk.owned-process-exit-fixture" mode destination]
      :use-stdio? (= mode "stdio")
      :auto-start? false
      :auto-restart? true
      :use-logged-in-user? false}
     opts))))

(defn- take-result! [channel label timeout-ms]
  (let [[value port] (async/alts!! [channel (async/timeout timeout-ms)])]
    (when-not (= port channel)
      (throw (ex-info (str "Timed out waiting for " label) {:timeout-ms timeout-ms})))
    value))

(defn- kill-process! [^Process child]
  (is (.destroyForcibly (.toHandle child)))
  (is (.waitFor child 5 TimeUnit/SECONDS)))

(defn- stop-holder! [^ProcessHandle holder]
  (when (.isAlive holder)
    (is (.destroyForcibly holder))
    (.get (.onExit holder) 5 TimeUnit/SECONDS)))

(defn- exercise-owned-tcp-exit! [phase]
  (testing (name phase)
    (with-open [server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
      (.setSoTimeout server 15000)
      (let [copilot-client (fixture-client
                            "tcp" (str (.getLocalPort server))
                            (when (= phase :starting)
                              {:builtin-plugin-directories [(.getCanonicalPath (io/file "."))]}))
            connect-received (promise)
            release-connect (promise)
            detach-received (promise)
            peer-eof (promise)
            release-peer (promise)
            stopping (atom nil)
            serving
            (future
              (with-open [peer (.accept server)
                          input (BufferedInputStream. (.getInputStream peer))
                          output (.getOutputStream peer)]
                (loop []
                  (if-let [{:keys [id method params]} (rpc/read-message! input)]
                    (do
                      (let [result
                            (case method
                              "connect" (do
                                          (when (not= phase :starting)
                                            (deliver connect-received true))
                                          {:protocolVersion 3})
                              "plugins.builtin.set" (do
                                                      (deliver connect-received true)
                                                      (await-value! release-connect "startup reply" 15000)
                                                      {})
                              "session.create" {:sessionId (:sessionId params)}
                              "session.detach" (do (deliver detach-received true) ::pending)
                              "ping" ::pending
                              (throw (ex-info "Unexpected TCP fixture request" {:method method})))]
                        (when-not (= result ::pending)
                          (rpc/write-message! output {:jsonrpc "2.0" :id id :result result})))
                      (recur))
                    (do
                      (deliver peer-eof true)
                      (await-value! release-peer "TCP peer cleanup" 20000)))))
              true)
            starting (future (try (sdk/start! copilot-client)
                                  (catch Throwable error error)))]
        (try
          (await-value! connect-received "connect request" 15000)
          (let [child (get-in @(:state copilot-client) [:process :process])]
            (if (= phase :starting)
              (do
                (kill-process! child)
                (deliver release-connect true)
                (let [result (await-value! starting "failed startup" 10000)]
                  (is (instance? Throwable result))
                  (is (not= (sdk/state copilot-client) :connected))))
              (do
                (is (nil? (await-value! starting "startup" 10000)))
                (let [session (sdk/create-session copilot-client {})
                      events (sdk/subscribe-events session)
                      conn (:connection-io @(:state copilot-client))
                      pending (when (= phase :connected)
                                (protocol/send-request conn "ping" {}))]
                  (when (= phase :stopping)
                    (reset! stopping (future (sdk/stop! copilot-client)))
                    (await-value! detach-received "shutdown detach request" 5000))
                  (kill-process! child)
                  (if-let [shutdown @stopping]
                    (let [errors (await-value! shutdown "stop after owned exit" 5000)]
                      (is (some #(re-find #"Failed to disconnect session" (ex-message %)) errors)))
                    (is (some? (:error (take-result! pending "pending RPC rejection" 5000)))))
                  (await-atom! (:state copilot-client) #(= (:status %) :disconnected)
                               "owned TCP exit" 5000)
                  (is (nil? (take-result! events "session event closure" 5000)))
                  (is (= (:sessions @(:state copilot-client)) {}))
                  (is (nil? (:connection-io @(:state copilot-client))))
                  (is (= (sdk/stop! copilot-client) [])))))
            (is (true? (await-value! peer-eof "local socket closure" 5000))))
          (finally
            (deliver release-connect true)
            (deliver release-peer true)
            (sdk/force-stop! copilot-client)
            (when-let [shutdown @stopping]
              (await-value! shutdown "shutdown cleanup" 10000))
            (is (true? (await-value! serving "TCP fixture cleanup" 10000)))))))))

(deftest owned-tcp-exit-does-not-wait-for-a-surviving-peer
  (exercise-owned-tcp-exit! :connected))

(deftest owned-exit-during-startup-cannot-publish-a-connected-client
  (exercise-owned-tcp-exit! :starting))

(deftest owned-exit-unblocks-an-in-progress-stop
  (exercise-owned-tcp-exit! :stopping))

(deftest owned-stdio-exit-drains-before-bounded-disconnection
  (let [directory (Files/createTempDirectory "copilot-owned-exit-"
                                             (make-array java.nio.file.attribute.FileAttribute 0))
        holder-path (.resolve directory "holder.pid")
        copilot-client (fixture-client "stdio" (str holder-path))
        holders (atom [])]
    (try
      (sdk/start! copilot-client)
      (let [holder (.orElseThrow (ProcessHandle/of (parse-long (slurp (str holder-path)))))
            _ (swap! holders conj holder)
            initial-models (sdk/list-models copilot-client)
            session (sdk/create-session copilot-client {})
            conn (:connection-io @(:state copilot-client))
            pending (protocol/send-request conn "ping" {:message "pending"})
            notification (take-result! (sdk/notifications copilot-client) "pending ping receipt" 5000)
            result (sdk/<send-and-wait! session {:prompt "Complete before exit"})]
        (is (= (:method notification) "fixture.pingReceived"))
        (is (= (get-in (take-result! result "completed root message" 5000) [:data :content])
               "Complete before exit"))
        (let [started (System/nanoTime)]
          (kill-process! (get-in @(:state copilot-client) [:process :process]))
          (is (.isAlive holder))
          (is (some? (:error (take-result! pending "inherited-pipe RPC rejection" 15000))))
          (await-atom! (:state copilot-client) #(= (:status %) :disconnected)
                       "owned stdio exit" 5000)
          (is (< (.toMillis TimeUnit/NANOSECONDS (- (System/nanoTime) started)) 15000)))
        (is (= (:session-io @(:state copilot-client)) {}))
        (is (nil? (:models-cache @(:state copilot-client))))
        (is (.isAlive holder) "disconnect does not wait for or kill an unowned descendant")
        (sdk/start! copilot-client)
        (swap! holders conj (.orElseThrow (ProcessHandle/of (parse-long (slurp (str holder-path))))))
        (is (not= (mapv :id (sdk/list-models copilot-client)) (mapv :id initial-models)))
        (stop-holder! holder)
        (.join ^Thread (:read-thread conn) 5000)
        (is (not (.isAlive ^Thread (:read-thread conn))))
        (is (= (:message (sdk/ping copilot-client "replacement")) "replacement"))
        (is (= (sdk/state copilot-client) :connected))
        (is (= (sdk/stop! copilot-client) [])))
      (finally
        (doseq [holder @holders]
          (stop-holder! holder))
        (sdk/force-stop! copilot-client)
        (Files/deleteIfExists holder-path)
        (Files/delete directory)))))
