(ns github.copilot-sdk.native-host-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.native-offline-fixture :as fixture]
            [github.copilot-sdk.protocol :as protocol]
            [github.copilot-sdk.teardown :as teardown])
  (:import [com.sun.jna CallbackProxy Memory Pointer]
           [java.io File IOException InputStream OutputStream]
           [java.net URL URLClassLoader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util.jar JarEntry JarOutputStream]))

(def ^:private native-entry "native/darwin-arm64/runtime.node")

(deftest classifier-must-be-the-pinned-release
  (let [jar (File/createTempFile "wrong-classifier" ".jar")
        target (.toPath (File. (str jar ".copy")))]
    (try
      (with-open [loader (URLClassLoader. (make-array URL 0) nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"classifier"
                              (#'host/copy-verified-jar! loader "darwin-arm64" target))))
      (with-open [out (JarOutputStream. (io/output-stream jar))]
        (.putNextEntry out (JarEntry. native-entry))
        (.write out (byte-array [1]))
        (.closeEntry out))
      (with-open [loader (URLClassLoader. (into-array URL [(-> jar .toURI .toURL)]) nil)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SHA-256"
                              (#'host/copy-verified-jar! loader "darwin-arm64" target))))
      (finally
        (Files/deleteIfExists target)
        (io/delete-file jar)))))

(deftest invalid-options-are-rejected-before-loading-a-host
  (doseq [opts [{} {:mode nil} {:mode :empty} {:mode :empty :session-fs {}}
                {:mode :empty :base-directory " "} {:mode :coding-agent :github nil}
                {:mode :coding-agent :github {:token nil}}
                {:mode :coding-agent :github {:use-logged-in-user? "true"}}
                {:mode :coding-agent :observability {:log-level :verbose}}
                {:mode :coding-agent :session-idle-timeout-seconds -1}
                {:mode :coding-agent :cli-path "copilot"} {:mode :coding-agent :env {}}
                {:mode :coding-agent :runtime :stdio}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"^(Unsupported native host options|Invalid native host option|Empty mode requires)"
                          (host/open! opts))
        (str (keys opts)))))

(deftest host-options-are-encoded-per-owner
  (let [ambient (zipmap ["COPILOT_SDK_AUTH_TOKEN" "COPILOT_GITHUB_TOKEN" "GH_TOKEN" "GITHUB_TOKEN"
                         "GH_ENTERPRISE_TOKEN" "GITHUB_ENTERPRISE_TOKEN" "GITHUB_COPILOT_API_TOKEN"
                         "COPILOT_CONNECTION_TOKEN" "COPILOT_HOME" "COPILOT_CACHE_HOME"]
                        (repeat "synthetic-service-value"))
        cfg #(#'host/host-config % (assoc ambient "LANG" "C"))
        token "test-placeholder"
        {:keys [argv env]} (cfg {:mode :empty :base-directory "scratch"
                                 :github {:token token}
                                 :session-idle-timeout-seconds 3
                                 :observability {:log-level :none}})]
    (is (= ["--log-level" "none" "--auth-token-env" "COPILOT_SDK_AUTH_TOKEN"
            "--no-auto-login" "--session-idle-timeout" "3"] argv))
    (is (= {"COPILOT_HOME" "scratch" "COPILOT_CACHE_HOME" (str (io/file "scratch" "cache"))
            "COPILOT_DISABLE_KEYTAR" "1" "COPILOT_SDK_AUTH_TOKEN" token}
           (select-keys env ["COPILOT_HOME" "COPILOT_CACHE_HOME" "COPILOT_DISABLE_KEYTAR"
                             "COPILOT_SDK_AUTH_TOKEN"])))
    (let [fs {:initial-cwd "/" :session-state-path "/s" :conventions "posix"}
          virtual (cfg {:mode :empty :session-fs fs})]
      (is (= ["--no-auto-login"] (:argv virtual)))
      (is (= {"LANG" "C" "COPILOT_DISABLE_KEYTAR" "1"} (:env virtual)))
      (is (= [] (:argv (cfg {:mode :empty :session-fs fs :github {:use-logged-in-user? true}})))))
    (is (= {} (select-keys env (keys (dissoc ambient "COPILOT_SDK_AUTH_TOKEN"
                                             "COPILOT_HOME" "COPILOT_CACHE_HOME")))))
    (is (= ambient (select-keys (:env (cfg {:mode :coding-agent})) (keys ambient))))
    (is (= [] (:argv (cfg {:mode :coding-agent}))))
    (is (= ["--auth-token-env" "COPILOT_SDK_AUTH_TOKEN"]
           (:argv (cfg {:mode :coding-agent :github {:token token :use-logged-in-user? true}}))))))

(deftest telemetry-is-a-typed-per-host-environment-override
  (let [ambient {"COPILOT_OTEL_ENABLED" "false" "COPILOT_OTEL_SOURCE_NAME" "ambient"
                 "OTEL_EXPORTER_OTLP_PROTOCOL" "http/protobuf" "GH_TOKEN" "ambient-token"}
        config (fn [observability]
                 (:env (#'host/host-config {:mode :empty :base-directory "scratch"
                                            :observability observability} ambient)))
        base (config {})]
    (is (not (contains? base "GH_TOKEN")))
    (is (= (select-keys base (keys ambient)) (dissoc ambient "GH_TOKEN")))
    (is (= (config {:telemetry {}}) (assoc base "COPILOT_OTEL_ENABLED" "true")))
    (is (= (config {:telemetry {:source-name "" :file-path "trace.jsonl" :exporter-type "file"
                                :otlp-protocol "http/json" :otlp-endpoint "http://example.invalid"
                                :capture-content? false}})
           (assoc base "COPILOT_OTEL_ENABLED" "true" "COPILOT_OTEL_SOURCE_NAME" ""
                  "COPILOT_OTEL_FILE_EXPORTER_PATH" "trace.jsonl" "COPILOT_OTEL_EXPORTER_TYPE" "file"
                  "OTEL_EXPORTER_OTLP_PROTOCOL" "http/json" "OTEL_EXPORTER_OTLP_ENDPOINT" "http://example.invalid"
                  "OTEL_INSTRUMENTATION_GENAI_CAPTURE_CONTENT" "NO_CONTENT"
                  "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT" "false")))
    (is (= (config {:telemetry {:capture-content? true :otlp-protocol "http/protobuf"}})
           (assoc base "COPILOT_OTEL_ENABLED" "true" "OTEL_INSTRUMENTATION_GENAI_CAPTURE_CONTENT" "SPAN_ONLY"
                  "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT" "true")))
    (doseq [value [nil false {:enabled? false} {:capture-content? nil} {:capture-content? "false"}
                   {:otlp-protocol "grpc"} {:otlp-endpoint nil} {:file-path 1} {:source-name :app}
                   {:exporter-type nil}]]
      (is (thrown? clojure.lang.ExceptionInfo (config {:telemetry value})) (pr-str value)))))

(deftest virtual-session-fs-has-private-host-storage
  (let [homes (atom [])
        outside (File/createTempFile "copilot-home-neighbor" ".txt")
        abi {:start (fn [_ bytes]
                      (let [env (json/read-str (String. ^bytes bytes StandardCharsets/UTF_8))
                            home (io/file (get env "COPILOT_HOME"))]
                        (is (= (str (io/file home "cache")) (get env "COPILOT_CACHE_HOME")))
                        (count (swap! homes conj home))))
             :open (fn [id _] id) :close (constantly true) :shutdown (constantly true)}
        config {:argv ["--no-auto-login"] :env {}}
        open! #(#'host/open-config! abi config true)]
    (try
      (let [first (open!)]
        (teardown/with-cleanup (:close! first)
          (let [second (open!)
                [^File a ^File b] @homes]
            (teardown/with-cleanup (:close! second)
              (is (not= a b))
              (is (.isDirectory a))
              (is (.isDirectory b))
              (spit (io/file a "runtime-state") "synthetic")
              (when-not (com.sun.jna.Platform/isWindows)
                (Files/createSymbolicLink (.toPath (io/file a "link")) (.toPath outside)
                                          (make-array java.nio.file.attribute.FileAttribute 0)))
              ((:close! first))
              (is (not (.exists a)))
              (is (.exists outside))
              (is (.isDirectory b))
              (Files/delete (.toPath b))
              (is (nil? ((:close! second))))))))
      (is (every? #(not (.exists ^File %)) @homes))
      (finally (io/delete-file outside)))))

(deftest native-upcall-copies-borrowed-bytes-and-fails-without-blocking
  (let [{:keys [^InputStream in ^CallbackProxy callback]} (#'host/inbound 2 4)]
    (with-open [mem (Memory. 4)]
      (.setByte mem 0 (byte 1))
      (.callback callback (object-array [nil nil (long 0)]))
      (.callback callback (object-array [nil mem (long 4)]))
      (.setByte mem 0 (byte 2))
      (is (= 1 (.read in)))
      (.callback callback (object-array [nil mem (long 4)]))
      (is (thrown-with-msg? IOException #"input failed" (.read in)))
      (.close in)
      (is (nil? (.callback callback (object-array [nil nil (long 1)])))))))

(deftest terminal-input-drops-partial-queued-and-in-flight-chunks
  (doseq [[end chunk-limit byte-slack] [[:close 2 1] [:byte-overflow 2 1] [:chunk-overflow 1 2]]]
    (testing (name end)
      (let [size (* 2 1024 1024)
            {:keys [^InputStream in ^CallbackProxy callback state]} (#'host/inbound chunk-limit (+ size byte-slack))
            copying (promise)
            resume (promise)
            ptr (proxy [Pointer] [0]
                  (getByteArray [_ n]
                    (deliver copying true)
                    (when-not (deref resume 5000 false) (throw (IOException. "Copy timed out")))
                    (byte-array n)))
            send! (fn [p n] (.callback callback (object-array [nil p (long n)])))]
        (with-open [mem (Memory. size)]
          (.setByte mem 0 (byte 42))
          (send! mem size)
          (is (= 42 (.read in)))
          (send! mem 1)
          (let [{:keys [^InputStream current ^java.util.ArrayDeque queue]} @state
                pending (future (send! ptr 1))]
            (try
              (is (= (dec size) (.available current)))
              (is (= true (deref copying 2000 false)))
              (if (= end :close) (.close in) (send! mem 1))
              (.close in)
              (is (= [:terminal] (keys @state)))
              (if (= end :close)
                (is (= -1 (.read in)))
                (is (thrown-with-msg? IOException #"input failed" (.read in))))
              ;; The old queue is detached. It must not accept the late copy.
              (let [before (.size queue)]
                (deliver resume true)
                (is (nil? (deref pending 2000 ::blocked)))
                (is (= before (.size queue))))
              (finally (deliver resume true) (.close in)))))))))

(defn- callback-rooted? [callback]
  (contains? @(var-get #'host/owners) callback))

(deftest private-home-deletion-can-retry-after-native-retirement
  (doseq [deferred? [false true]]
    (testing (if deferred? "deferred close" "caller close")
      (let [cb (atom nil)
            calls (atom [])
            attempted (promise)
            failure (IOException. "Cannot delete private home")
            abi {:start (fn [_ _] 1)
                 :open (fn [_ callback] (reset! cb callback) 2)
                 :close (fn [_] (let [calls (swap! calls conj :close)]
                                  (or (not deferred?) (> (count calls) 1))))
                 :shutdown (fn [_] (swap! calls conj [:shutdown (callback-rooted? @cb)]) true)
                 :release-home! (fn []
                                  (swap! calls conj [:delete (callback-rooted? @cb)])
                                  (when-not (realized? attempted)
                                    (deliver attempted true)
                                    (throw failure)))}
            {:keys [close!]} (#'host/host-transport abi nil nil)]
        (try
          (let [error (with-redefs-fn {#'host/close-window-ms 0}
                        #(try (close!) (catch Exception e e)))]
            (if deferred?
              (is (:retryable? (ex-data error)))
              (is (identical? failure error))))
          (is (= true (deref attempted 2000 ::not-attempted)))
          (is (not (callback-rooted? @cb)))
          (is (nil? (close!)))
          (is (nil? (close!)))
          (is (= (into (if deferred? [:close :close] [:close])
                       [[:shutdown true] [:delete false] [:delete false]])
                 @calls))
          (finally (swap! (var-get #'host/owners) dissoc @cb)))))))

(deftest failed-startup-retries-only-the-retired-private-home
  (let [home (promise) cleaned (promise) attempts (atom 0) shutdowns (atom 0)
        remove-home! @#'host/delete-home!
        failure (IOException. "Fixture transient directory failure")
        abi {:start (fn [_ bytes]
                      (let [path (.toPath (io/file (get (json/read-str (String. ^bytes bytes StandardCharsets/UTF_8)) "COPILOT_HOME")))]
                        (deliver home path)
                        1))
             :open (constantly 0) :shutdown (fn [_] (swap! shutdowns inc) true)}]
    (try
      (with-redefs [host/delete-home! (fn [path]
                                        (if (= 1 (swap! attempts inc)) (throw failure)
                                            (do (remove-home! path) (deliver cleaned true))))]
        (let [problem (try (#'host/open-config! abi {:argv [] :env {}} true) (catch Exception e e))]
          (is (= (ex-message problem) "Copilot runtime connection open failed"))
          (let [[cleanup] (.getSuppressed ^Throwable problem)]
            (is (= (ex-data cleanup) {:type :private-home-cleanup :path (str @home)}))
            (is (identical? (.getCause ^Throwable cleanup) failure)))
          (is (true? (deref cleaned 3000 false)))
          (is (not (.exists (.toFile ^java.nio.file.Path @home))))
          (is (= [@attempts @shutdowns] [2 1]))))
      (finally (when (realized? home) (remove-home! @home))))))

(deftest failed-startup-retains-an-owner-after-the-caller-is-gone
  (let [cb (atom nil)
        starts (atom 0)
        owners (var-get #'host/owners)
        cleanup-owned (promise)
        can-close (promise)
        retried (promise)
        shutdown (promise)
        retired (promise)
        abi {:start (fn [_ _] (swap! starts inc))
             :open (fn [_ callback]
                     (reset! cb callback)
                     (.interrupt (Thread/currentThread))
                     17)
             :close (fn [_]
                      (if (realized? cleanup-owned)
                        (do (deliver retried true) (realized? can-close))
                        false))
             :shutdown (fn [_] (deliver shutdown true) true)}]
    (add-watch owners ::retired (fn [_ _ old new]
                                  (when (and @cb (contains? old @cb) (not (contains? new @cb)))
                                    (deliver retired true))))
    (try
      (with-redefs-fn
        {#'host/close-window-ms 0 #'host/max-owners 1}
        (fn []
          (let [[err interrupted?] (try (#'host/host-transport abi nil nil)
                                        (catch InterruptedException e [e (Thread/interrupted)]))]
            (is interrupted?)
            (is (:retryable? (ex-data (first (.getSuppressed ^Throwable err)))))
            (is (callback-rooted? @cb))
            (is (not (realized? shutdown)))
            (deliver cleanup-owned true)
            (is (= true (deref retried 2000 ::not-retried)))
            (let [pressure (try (#'host/host-transport abi nil nil)
                                (catch clojure.lang.ExceptionInfo e (ex-data e)))]
              (is (= {:capacity 1 :states {:closing 1}} pressure)))
            (is (= 1 @starts))
            (deliver can-close true)
            (is (= true (deref shutdown 2000 ::not-shut-down)))
            (is (= true (deref retired 2000 ::not-retired))))))
      (finally (deliver can-close true) (remove-watch owners ::retired)))))

(deftest native-handle-ownership-on-failure
  (let [events (atom [])
        cb (atom nil)
        abi {:start (fn [_ _] 1)
             :open (fn [_ callback] (reset! cb callback) 2)
             :close (fn [conn]
                      (swap! events conj [:close conn (callback-rooted? @cb)])
                      (> (count @events) 1))
             :shutdown (fn [host]
                         (swap! events conj [:shutdown host (callback-rooted? @cb)])
                         true)}
        expected [[:close 2 true] [:close 2 true] [:shutdown 1 true]]]
    (testing "native false is not a quiescence barrier"
      (let [{:keys [close!]} (#'host/host-transport abi nil nil)]
        (close!)
        (close!)
        (is (= expected @events))))
    (testing "interrupted startup still closes the connection and restores interruption"
      (reset! events [])
      (let [interrupted (assoc abi :open (fn [_ callback]
                                           (reset! cb callback)
                                           (.interrupt (Thread/currentThread))
                                           2))
            [err flagged?] (try (#'host/host-transport interrupted nil nil)
                                (catch InterruptedException e [e (Thread/interrupted)]))]
        (is (instance? InterruptedException err))
        (is (empty? (.getSuppressed ^Throwable err)))
        (is flagged?)
        (is (= expected @events))))
    (testing "a close timeout is retryable without releasing the callback"
      (reset! events [])
      (with-redefs-fn
        {#'host/close-window-ms 0}
        (fn []
          (let [{:keys [close!]} (#'host/host-transport abi nil nil)
                err (try (close!) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (true? (:retryable? (ex-data err))))
            (is (callback-rooted? @cb))
            (close!)
            (is (= expected @events))))))
    (testing "a zero connection handle tears down its host and preserves both errors"
      (doseq [retired? [false true]]
        (let [failed (assoc abi :open (fn [_ callback] (reset! cb callback) 0)
                            :shutdown (constantly retired?)
                            :release-home! #(throw (IOException. "Cannot delete private home")))]
          (try
            (let [err (try (#'host/host-transport failed nil nil) nil
                           (catch clojure.lang.ExceptionInfo e e))]
              (is (= "Copilot runtime connection open failed" (ex-message err)))
              (is (= [(if retired? "Cannot delete private home" "Copilot runtime host shutdown failed")]
                     (mapv ex-message (.getSuppressed err))))
              (is (= (not retired?) (callback-rooted? @cb))))
            (finally (swap! (var-get #'host/owners) dissoc @cb))))))
    (testing "a deferred unknown native result quarantines without retry or shutdown"
      (let [owners (var-get #'host/owners)
            calls (atom 0)
            quarantined (promise)
            broken (assoc abi :close (fn [_]
                                       (if (= 1 (swap! calls inc))
                                         false
                                         (throw (IOException. "close failed")))))]
        (add-watch owners ::quarantine (fn [_ _ _ new]
                                         (when (= :quarantined (get new @cb)) (deliver quarantined true))))
        (try
          (with-redefs-fn
            {#'host/close-window-ms 0 #'host/max-owners 1}
            (fn []
              (reset! events [])
              (let [{:keys [in close!]} (#'host/host-transport broken nil nil)
                    err (try (close!) nil (catch clojure.lang.ExceptionInfo e e))]
                (is (:retryable? (ex-data err)))
                (is (= true (deref quarantined 2000 ::not-quarantined)))
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"quarantined" (close!)))
                (is (= -1 (.read ^InputStream in)))
                (is (= {:capacity 1 :states {:quarantined 1}}
                       (try (#'host/host-transport abi nil nil)
                            (catch clojure.lang.ExceptionInfo e (ex-data e)))))
                (is (= 2 @calls))
                (is (empty? @events)))))
          ;; This fault-injection callback has no native registration.
          (finally (remove-watch owners ::quarantine) (swap! owners dissoc @cb)))))))

(deftest ui-operations-are-scoped-to-an-enabled-live-connection
  (let [calls (atom [])
        ready? (atom false)
        abi {:start (fn [_ _] 11) :open (fn [_ _] 23)
             :configure-ui (fn [conn enabled?] (swap! calls conj [:configure conn enabled?]) @ready?)
             :run-ui-action (fn [conn id]
                              (swap! calls conj [:run conn id])
                              (case id 0 false 1 (throw (IOException. "native UI failed")) true))
             :close (constantly true) :shutdown (constantly true)}
        {:keys [configure-ui! run-ui-action! close!]} (#'host/host-transport abi nil nil)]
    (teardown/with-cleanup close!
      (is (thrown? clojure.lang.ExceptionInfo (run-ui-action! 0)))
      (is (empty? @calls))
      (is (thrown? clojure.lang.ExceptionInfo (configure-ui! nil)))
      (is (thrown? clojure.lang.ExceptionInfo (configure-ui! true)))
      (is (thrown? clojure.lang.ExceptionInfo (run-ui-action! 0)))
      (reset! ready? true)
      (is (nil? (configure-ui! false)))
      (is (thrown? clojure.lang.ExceptionInfo (run-ui-action! 0)))
      (is (nil? (configure-ui! true)))
      (doseq [id [nil -1 1.5 4294967296 18446744073709551616N]]
        (is (thrown? clojure.lang.ExceptionInfo (run-ui-action! id))))
      (is (thrown? clojure.lang.ExceptionInfo (run-ui-action! 0)))
      (is (thrown-with-msg? IOException #"native UI failed" (run-ui-action! 1)))
      (is (nil? (run-ui-action! 4294967295N)))
      (close!)
      (is (thrown? IOException (configure-ui! false)))
      (is (thrown? IOException (run-ui-action! 0)))
      (is (= [[:configure 23 true] [:configure 23 false] [:configure 23 true]
              [:run 23 0] [:run 23 1] [:run 23 4294967295]]
             @calls)))))

(deftest ui-action-can-return-during-native-close
  (let [entered (promise)
        cancel (promise)
        returned (promise)
        calls (atom [])
        abi {:start (fn [_ _] 5) :open (fn [_ _] 6)
             :configure-ui (constantly true)
             :run-ui-action (fn [_ _]
                              (deliver entered true)
                              (= true (deref cancel 5000 ::timeout)))
             :close (fn [_]
                      (swap! calls conj :close)
                      (deliver cancel true)
                      (is (nil? (deref returned 2000 ::still-running)))
                      true)
             :shutdown (fn [_] (swap! calls conj :shutdown) true)}
        {:keys [configure-ui! run-ui-action! close!]} (#'host/host-transport abi nil nil)]
    (teardown/with-cleanup close!
      (configure-ui! true)
      (future (deliver returned (run-ui-action! 42)))
      (is (= true (deref entered 2000 ::not-entered)))
      (is (nil? (try (close!) (catch clojure.lang.ExceptionInfo e (ex-data e)))))
      (is (nil? (deref returned 2000 ::still-running)))
      (is (= [:close :shutdown] @calls)))))

(deftest close-fences-in-flight-ui-without-blocking-the-transport
  (let [entered (promise)
        cancelled (promise)
        leaving (promise)
        release (promise)
        shutting-down (promise)
        calls (atom [])
        abi {:start (fn [_ _] 5) :open (fn [_ _] 6)
             :configure-ui (constantly true)
             :run-ui-action (fn [conn id]
                              (swap! calls conj [:run conn id])
                              (deliver entered (Thread/currentThread))
                              (if (= true (deref cancelled 5000 ::timeout))
                                (do (deliver leaving true) (= true (deref release 5000 ::timeout)))
                                false))
             :write (fn [conn _] (swap! calls conj [:write conn]) true)
             :close (fn [conn] (swap! calls conj [:close conn]) (deliver cancelled true) true)
             :shutdown (fn [host] (swap! calls conj [:shutdown host]) (deliver shutting-down true) true)}
        {:keys [configure-ui! run-ui-action! close! out]} (#'host/host-transport abi nil nil)]
    (try
      (configure-ui! true)
      (let [thread (promise)
            action (future (deliver thread (Thread/currentThread)) (run-ui-action! 42))]
        (is (identical? (deref thread 2000 nil) (deref entered 2000 ::not-entered)))
        (is (nil? (deref (future (.write ^OutputStream out 42)) 2000 ::writer-blocked)))
        (let [closed (future (try (close!) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
          (is (true? (:retryable? (deref closed 2000 {}))))
          (is (= true (deref leaving 1000 ::no-cancellation)))
          (is (thrown? IOException (run-ui-action! 42)))
          (is (not (realized? shutting-down)))
          (is (= [[:run 6 42] [:write 6] [:close 6]] @calls)))
        (let [set-calls (atom [])
              replacement (#'host/host-transport
                           (assoc abi :start (fn [_ _] 7)
                                  :configure-ui (fn [conn enabled?] (swap! set-calls conj [conn enabled?]) true)
                                  :close (constantly true) :shutdown (constantly true)) nil nil)]
          (teardown/with-cleanup (:close! replacement)
            ;; Simulate connection-ID wrap while the old ABI call still owns that number.
            (is (nil? ((:configure-ui! replacement) false)))
            (is (thrown? clojure.lang.ExceptionInfo ((:configure-ui! replacement) true)))
            (is (= [[6 false]] @set-calls))
            (deliver release true)
            (is (nil? (deref action 2000 ::action-blocked)))
            (is (= true (deref shutting-down 2000 ::not-reaped)))
            (close!)
            (is (= [[:run 6 42] [:write 6] [:close 6] [:shutdown 5]] @calls))
            (is (nil? ((:configure-ui! replacement) true)))
            (is (= [[6 false] [6 true]] @set-calls)))))
      (finally (deliver cancelled true) (deliver release true) (close!)))))

(defn- with-native-connection [opts f]
  (let [{:keys [in out close!] :as host} (host/open! opts)]
    (teardown/with-cleanup close!
      (let [state (atom {:connection (protocol/initial-connection-state)})
            conn (protocol/connect in out state)]
        (teardown/with-cleanup #(when-let [failure (first (protocol/disconnect conn))]
                                  (throw failure))
          (f host conn))))))

(defn- exercise-hosts! [home]
  (let [opts {:mode :empty :github {:use-logged-in-user? false}}
        ping (fn [conn text] (:message (protocol/send-request! conn "ping" {:message text} 5000)))]
    (with-native-connection
      (assoc opts :session-fs {:initial-cwd "/virtual" :session-state-path "/sessions" :conventions "posix"})
      (fn [{:keys [configure-ui! close! out]} first-conn]
        (configure-ui! true)
        (let [connect (protocol/send-request! first-conn "connect" {} 5000)
              status (protocol/send-request! first-conn "status.get" {} 5000)]
          (with-native-connection
            (assoc opts :base-directory (str (io/file home "second")))
            (fn [headless other]
              ((:configure-ui! headless) false)
              (let [other-reply (ping other "other")
                    text (apply str (repeat 65536 "x"))
                    large-reply? (= (str "pong: " text) (ping first-conn text))]
                (close!)
                (close!)
                {:protocol (:protocol-version connect) :runtime (:version status)
                 :other other-reply :large-reply? large-reply?
                 :closed? (try (.write ^OutputStream out 42) false (catch IOException _ true))
                 :survivor (ping other "survivor")}))))))))

(defn -main [_base-url _prompt]
  (try
    (let [home (System/getenv "HOME")]
      (when-not (and (= home (System/getProperty "user.home") (System/getProperty "java.io.tmpdir"))
                     (= (System/getenv "PATH") (str (io/file home "no-programs")))
                     (not (.exists (io/file home ".copilot"))))
        (throw (ex-info "Run the native host fixture in the isolated test JVM" {})))
      (spit "result.edn" (pr-str (exercise-hosts! home))))
    (finally (shutdown-agents))))

(deftest ^:native isolated-real-native-hosts
  (let [{:keys [event requests]} (fixture/exercise-turn!
                                  ["-m" "github.copilot-sdk.native-host-test"]
                                  (fn [_] (throw (ex-info "Host pings must not call a model" {}))))]
    (is (= requests []))
    (is (= event {:protocol 4 :runtime "1.0.114" :other "pong: other" :large-reply? true
                  :closed? true :survivor "pong: survivor"}))))
