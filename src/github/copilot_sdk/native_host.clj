(ns ^:no-doc github.copilot-sdk.native-host
  "Internal in-process transport and optional UI actions for the Rust runtime's C ABI."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [github.copilot-sdk.logging :as log]
            [copilot.sdk.specs :as specs]
            [github.copilot-sdk.teardown :as teardown])
  (:import [com.sun.jna CallbackProxy Function Native NativeLibrary Platform Pointer]
           [java.io ByteArrayInputStream File InputStream IOException OutputStream UncheckedIOException]
           [java.net JarURLConnection]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption FileVisitOption Files NoSuchFileException Path]
           [java.nio.file.attribute FileAttribute]
           [java.security DigestInputStream MessageDigest]
           [java.util ArrayDeque Arrays HexFormat Objects Properties]
           [java.util.concurrent ArrayBlockingQueue ConcurrentHashMap RejectedExecutionException ScheduledFuture
            ScheduledThreadPoolExecutor Semaphore ThreadFactory ThreadPoolExecutor TimeUnit]
           [java.util.jar JarFile]))

;; Maven Central: com.microsoft:copilot-sdk-runtime:2.0.3:<classifier>.jar.sha256.
(def ^:private classifier-sha256
  {"darwin-arm64" "ef155db72a5bae6cad70434e2753e73c7f3f1715911f7d3ebd4042c5e70979e8"
   "darwin-x64" "ae8c8a351139f85b691dbd93210870877d7c5e5733be4399e9d3c8d92889786d"
   "linux-arm64" "10472a5a3a34ba62f3efd8457ce611ef3bf690e7f782faf23c7f02e930780a03"
   "linux-x64" "7c4ba89030d4a61b268b60cc27b4b57799f5a2cf6f133a9c6363a0238207cf71"
   "linuxmusl-arm64" "ca2bad77dd715f2e9349e0dc47afea425e5639e7ca6b3e5ee34127ec6f817f50"
   "linuxmusl-x64" "fac43b3e1179fee39748d52c42015db605b2c33da9680d27981614a6c5308b78"
   "win32-arm64" "ab3d8891a3b5820d6db91df96b2036b864ff2517ad01de33a398ef14ebb5c25b"
   "win32-x64" "d4875fd0fb769fd6d6a3193c16279027dfcc410acc58f9af63ea3e09043ea6ca"})

(def ^:private ^"[Ljava.nio.file.CopyOption;" new-file (make-array CopyOption 0))
(def ^:private ^"[Ljava.nio.file.attribute.FileAttribute;" default-attrs (make-array FileAttribute 0))

(defn- host-classifier [^ClassLoader loader]
  (let [arch (cond (Platform/isARM) "arm64" (Platform/isIntel) "x64")
        systems (cond (Platform/isMac) ["darwin"]
                      (Platform/isWindows) ["win32"]
                      (Platform/isLinux) ["linux" "linuxmusl"])
        ;; The application supplies its libc variant, not a binary path.
        candidates (when (and arch (= Long/BYTES Native/SIZE_T_SIZE))
                     (mapv #(str % "-" arch) systems))
        supplied (filter #(.getResource loader (str "native/" % "/runtime.node")) candidates)]
    (if (= 1 (count supplied))
      (first supplied)
      (throw (ex-info "Supply exactly one Copilot SDK 2.0.3 runtime classifier for this JVM"
                      {:os (System/getProperty "os.name") :arch Platform/ARCH
                       :candidates candidates :supplied (vec supplied)})))))

(defn- copy-verified-jar! [^ClassLoader loader classifier ^Path target]
  (let [resource (.getResource loader (str "native/" classifier "/runtime.node"))
        jar-url (when (= "jar" (some-> resource .getProtocol))
                  (.getJarFileURL ^JarURLConnection (.openConnection resource)))]
    (when-not (= "file" (some-> jar-url .getProtocol))
      (throw (ex-info "A local Copilot SDK 2.0.3 platform classifier JAR is required"
                      {:classifier classifier})))
    (let [digest (MessageDigest/getInstance "SHA-256")
          expected (get classifier-sha256 classifier)
          actual (with-open [in (DigestInputStream. (io/input-stream (io/file (.toURI jar-url))) digest)]
                   (Files/copy in target new-file)
                   (.formatHex (HexFormat/of) (.digest digest)))]
      (when-not (= expected actual)
        (throw (ex-info "Copilot runtime classifier SHA-256 mismatch"
                        {:classifier classifier :expected expected :actual actual}))))))

(defn- extract-library! ^File [^ClassLoader loader]
  (let [classifier (host-classifier loader)
        prefix (str "native/" classifier "/")
        root (Files/createTempDirectory "copilot-sdk-2.0.3-" default-attrs)
        archive (.resolve root "classifier.jar")]
    ;; Until process exit, the library may open assets beside its own image.
    ;; Register parents before children: deleteOnExit removes in reverse order.
    (.deleteOnExit (.toFile root))
    (try
      (copy-verified-jar! loader classifier archive)
      (with-open [jar (JarFile. (.toFile archive))]
        (letfn [(entry [p] (.getInputStream jar (.getJarEntry jar (str prefix p))))]
          (with-open [^InputStream in (entry "platform.properties")]
            (let [props (doto (Properties.) (.load in))]
              (when-not (and (= classifier (.getProperty props "classifier"))
                             (= "1.0.116" (.getProperty props "version")))
                (throw (ex-info "Unexpected Copilot runtime metadata" {:classifier classifier})))))
          (with-open [manifest (io/reader (entry "runtime-assets.list") :encoding "UTF-8")]
            (doseq [line (line-seq manifest)
                    :let [[mode name] (str/split line #"\t" 2)
                          path (.resolve root ^String name)]]
              (doseq [^File dir (reverse (take-while #(not (.exists ^File %))
                                                     (iterate #(.getParentFile ^File %)
                                                              (.getParentFile (.toFile path)))))]
                (Files/createDirectory (.toPath dir) default-attrs)
                (.deleteOnExit dir))
              (with-open [^InputStream in (entry name)]
                (Files/copy in path new-file))
              (.deleteOnExit (.toFile path))
              (when (and (= "755" mode) (not (Platform/isWindows))
                         (not (.setExecutable (.toFile path) true true)))
                (throw (IOException. (str "Cannot set executable bit on " path))))))))
      (Files/delete archive)
      (.getCanonicalFile (.toFile (.resolve root "runtime.node")))
      (catch Throwable t
        (teardown/cleanup-preserving! t #(doseq [f (reverse (file-seq (.toFile root)))]
                                           (io/delete-file f)))
        (throw t)))))

(defn- native-true? [^Function f & args]
  ;; Rust bool is one byte; JNA's Boolean return type denotes a C int.
  (not (zero? (.invoke f Byte/TYPE (object-array args)))))

;; Rust worker threads can outlive every client. Never unload the image.
(defonce ^:private image
  (delay
    (let [file (extract-library! (clojure.lang.RT/baseLoader))
          lib (NativeLibrary/getInstance (.getAbsolutePath file))]
      (when-not (= file (.getCanonicalFile (.getFile lib)))
        (throw (ex-info "JNA did not load the verified Copilot runtime image" {})))
      (let [start (.getFunction lib "copilot_runtime_host_start")
            open (.getFunction lib "copilot_runtime_connection_open")
            write (.getFunction lib "copilot_runtime_connection_write")
            close (.getFunction lib "copilot_runtime_connection_close")
            configure-ui (.getFunction lib "copilot_runtime_connection_set_ui_thread_handler")
            run-ui (.getFunction lib "copilot_runtime_connection_run_ui_action")
            shutdown (.getFunction lib "copilot_runtime_host_shutdown")]
        ;; All published classifiers have 64-bit size_t (including Windows).
        ;; Handles on the other hand are uint32_t. Pass their bits as JNA int.
        {:library lib
         :start (fn [^bytes argv ^bytes env]
                  (.invokeInt start (object-array [argv (long (alength argv)) env (long (alength env))])))
         :open (fn [host callback]
                 (.invokeInt open (object-array [(int host) callback nil nil 0 nil 0 nil 0])))
         :write (fn [conn ^bytes frame] (native-true? write (int conn) frame (long (alength frame))))
         :configure-ui (fn [conn enabled?] (native-true? configure-ui (int conn) (byte (if enabled? 1 0))))
         :run-ui-action (fn [conn id] (native-true? run-ui (int conn) (unchecked-int id)))
         :close (fn [conn] (native-true? close (int conn)))
         :shutdown (fn [host] (native-true? shutdown (int host)))}))))

(defn- check-options! [opts allowed path]
  (when-not (and (map? opts) (every? allowed (keys opts)))
    (throw (ex-info "Unsupported native host options" {:path path :allowed allowed}))))

(def ^:private empty-mode-env-keys
  #{"COPILOT_SDK_AUTH_TOKEN" "COPILOT_GITHUB_TOKEN" "GH_TOKEN" "GITHUB_TOKEN"
    "GH_ENTERPRISE_TOKEN" "GITHUB_ENTERPRISE_TOKEN" "GITHUB_COPILOT_API_TOKEN"
    "COPILOT_CONNECTION_TOKEN" "COPILOT_HOME" "COPILOT_CACHE_HOME"})

(def ^:private telemetry-env-keys
  {:otlp-endpoint "OTEL_EXPORTER_OTLP_ENDPOINT" :otlp-protocol "OTEL_EXPORTER_OTLP_PROTOCOL"
   :file-path "COPILOT_OTEL_FILE_EXPORTER_PATH" :exporter-type "COPILOT_OTEL_EXPORTER_TYPE"
   :source-name "COPILOT_OTEL_SOURCE_NAME"})

(defn- telemetry-env [env config]
  (check-options! config (conj (set (keys telemetry-env-keys)) :capture-content?) [:observability :telemetry])
  (reduce-kv
   (fn [env k v]
     (when-not (case k
                 :capture-content? (boolean? v)
                 :otlp-protocol (#{"http/json" "http/protobuf"} v)
                 (string? v))
       (throw (ex-info "Invalid native host option" {:path [:observability :telemetry k]})))
     (if (= k :capture-content?)
       (assoc env "OTEL_INSTRUMENTATION_GENAI_CAPTURE_CONTENT" (if v "SPAN_ONLY" "NO_CONTENT")
              "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT" (str v))
       (assoc env (telemetry-env-keys k) v)))
   (assoc env "COPILOT_OTEL_ENABLED" "true") config))

(defn- host-config [{:keys [mode base-directory session-fs github session-idle-timeout-seconds
                            observability] :as opts} ambient]
  (check-options! opts #{:mode :base-directory :session-fs :github
                         :session-idle-timeout-seconds :observability} [])
  (when (contains? opts :github)
    (check-options! github #{:token :use-logged-in-user?} [:github]))
  (when (contains? opts :observability)
    (check-options! observability #{:log-level :telemetry} [:observability]))
  (let [{:keys [token use-logged-in-user?]} github
        {:keys [log-level]} observability]
    (doseq [[valid? path] [[(#{:empty :coding-agent} mode) [:mode]]
                           [(or (not (contains? opts :base-directory))
                                (s/valid? ::specs/non-blank-string base-directory)) [:base-directory]]
                           [(or (not (contains? opts :session-fs))
                                (s/valid? ::specs/session-fs session-fs)) [:session-fs]]
                           [(or (not (contains? opts :session-idle-timeout-seconds))
                                (s/valid? ::specs/session-idle-timeout-seconds
                                          session-idle-timeout-seconds)) [:session-idle-timeout-seconds]]
                           [(or (not (contains? github :token)) (string? token)) [:github :token]]
                           [(or (not (contains? github :use-logged-in-user?))
                                (boolean? use-logged-in-user?)) [:github :use-logged-in-user?]]
                           [(or (not (contains? observability :log-level))
                                (s/valid? ::specs/log-level log-level)) [:observability :log-level]]]]
      (when-not valid?
        (throw (ex-info "Invalid native host option" {:path path}))))
    (when (and (= :empty mode) (not (or base-directory session-fs)))
      (throw (ex-info "Empty mode requires :base-directory or :session-fs" {})))
    (let [env (into {} (keep (fn [[k v]]
                               (let [k (if (Platform/isWindows) (str/upper-case k) k)]
                                 (when-not (and (= mode :empty) (empty-mode-env-keys k)) [k v]))))
                    ambient)]
      {:argv (cond-> []
               log-level (conj "--log-level" (name log-level))
               (seq token) (conj "--auth-token-env" "COPILOT_SDK_AUTH_TOKEN")
               (not (get github :use-logged-in-user? (and (= mode :coding-agent) (empty? token))))
               (conj "--no-auto-login")
               (pos? (or session-idle-timeout-seconds 0))
               (conj "--session-idle-timeout" (str session-idle-timeout-seconds)))
       :env (cond-> (dissoc env "NODE_DEBUG")
              (seq token) (assoc "COPILOT_SDK_AUTH_TOKEN" token)
              base-directory (assoc "COPILOT_HOME" base-directory
                                    "COPILOT_CACHE_HOME" (str (io/file base-directory "cache")))
              (= :empty mode) (assoc "COPILOT_DISABLE_KEYTAR" "1")
              (= :coding-agent mode) (assoc "COPILOT_RUNTIME_PROCESS_FILE_LOGGING" "1")
              (contains? observability :telemetry) (telemetry-env (:telemetry observability)))})))

(def ^:private eof (Object.))

(defn- inbound [chunk-limit byte-limit]
  ;; A callback may not wait for the reader: native close can itself be waiting
  ;; for that callback. Bound both the number of chunks and their total bytes.
  (let [state (volatile! {:queue (ArrayDeque.)})
        budget (Semaphore. byte-limit)
        terminate! (fn [reason]
                     (locking state
                       (when-not (:terminal @state)
                         (vreset! state {:terminal reason})
                         (.notifyAll state))))
        enqueue! (fn [^bytes chunk]
                   (locking state
                     (when-let [^ArrayDeque queue (:queue @state)]
                       (when (= (.size queue) chunk-limit)
                         (throw (IOException. "Copilot runtime callback exceeded input capacity")))
                       (.add queue (ByteArrayInputStream. chunk))
                       (.notifyAll state))))
        read! (fn [^bytes buf off len]
                (Objects/checkFromIndexSize (int off) (int len) (alength buf))
                (if (zero? len)
                  0
                  (locking state
                    (loop []
                      (let [{:keys [^ArrayDeque queue current terminal]} @state]
                        (cond
                          (identical? eof terminal) -1
                          terminal (throw (IOException. "Copilot runtime input failed" ^Throwable terminal))
                          :else
                          (if-let [^ByteArrayInputStream chunk (or current (.poll queue))]
                            (let [n (.read chunk buf off len)
                                  remaining (when (pos? (.available chunk)) chunk)]
                              (when-not (identical? current remaining)
                                (vswap! state assoc :current remaining))
                              (.release budget n)
                              n)
                            (do
                              (try (.wait state)
                                   (catch InterruptedException e
                                     (.interrupt (Thread/currentThread))
                                     (throw (IOException. "Interrupted reading from Copilot runtime" e))))
                              (recur)))))))))]
    {:in (proxy [InputStream] []
           (read
             ([] (let [b (byte-array 1)]
                   (if (neg? (read! b 0 1)) -1 (bit-and 255 (aget b 0)))))
             ([b] (read! b 0 (alength ^bytes b)))
             ([b off len] (read! b off len)))
           (close [] (terminate! eof)))
     :state state
     :terminate! terminate!
     :callback (reify CallbackProxy
                 (getParameterTypes [_] (into-array Class [Pointer Pointer Long/TYPE]))
                 (getReturnType [_] Void/TYPE)
                 (callback [_ args]
                   (try
                     (when-not (:terminal @state)
                       (let [^Pointer data (aget args 1)
                             length (long (aget args 2))]
                         (when-not (zero? length)
                           (when-not (and data (<= 1 length byte-limit) (.tryAcquire budget (int length)))
                             (throw (IOException. "Copilot runtime callback exceeded input capacity or had invalid data")))
                           (enqueue! (.getByteArray data 0 (int length))))))
                     (catch Throwable t
                       (terminate! t)))
                   nil))}))

;; Reserve the callback and its possible cleanup slot *before* entering Rust.
;; Quarantines count against the same budget as live and closing hosts.
(defonce ^:private owners (atom {}))
(defonce ^:private ^ConcurrentHashMap ui-owners (ConcurrentHashMap.))
(def ^:private max-owners 1024)

(defn- reserve! [callback]
  (swap! owners (fn [registry]
                  (when (>= (count registry) max-owners)
                    (throw (ex-info "Copilot native host capacity exhausted"
                                    {:capacity max-owners :states (frequencies (vals registry))})))
                  (assoc registry callback :active))))

(defonce ^:private ^ScheduledThreadPoolExecutor cleanup-worker
  (doto (ScheduledThreadPoolExecutor.
         1 (reify ThreadFactory
             (newThread [_ r] (doto (Thread. nil r "copilot-native-close" 0 false) (.setDaemon true)))))
    (.setRemoveOnCancelPolicy true)))

(def ^:private close-window-ms 1000)

(defn- quiescent? [advance!]
  (let [deadline (+ (System/nanoTime) (.toNanos TimeUnit/MILLISECONDS close-window-ms))
        interrupted? (volatile! (Thread/interrupted))]
    (try
      (loop []
        (cond
          (advance!) true
          (>= (System/nanoTime) deadline) false
          :else (do
                  (try (Thread/sleep 10)
                       (catch InterruptedException _ (vreset! interrupted? true)))
                  (recur))))
      (finally
        (when @interrupted? (.interrupt (Thread/currentThread)))))))

(defn- host-transport [{:keys [start open write close shutdown release-home! configure-ui run-ui-action]} argv env]
  (let [{:keys [in callback state terminate!]} (inbound 1024 (* 64 1024 1024))
        conn-id (volatile! nil)
        ;; Disabling UI prevents new admissions but does not revoke calls already admitted.
        ui-enabled? (volatile! false)
        ;; Close waits under conn-id, so admitted calls must release their count without it.
        ui-calls (atom 0)
        close-confirmed? (volatile! false)
        _ (try (reserve! callback)
               (catch Throwable t
                 (when release-home! (teardown/cleanup-preserving! t release-home!))
                 (throw t)))
        host-id (try (start argv env)
                     (catch Throwable t
                       ;; Startup registered no callback, but the host handle is unknown.
                       (swap! owners assoc callback :quarantined)
                       (throw (ex-info "Native start outcome unknown; host quarantined" {} t))))
        task (volatile! nil)
        stop-task! (fn []
                     (when-let [^ScheduledFuture f @task]
                       (.cancel f false)
                       (vreset! task nil)))
        quarantine! (fn [error]
                      (stop-task!)
                      (swap! owners assoc callback :quarantined)
                      (throw (vreset! conn-id error)))
        ;; Invoked under conn-id by both the blocking caller and the one retry
        ;; worker. Each background tick takes at most one native close attempt.
        advance! (fn []
                   (let [id @conn-id]
                     (cond
                       (= :closed id) true
                       ;; A failed deletion is retryable; native retirement is not.
                       (= :retired id) (do (when release-home! (release-home!))
                                           (vreset! conn-id :closed)
                                           true)
                       (= :opening id) (quarantine! (ex-info "Native connection open outcome unknown; host quarantined"
                                                             {:host-id host-id}))
                       (instance? Throwable id) (throw id)
                       (or (nil? id) (zero? id)
                           (and
                            ;; Native close cancels UI work. Its callback barrier
                            ;; does not imply that a synchronous UI call has returned.
                            (or @close-confirmed?
                                (try (vreset! close-confirmed? (close id))
                                     (catch Throwable t
                                       (quarantine! (ex-info "Native close outcome unknown; connection quarantined"
                                                             {:host-id host-id :connection-id id} t)))))
                            (zero? @ui-calls)))
                       (do (when id (.remove ui-owners id callback))
                           (stop-task!)
                           (try
                             (when-not (shutdown host-id)
                               (throw (ex-info "Copilot runtime host shutdown failed" {:host-id host-id})))
                             (catch Throwable t (quarantine! t)))
                           (vreset! conn-id :retired)
                           (swap! owners dissoc callback)
                           (recur))
                       :else false)))
        close! (fn []
                 (terminate! eof)
                 (locking conn-id
                   (when-not (quiescent? advance!)
                     (when-not @task
                       (swap! owners assoc callback :closing)
                       (try
                         (vreset! task
                                  (.scheduleWithFixedDelay
                                   cleanup-worker
                                   ^Runnable (fn []
                                               (locking conn-id
                                                 (when @task
                                                   (try (advance!)
                                                        (catch Throwable _
                                                          ;; Do not log ABI exception text or client options.
                                                          (log/error "Deferred host cleanup failed "
                                                                     {:host-id host-id
                                                                      :quarantined? (instance? Throwable @conn-id)}))))))
                                   100 100 TimeUnit/MILLISECONDS))
                         (catch Throwable t
                           (quarantine! (ex-info "Cannot schedule native cleanup; host quarantined"
                                                 {:host-id host-id :connection-id @conn-id} t)))))
                     (throw (ex-info "Native connection not yet quiescent; cleanup scheduled"
                                     {:host-id host-id :connection-id @conn-id :retryable? true})))))
        live-id! (fn []
                   (when (:terminal @state) (throw (IOException. "Copilot runtime connection is closed")))
                   @conn-id)
        send! (fn [^bytes buf off len]
                (Objects/checkFromIndexSize (int off) (int len) (alength buf))
                (locking conn-id
                  (live-id!)
                  (when (pos? len)
                    (let [frame (if (and (zero? off) (= len (alength buf))) buf
                                    (Arrays/copyOfRange buf (int off) (int (+ off len))))]
                      (try
                        (when-not (write @conn-id frame)
                          (throw (IOException. "Copilot runtime connection write failed")))
                        (catch Throwable t
                          (terminate! t)
                          (throw t)))))))]
    (when (zero? host-id)
      (swap! owners dissoc callback)
      (let [error (ex-info "Copilot runtime host start failed" {})]
        (when release-home! (teardown/cleanup-preserving! error release-home!))
        (throw error)))
    (try
      (when (.isInterrupted (Thread/currentThread))
        (throw (InterruptedException. "Interrupted during Copilot runtime host startup")))
      (vreset! conn-id :opening)
      (vreset! conn-id (open host-id callback))
      (when (zero? @conn-id)
        (throw (ex-info "Copilot runtime connection open failed" {:host-id host-id})))
      (when (.isInterrupted (Thread/currentThread))
        (throw (InterruptedException. "Interrupted during Copilot runtime connection startup")))
      (when-let [failure (:terminal @state)]
        (throw (IOException. "Copilot runtime input failed during startup" ^Throwable failure)))
      {:in in
       :out (proxy [OutputStream] []
              (write
                ([b] (if (bytes? b) (send! b 0 (alength ^bytes b))
                         (send! (byte-array [(unchecked-byte b)]) 0 1)))
                ([b off len] (send! b off len))))
       :configure-ui! (fn [enabled?]
                        (when-not (boolean? enabled?)
                          (throw (ex-info "UI configuration requires a boolean" {})))
                        (locking conn-id
                          (let [id (live-id!)]
                            ;; A preempted UI caller can still enter Rust after
                            ;; native close. Do not register a recycled uint32 ID
                            ;; for another owner until that caller has returned.
                            (when enabled?
                              (when-let [owner (.putIfAbsent ui-owners id callback)]
                                (when-not (identical? owner callback)
                                  (throw (ex-info "UI connection ID is still owned by a closing host" {})))))
                            (when-not (configure-ui id enabled?)
                              (throw (ex-info "Copilot runtime rejected the UI thread configuration" {})))
                            (vreset! ui-enabled? enabled?)))
                        nil)
       :run-ui-action! (fn [action-id]
                         (when-not (and (integer? action-id) (<= 0 action-id 0xffffffff))
                           (throw (ex-info "UI action ID must be an unsigned 32-bit integer" {})))
                         (let [id (locking conn-id
                                    (let [id (live-id!)]
                                      (when-not @ui-enabled?
                                        (throw (ex-info "The UI thread handler is not enabled" {})))
                                      (swap! ui-calls inc)
                                      id))]
                           ;; UI code must run on this thread, without holding the writer/close lock.
                           (try
                             (when-not (run-ui-action id (long action-id))
                               (throw (ex-info "Native UI action is retired, executed, or on the wrong thread"
                                               {:action-id action-id})))
                             (finally (swap! ui-calls dec)))))
       :close! close!}
      (catch Throwable t
        (teardown/cleanup-preserving! t close!)
        (throw t)))))

(defn- delete-home! [^Path home]
  ;; Session tools can create symlinks: walk the private directory, never their targets.
  (try
    (with-open [paths (Files/walk home (make-array FileVisitOption 0))]
      (doseq [^Path p (rseq (vec (iterator-seq (.iterator paths))))]
        (Files/deleteIfExists p)))
    (catch UncheckedIOException e (throw (.getCause e)))
    (catch NoSuchFileException e
      (when-not (= (.getFile e) (str home)) (throw e)))))

(defonce ^:private ^ThreadPoolExecutor home-retries
  (ThreadPoolExecutor. 0 1 30 TimeUnit/SECONDS (ArrayBlockingQueue. max-owners)
                       (reify ThreadFactory
                         (newThread [_ r] (doto (Thread. nil r "copilot-home-cleanup" 0 false) (.setDaemon true))))))

(defn- private-home-release [^Path home]
  (let [removed? (volatile! false)
        remove! (fn [] (locking removed?
                         (when-not @removed?
                           (delete-home! home)
                           (vreset! removed? true))))
        ;; A failed open has no public client to retry the directory cleanup.
        ;; Keep only the directory, never a native callback or handle, in this task.
        retry (delay (.execute home-retries
                               ^Runnable #(try (remove!)
                                               (catch IOException e
                                                 (log/error "Private runtime home cleanup retry failed"
                                                            {:directory (str (.getFileName home))
                                                             :exception (.getName (class e))})))))]
    (fn []
      (try (remove!)
           (catch IOException e
             (let [error (ex-info "Cannot remove the SDK-created temporary home" {:type :private-home-cleanup :path (str home)} e)]
               (try (force retry)
                    (catch RejectedExecutionException full (.addSuppressed error full)))
               (throw error)))))))

(defn- open-config! [abi {:keys [argv env]} virtual-home?]
  (let [argv (.getBytes (json/write-str argv) StandardCharsets/UTF_8)
        home (when virtual-home? (Files/createTempDirectory "copilot-sdk-home-" default-attrs))
        release! (when home (private-home-release home))
        ^bytes env-bytes (try
                           (.getBytes (json/write-str
                                       (cond-> env home (assoc "COPILOT_HOME" (str home)
                                                               "COPILOT_CACHE_HOME" (str (.resolve home "cache")))))
                                      StandardCharsets/UTF_8)
                           (catch Throwable t
                             (when release! (teardown/cleanup-preserving! t release!))
                             (throw t)))]
    (try
      (host-transport (assoc abi :release-home! release!) argv env-bytes)
      (finally (Arrays/fill env-bytes (byte 0))))))

(defn open!
  "Open an owned Rust host and connection on the caller's blocking worker.
   Pass only host options. The caller must invoke :close!, not just close
   the streams. A close timeout registers a bounded shared cleanup owner.
   The native image is shared and stays loaded until JVM exit.
   After installing reverse handlers, call :configure-ui! with whether a UI
   scheduler is present, including false for headless clients. :run-ui-action!
   must be invoked synchronously on the selected UI thread with the runtime's ID.

   Unlike the Node SDK default, :empty strips ambient GitHub/Copilot auth
   tokens and disables automatic login unless explicitly enabled. A virtual
   :session-fs without :base-directory gets a private temporary runtime home
   through native shutdown; it does not use the process Copilot home or cache.
   These are runtime settings, not a sandbox for tools or code in this JVM."
  [opts]
  (let [config (host-config opts (System/getenv))]
    (open-config! @image config (and (= :empty (:mode opts)) (not (:base-directory opts))))))
