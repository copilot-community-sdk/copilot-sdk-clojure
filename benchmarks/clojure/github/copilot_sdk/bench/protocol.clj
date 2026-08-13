(ns github.copilot-sdk.bench.protocol
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.lang ProcessHandle]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def header-separator (.getBytes "\r\n\r\n" StandardCharsets/US_ASCII))

(defonce ^:private process-registry
  (atom {:shutting-down? false :processes #{}}))
(defonce ^:private process-registry-lock (Object.))
(defonce ^:private shutdown-hook (atom nil))

(def ^:dynamic *after-process-start*
  (fn [_process]))

(defn encode-frame
  [payload]
  (let [body (.getBytes ^String payload StandardCharsets/UTF_8)
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n")
                          StandardCharsets/US_ASCII)
        output (ByteArrayOutputStream.)]
    (.write output header)
    (.write output body)
    (.toByteArray output)))

(defn- separator-index
  [bytes]
  (let [limit (- (alength bytes) (alength header-separator))]
    (loop [index 0]
      (cond
        (> index limit) nil
        (every? true?
                (map-indexed (fn [offset expected]
                               (= expected (aget bytes (+ index offset))))
                             header-separator))
        index
        :else (recur (inc index))))))

(defn decode-frame
  [bytes]
  (let [separator (separator-index bytes)]
    (when-not separator
      (throw (ex-info "Incomplete JSON-RPC frame header" {})))
    (let [header (String. bytes 0 separator StandardCharsets/US_ASCII)
          content-length (some->> (str/split-lines header)
                                  (some (fn [line]
                                          (when-let [[_ value]
                                                     (re-matches
                                                      #"(?i)Content-Length:\s*(\d+)"
                                                      line)]
                                            (parse-long value)))))]
      (when-not content-length
        (throw (ex-info "Missing Content-Length" {:header header})))
      (let [body-offset (+ separator (alength header-separator))
            available (- (alength bytes) body-offset)]
        (when (< available content-length)
          (throw (ex-info "Incomplete JSON-RPC frame"
                          {:expected content-length :available available})))
        (String. bytes body-offset content-length StandardCharsets/UTF_8)))))

(defn- read-stream
  [^InputStream stream]
  (with-open [input stream]
    (slurp input)))

(defn- process-descendants
  [^Process process]
  (with-open [stream (.descendants (.toHandle process))]
    (vec (iterator-seq (.iterator stream)))))

(defn- handle-depth
  [^ProcessHandle root ^ProcessHandle handle]
  (loop [current handle
         depth 0]
    (if (= (.pid root) (.pid current))
      depth
      (if-let [parent (.orElse (.parent current) nil)]
        (recur parent (inc depth))
        depth))))

(defn termination-handles
  [^Process process]
  (let [root (.toHandle process)]
    (->> (conj (process-descendants process) root)
         (sort-by #(handle-depth root %) >)
         vec)))

(defn- await-dead!
  [handles timeout-ms]
  (let [deadline (+ (System/nanoTime) (* timeout-ms 1000000))]
    (loop []
      (let [alive (filterv #(.isAlive ^ProcessHandle %) handles)]
        (cond
          (empty? alive) true
          (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
          :else false)))))

(defn terminate-process!
  [^Process process]
  (let [handles (termination-handles process)]
    (doseq [^ProcessHandle handle handles
            :when (.isAlive handle)]
      (.destroy handle))
    (when-not (await-dead! handles 1000)
      (doseq [^ProcessHandle handle handles
              :when (.isAlive handle)]
        (.destroyForcibly handle))
      (when-not (await-dead! handles 5000)
        (throw (ex-info "Process tree cleanup was not confirmed"
                        {:survivors (mapv #(.pid ^ProcessHandle %)
                                          (filter #(.isAlive ^ProcessHandle %)
                                                  handles))}))))
    (when-not (.waitFor process 5000 TimeUnit/MILLISECONDS)
      (throw (ex-info "Root process could not be reaped after termination"
                      {:pid (.pid process)})))
    (when (.isAlive process)
      (throw (ex-info "Root process cleanup was not confirmed"
                      {:pid (.pid process)})))))

(defn cleanup-tracked-processes!
  []
  (locking process-registry-lock
    (swap! process-registry assoc :shutting-down? true))
  (loop [attempted #{}
         errors []]
    (let [processes (:processes @process-registry)
          pending (remove attempted processes)]
      (if (seq pending)
        (let [[attempted errors]
              (reduce
               (fn [[seen failures] ^Process process]
                 (try
                   (when (.isAlive process)
                     (terminate-process! process))
                   (when-not (.isAlive process)
                     (swap! process-registry update :processes disj process))
                   [(conj seen process) failures]
                   (catch Throwable error
                     [(conj seen process) (conj failures error)])))
               [attempted errors]
               pending)]
          (recur attempted errors))
        errors))))

(defn install-shutdown-hook!
  []
  (or @shutdown-hook
      (locking shutdown-hook
        (or @shutdown-hook
            (let [hook (Thread. ^Runnable
                        (fn [] (cleanup-tracked-processes!))
                                "copilot-benchmark-process-cleanup")]
              (.addShutdownHook (Runtime/getRuntime) hook)
              (reset! shutdown-hook hook)
              hook)))))

(defn register-process!
  [^Process process]
  (install-shutdown-hook!)
  (let [late? (locking process-registry-lock
                (let [late? (:shutting-down? @process-registry)]
                  (swap! process-registry update :processes conj process)
                  late?))]
    (when late?
      (try
        (when (.isAlive process)
          (terminate-process! process))
        (finally
          (when-not (.isAlive process)
            (swap! process-registry update :processes disj process)))))
    process))

(defn start-process!
  [^ProcessBuilder builder]
  (install-shutdown-hook!)
  (locking process-registry-lock
    (when (:shutting-down? @process-registry)
      (throw (ex-info "Benchmark process creation refused during shutdown" {})))
    (let [process (.start builder)]
      (try
        (*after-process-start* process)
        (swap! process-registry update :processes conj process)
        process
        (catch Throwable error
          (when (.isAlive process)
            (terminate-process! process))
          (throw error))))))

(defn unregister-process!
  [^Process process]
  (when (.isAlive process)
    (throw (ex-info "Cannot unregister a live benchmark process"
                    {:pid (.pid process)})))
  (swap! process-registry update :processes disj process)
  nil)

(defn tracked-process-count
  []
  (count (:processes @process-registry)))

(defn process-registry-state
  []
  @process-registry)

(defn reset-process-registry-for-tests!
  []
  (locking process-registry-lock
    (when (some #(.isAlive ^Process %) (:processes @process-registry))
      (throw (ex-info "Cannot reset registry with live processes" {})))
    (reset! process-registry {:shutting-down? false :processes #{}}))
  nil)

(defn shutdown-hook-instance
  []
  @shutdown-hook)

(install-shutdown-hook!)

(defn- completed-stream!
  [stream-future stream-name]
  (let [result (deref stream-future 5000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Process stream reader did not complete"
                      {:stream stream-name})))
    result))

(defn run-process!
  [command {:keys [timeout-ms env dir]
            :or {timeout-ms 30000}}]
  (let [builder (ProcessBuilder. ^java.util.List command)
        _ (when dir (.directory builder (java.io.File. ^String dir)))
        process-env (.environment builder)
        _ (doseq [[key value] env]
            (.put process-env (name key) (str value)))
        process (start-process! builder)]
    (try
      (let [stdout-future (future (read-stream (.getInputStream process)))
            stderr-future (future (read-stream (.getErrorStream process)))
            completed? (.waitFor process (long timeout-ms) TimeUnit/MILLISECONDS)]
        (when-not completed?
          (terminate-process! process))
        (when-not (.waitFor process 5000 TimeUnit/MILLISECONDS)
          (terminate-process! process))
        {:exit (when-not (.isAlive process) (.exitValue process))
         :stdout (completed-stream! stdout-future :stdout)
         :stderr (completed-stream! stderr-future :stderr)
         :timed-out? (not completed?)
         :alive? (.isAlive process)})
      (finally
        (unregister-process! process)))))
