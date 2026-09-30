(ns github.copilot-sdk.client-transport-lifecycle-test
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.integration.support :refer [await-atom! await-value!]]
            [github.copilot-sdk.process :as proc]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.stdio-runtime-fixture :as runtime])
  (:import [java.io BufferedInputStream ByteArrayOutputStream IOException OutputStream]
           [java.net InetAddress ServerSocket]
           [java.nio.file Files StandardWatchEventKinds WatchEvent$Kind]
           [java.util.concurrent TimeUnit]))

(defn- await-created! [watch directory filename]
  (let [path (.resolve directory filename)
        deadline (+ (System/nanoTime) (.toNanos TimeUnit/SECONDS 5))]
    (loop []
      (when-not (Files/exists path (make-array java.nio.file.LinkOption 0))
        (let [remaining (- deadline (System/nanoTime))]
          (when-not (pos? remaining)
            (throw (ex-info "Timed out waiting for runtime shutdown" {})))
          (if-let [key (.poll watch remaining TimeUnit/NANOSECONDS)]
            (do (.pollEvents key) (.reset key) (recur))
            (throw (ex-info "Timed out waiting for runtime shutdown" {}))))))))

(deftest owned-stdio-shutdown-signals-eof-before-waiting-for-exit
  (doseq [mode [:stop :force :start-failure :fallback :force-during-stop :blocked-writer]]
    (testing (name mode)
      (let [directory (Files/createTempDirectory
                       "copilot-stdio-shutdown-"
                       (make-array java.nio.file.attribute.FileAttribute 0))
            marker (.resolve directory "telemetry.jsonl")
            requested (.resolve directory "telemetry.jsonl.requested")
            process (atom nil)
            client (sdk/client
                    {:cli-path (str (io/file (System/getProperty "java.home") "bin" "java"))
                     :cli-args ["-cp" (System/getProperty "java.class.path")
                                "clojure.main" "-m" "github.copilot-sdk.stdio-runtime-fixture"
                                (str marker) (if (= mode :force-during-stop) "fallback" (name mode))]
                     :auto-start? false
                     :use-logged-in-user? false})]
        (add-watch (:state client) ::process
                   (fn [_ _ _ state]
                     (when-let [child (get-in state [:process :process])]
                       (reset! process child))))
        (try
          (with-open [watch (.newWatchService (.getFileSystem directory))]
            (.register directory watch
                       (into-array WatchEvent$Kind [StandardWatchEventKinds/ENTRY_CREATE]))
            (if (= mode :start-failure)
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"protocol version"
                                    (sdk/start! client)))
              (do
                (sdk/start! client)
                (let [started (System/nanoTime)]
                  (case mode
                    :force (sdk/force-stop! client)
                    :force-during-stop
                    (let [stopping (future (sdk/stop! client))]
                      (await-created! watch directory (str (.getFileName marker)))
                      (sdk/force-stop! client)
                      (is (= (deref stopping 15000 ::timeout) [])))
                    :blocked-writer
                    (let [stopping (future (sdk/stop! client))]
                      (await-created! watch directory (str (.getFileName requested)))
                      (protocol/send-request (:connection-io @(:state client))
                                             "ping" {:message (apply str (repeat (* 2 1024 1024) "x"))})
                      (is (= (deref stopping 15000 ::timeout) [])))
                    (is (= (sdk/stop! client) [])))
                  (let [elapsed (.toMillis TimeUnit/NANOSECONDS (- (System/nanoTime) started))]
                    (if (#{:fallback :blocked-writer} mode)
                      (is (<= 10000 elapsed 15000))
                      (is (< elapsed 10000)))))))
            (is (some? @process))
            (when-let [^Process child @process]
              (is (.waitFor child 2 TimeUnit/SECONDS))
              (is (not (.isAlive child))))
            (if (#{:force :start-failure :blocked-writer} mode)
              (is (not (Files/exists marker (make-array java.nio.file.LinkOption 0))))
              (is (= (when (Files/exists marker (make-array java.nio.file.LinkOption 0))
                       (slurp (str marker)))
                     "{\"type\":\"span\"}\n")))
            (is (= (sdk/stop! client) []))
            (is (nil? (:process @(:state client)))))
          (finally
            (sdk/force-stop! client)
            (remove-watch (:state client) ::process)
            (Files/deleteIfExists marker)
            (Files/deleteIfExists requested)
            (Files/deleteIfExists directory)))))))

(deftest remote-eof-preserves-completed-channel-sends
  (doseq [[label start] [[:events sdk/send-async]
                         [:with-id #(-> (sdk/send-async-with-id %1 %2) :events-ch)]
                         [:content sdk/<send!]
                         [:message sdk/<send-and-wait!]]
          terminal ["session.idle" "session.error"]]
    (testing (str label " " terminal)
      (with-open [server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
        (.setSoTimeout server 10000)
        (let [send-received (promise)
              release-events (promise)
              serving
              (future
                (with-open [socket (.accept server)]
                  (.setSoTimeout socket 10000)
                  (let [input (BufferedInputStream. (.getInputStream socket))
                        output (.getOutputStream socket)]
                    (loop []
                      (when-let [{:keys [id method params]} (runtime/read-message! input)]
                        (runtime/write-message!
                         output {:jsonrpc "2.0" :id id
                                 :result (case method
                                           "connect" {:protocolVersion 3}
                                           "session.create" {:sessionId (:sessionId params)}
                                           "session.send" {:messageId "send-1"}
                                           (throw (ex-info "Unexpected EOF-fixture request"
                                                           {:method method})))})
                        (if (= method "session.send")
                          (do
                            (deliver send-received true)
                            (await-value! release-events "EOF fixture release" 5000)
                            (let [frames (ByteArrayOutputStream.)
                                  emit! (fn [event]
                                          (runtime/write-message!
                                           frames
                                           {:jsonrpc "2.0" :method "session.event"
                                            :params {:sessionId (:sessionId params) :event event}}))]
                              (dotimes [index 1100]
                                (emit! {:type "assistant.message" :agentId "child"
                                        :data {:content (str index)}}))
                              (emit! {:type "assistant.message"
                                      :data {:messageId "answer" :content "final answer"}})
                              (emit! {:type terminal :data (if (= terminal "session.error")
                                                             {:message "original failure" :errorType "test"}
                                                             {})})
                              (.write output (.toByteArray frames))
                              (.flush output)
                              (.shutdownOutput socket))
                            (is (nil? (runtime/read-message! input))))
                          (recur)))))))
              client (sdk/client {:cli-url (str "127.0.0.1:" (.getLocalPort server))
                                  :auto-start? false})
              result (atom nil)]
          (try
            (sdk/start! client)
            (let [session (sdk/create-session client {})]
              (reset! result (start session {:prompt "hi" :timeout-ms nil}))
              (await-value! send-received "send acknowledgement" 5000)
              (deliver release-events true)
              (await-atom! (:state client) #(= (:status %) :disconnected) "remote EOF cleanup" 5000)
              (let [collected (if (#{:events :with-id} label) (async/into [] @result) @result)
                    [value port] (async/alts!! [collected (async/timeout 5000)])]
                (is (= port collected))
                (if (#{:events :with-id} label)
                  (do
                    (is (= (get-in (last (filter #(nil? (:agent-id %)) (butlast value)))
                                   [:data :content])
                           "final answer"))
                    (is (= (:type (last value)) (keyword "copilot" terminal)))
                    (when (= terminal "session.error")
                      (is (= (get-in (last value) [:data :message]) "original failure"))))
                  (is (= (if (= label :content) value (get-in value [:data :content]))
                         "final answer")))))
            (is (true? (await-value! serving "EOF fixture exit" 5000)))
            (finally
              (deliver release-events true)
              (when-let [channel @result] (async/close! channel))
              (sdk/force-stop! client)
              (.close server)
              (future-cancel serving))))))))

(deftest stdin-close-failure-does-not-retain-a-reaped-process
  (let [mp (proc/spawn-cli
            {:cli-path (str (io/file (System/getProperty "java.home") "bin" "java"))
             :cli-args ["-version"]})
        client (sdk/client {:auto-start? false})
        failing-stdin (proxy [OutputStream] []
                        (write [_] nil)
                        (close [] (throw (IOException. "close failed"))))]
    (try
      (is (proc/wait-for-exit! mp 5000))
      (swap! (:state client) assoc :process (assoc mp :stdin failing-stdin))
      (let [failures (#'client/release-transport!
                      client {:process :graceful :wait-for-exit-ms 100})]
        (is (= (mapv #(select-keys (ex-data %) [:operation :resource]) failures)
               [{:operation :close :resource :process-stdin}]))
        (is (= (ex-message (ex-cause (first failures))) "close failed"))
        (is (nil? (:process @(:state client)))))
      (finally
        (sdk/force-stop! client)
        (proc/destroy-forcibly! mp)))))

(deftest external-uri-can-reconnect-after-either-stop-path
  (with-open [server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))]
    (.setSoTimeout server 10000)
    (let [requests (atom [])
          serving
          (future
            (dotimes [_ 3]
              (with-open [socket (.accept server)]
                (.setSoTimeout socket 10000)
                (let [input (BufferedInputStream. (.getInputStream socket))]
                  (loop []
                    (when-let [{:keys [id method]} (runtime/read-message! input)]
                      (swap! requests conj method)
                      (runtime/write-message!
                       (.getOutputStream socket)
                       {:jsonrpc "2.0" :id id
                        :result (case method
                                  "connect" {:protocolVersion 3}
                                  "ping" {:message "pong"
                                          :timestamp (.toString (java.time.Instant/now))}
                                  (throw (ex-info "Unexpected external-runtime request"
                                                  {:method method})))})
                      (recur)))))))
          client (sdk/client {:cli-url (str "http://127.0.0.1:" (.getLocalPort server))
                              :auto-start? false})]
      (try
        (doseq [stop [sdk/stop! sdk/force-stop! sdk/stop!]]
          (sdk/start! client)
          (is (= (:message (sdk/ping client)) "pong"))
          (stop client)
          (is (= (sdk/state client) :disconnected)))
        (is (nil? (deref serving 5000 ::timeout)))
        (is (= @requests ["connect" "ping" "connect" "ping" "connect" "ping"]))
        (finally
          (sdk/force-stop! client)
          (.close server)
          (future-cancel serving))))))
