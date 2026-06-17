(ns github.copilot-sdk.process
  "Process management for spawning and managing the Copilot CLI."
  (:require [clojure.core.async :as async :refer [chan close! >!!]]
            [clojure.string :as str])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]
           [java.io File]
           [java.net Socket]))

(defrecord ManagedProcess
           [^Process process
            stdin
            stdout
            stderr
            exit-chan])

(defn- build-cli-args
  "Build CLI arguments based on options."
  [{:keys [log-level use-stdio? port cli-args github-token use-logged-in-user? remote?
           session-idle-timeout-seconds]}]
  (cond-> (vec (or cli-args []))
    true (conj "--server")
    true (conj "--no-auto-update")
    log-level (conj "--log-level" (name log-level))
    use-stdio? (conj "--stdio")
    (and (not use-stdio?) port (pos? port)) (conj "--port" (str port))
    ;; Auth options (PR #237)
    github-token (conj "--auth-token-env")
    (false? use-logged-in-user?) (conj "--no-auto-login")
    ;; Server-wide session idle timeout (upstream client.ts: --session-idle-timeout)
    (and session-idle-timeout-seconds (pos? session-idle-timeout-seconds))
    (conj "--session-idle-timeout" (str session-idle-timeout-seconds))
    ;; Remote session support (upstream PR #1192)
    remote? (conj "--remote")))

(defn cli-env-overrides
  "Compute the environment variable contract the SDK applies to the spawned CLI.

   Returns a map with two entries:
   - `:defaults` — env-var → value-or-nil applied **before** user-provided
     `:env` so the user can override them. Currently just `{\"NODE_DEBUG\" nil}`
     (default: strip NODE_DEBUG to avoid polluting stdout, but a user can
     re-enable it via `:env`).
   - `:overrides` — env-var → value-or-nil applied **after** user-provided
     `:env` so explicit SDK options win over stale env (e.g. an explicit
     `:github-token` must beat a `COPILOT_SDK_AUTH_TOKEN` coming from `:env`).

   This is a pure helper extracted so we can unit-test the env contract
   without spawning a real process."
  [{:keys [github-token telemetry copilot-home tcp-connection-token mode] :as _opts}]
  {:defaults {"NODE_DEBUG" nil}
   :overrides
   (cond-> {}
     ;; Auth token (PR #237)
     github-token
     (assoc "COPILOT_SDK_AUTH_TOKEN" github-token)
     ;; copilotHome (upstream PR #1191) — base directory for Copilot data
     copilot-home
     (assoc "COPILOT_HOME" copilot-home)
     ;; tcpConnectionToken (upstream PR #1176) — required when server enforces a token
     tcp-connection-token
     (assoc "COPILOT_CONNECTION_TOKEN" tcp-connection-token)
     ;; Client mode :empty disables the system keychain (upstream PR #1428).
     ;; Applied in :overrides so the caller's `:env` cannot accidentally
     ;; re-enable it under multitenancy hardening.
     (= mode :empty)
     (assoc "COPILOT_DISABLE_KEYTAR" "1")
     ;; OpenTelemetry (upstream PR #785)
     telemetry
     (as-> m
           (cond-> (assoc m "COPILOT_OTEL_ENABLED" "true")
             (:otlp-endpoint telemetry)
             (assoc "OTEL_EXPORTER_OTLP_ENDPOINT" (:otlp-endpoint telemetry))
             (:file-path telemetry)
             (assoc "COPILOT_OTEL_FILE_EXPORTER_PATH" (:file-path telemetry))
             (:exporter-type telemetry)
             (assoc "COPILOT_OTEL_EXPORTER_TYPE" (:exporter-type telemetry))
             (:source-name telemetry)
             (assoc "COPILOT_OTEL_SOURCE_NAME" (:source-name telemetry))
             (:otlp-protocol telemetry)
             (assoc "OTEL_EXPORTER_OTLP_PROTOCOL" (:otlp-protocol telemetry))
             (some? (:capture-content? telemetry))
             (assoc "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT"
                    (str (:capture-content? telemetry))))))})

(defn spawn-cli
  "Spawn the Copilot CLI process.
   
   Options:
   - :cli-path - Path to CLI executable (default: \"copilot\")
   - :cli-args - Extra args to prepend
   - :cwd - Working directory
   - :env - Environment variables map
   - :log-level - Log level (:none :error :warning :info :debug :all)
   - :use-stdio? - Use stdio transport (default: true)
   - :port - TCP port (when not using stdio)
   - :github-token - GitHub token for authentication (PR #237)
   - :use-logged-in-user? - Whether to use logged-in user auth (PR #237)
   - :copilot-home - Base directory for Copilot data (sets COPILOT_HOME) (upstream PR #1191)
   - :tcp-connection-token - Connection token sent via COPILOT_CONNECTION_TOKEN (upstream PR #1176)
   - :remote? - When true, append `--remote` so the CLI exposes its session
                over a GitHub-hosted remote endpoint (upstream PR #1192)
   - :mode - Client mode `:empty` or `:copilot-cli` (default). When `:empty`,
             `COPILOT_DISABLE_KEYTAR=1` is forced into the spawned env so the
             child CLI cannot reach the host keychain (upstream PR #1428).

   Returns a ManagedProcess record."
  [{:keys [cli-path cwd env use-stdio?]
    :or {cli-path "copilot"
         use-stdio? true}
    :as opts}]
  (let [args (build-cli-args (assoc opts :use-stdio? use-stdio?))
        full-cmd (into [cli-path] args)
        builder (ProcessBuilder. ^java.util.List full-cmd)]

    ;; Set working directory
    (when cwd
      (.directory builder (File. ^String cwd)))

    ;; Set environment
    (let [env-map (.environment builder)
          ;; SDK env contract: defaults applied first (user :env can override
          ;; them), then user :env, then strict overrides (which beat :env).
          {:keys [defaults overrides]} (cli-env-overrides opts)
          apply-map! (fn [m]
                       (doseq [[k v] m]
                         (if (some? v)
                           (.put env-map ^String k ^String v)
                           (.remove env-map ^String k))))]
      (apply-map! defaults)
      (when env
        (doseq [[k v] env]
          (if (some? v)
            (.put env-map k v)
            (.remove env-map k))))
      (apply-map! overrides))

    ;; Configure stdio — use explicit PIPE redirects for all three streams.
    ;; On Windows, the JVM's ProcessImpl sets CREATE_NO_WINDOW when none of the
    ;; child's stdio handles are inherited from the parent console. By ensuring
    ;; PIPE (not INHERIT) for stdin, stdout, and stderr, we guarantee no
    ;; console window appears — equivalent to upstream windowsHide: true (PR #329).
    (.redirectInput builder ProcessBuilder$Redirect/PIPE)
    (.redirectOutput builder ProcessBuilder$Redirect/PIPE)
    (.redirectError builder ProcessBuilder$Redirect/PIPE)
    (.redirectErrorStream builder false)

    (let [process (.start builder)
          exit-chan (chan 1)]

      ;; Monitor process exit. `.waitFor` blocks the calling thread until the
      ;; child exits, so it must run on a real thread (async/thread), never a
      ;; go block (which would park a shared dispatch thread for the process
      ;; lifetime).
      (async/thread
        (let [exit-code (.waitFor process)]
          (>!! exit-chan {:exit-code exit-code})
          (close! exit-chan)))

      (map->ManagedProcess
       {:process process
        :stdin (.getOutputStream process)
        :stdout (.getInputStream process)
        :stderr (.getErrorStream process)
        :exit-chan exit-chan}))))

(defn destroy!
  "Destroy the process gracefully with timeout."
  ([^ManagedProcess mp]
   (destroy! mp 5000))
  ([^ManagedProcess mp timeout-ms]
   (when-let [^Process p (:process mp)]
     (.destroy p)
     ;; Wait for process to exit with timeout
     (let [exited (try
                    (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                    (catch Exception _ false))]
       (when-not exited
         ;; Force kill if still running
         (.destroyForcibly p)
         (try
           (.waitFor p 2000 java.util.concurrent.TimeUnit/MILLISECONDS)
           (catch Exception _)))))))

(defn destroy-forcibly!
  "Force destroy the process."
  [^ManagedProcess mp]
  (when-let [^Process p (:process mp)]
    (.destroyForcibly p)))

(defn alive?
  "Check if process is still running."
  [^ManagedProcess mp]
  (when-let [^Process p (:process mp)]
    (.isAlive p)))

(defn wait-for-exit!
  "Block until the process exits or timeout-ms elapses.
   Returns true if the process exited, false on timeout.

   Uses `Process.waitFor`, which is safe to call concurrently with the
   exit-chan monitor thread (and any other waiters) on the same process.
   When there is no underlying process, returns true (nothing to wait for)."
  [^ManagedProcess mp timeout-ms]
  (if-let [^Process p (:process mp)]
    (try
      (.waitFor p timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
      (catch Exception _ false))
    true))

(defn wait-for-port
  "Wait for TCP server to announce its port on stdout.
   Returns the port number or throws on timeout."
  [^ManagedProcess mp timeout-ms]
  (let [stdout (:stdout mp)
        reader (java.io.BufferedReader.
                (java.io.InputStreamReader. stdout "UTF-8"))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [buffer ""]
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "Timeout waiting for CLI server to start" {:timeout-ms timeout-ms})))
      (when-not (alive? mp)
        (throw (ex-info "CLI process exited before announcing port" {})))
      (if (.ready reader)
        (let [ch (.read reader)]
          (if (neg? ch)
            (throw (ex-info "CLI stdout closed unexpectedly" {}))
            (let [new-buffer (str buffer (char ch))
                  matcher (re-find #"listening on port (\d+)" (str/lower-case new-buffer))]
              (if matcher
                (do
                  (async/thread
                    (try
                      (loop []
                        (when (.readLine reader)
                          (recur)))
                      (catch Exception _)))
                  (parse-long (second matcher)))
                (recur new-buffer)))))
        (do
          (Thread/sleep 50)
          (recur buffer))))))

(defn connect-tcp
  "Connect to a TCP server. Returns a socket."
  [^String host ^long port ^long timeout-ms]
  (let [socket (Socket.)]
    (.connect socket
              (java.net.InetSocketAddress. host port)
              (int timeout-ms))
    socket))

(defn stderr-reader
  "Returns a channel that receives lines from stderr."
  [^ManagedProcess mp]
  (let [ch (chan 256)
        reader (java.io.BufferedReader.
                (java.io.InputStreamReader. (:stderr mp) "UTF-8"))]
    ;; `.readLine` blocks until a line, EOF, or stream close, so the read loop
    ;; must run on a real thread rather than parking a go dispatch thread.
    ;; `>!!` applies backpressure so a slow consumer can't drop stderr lines.
    (async/thread
      (try
        (loop []
          (when-let [line (.readLine reader)]
            (>!! ch {:type :stderr :line line})
            (recur)))
        (catch Exception _)
        (finally
          (close! ch))))
    ch))
