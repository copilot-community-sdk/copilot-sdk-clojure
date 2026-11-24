(ns ^:no-doc github.copilot-sdk.protocol
  "JSON-RPC 2.0 protocol implementation using java.nio channels.
   
   Architecture:
   - NIO channels for interruptible I/O (clean shutdown)
   - core.async channels for message flow
   - Single reader thread resolves responses and queues notifications
   - Notification dispatcher drains accepted messages before closing incoming-ch on EOF
   - Single writer thread takes directly from the bounded outgoing-ch
   - Reverse requests run on a bounded worker pool owned by the connection
   - State is managed externally (passed in as atom)
   
   This design allows clean shutdown: closing NIO channels causes
   reader to throw AsynchronousCloseException and exit gracefully."
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async :refer [>!! <!! chan close! put!]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [github.copilot-sdk.generated.event-metadata :as event-metadata]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.teardown :as td]
            [github.copilot-sdk.util :as util])
  (:import [java.io InputStream OutputStream IOException]
           [java.nio ByteBuffer]
           [java.nio.channels Channels ReadableByteChannel WritableByteChannel ClosedChannelException]
           [java.nio.channels AsynchronousCloseException]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ArrayBlockingQueue CompletableFuture LinkedBlockingQueue
            RejectedExecutionException ThreadFactory ThreadPoolExecutor
            ThreadPoolExecutor$AbortPolicy TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:private content-length-header "Content-Length: ")

(def ^:private request-worker-thread-name-prefix
  "Thread-name prefix for a connection's reverse-request worker pool.

   Reverse request handlers are arbitrary caller code, so they always run on a
   thread with this prefix -- never on core.async `go` dispatch. In core.async
   1.8 `go` dispatch shares the process-wide unbounded cached `:io` executor,
   so blocking there grows that pool without bound."
  "jsonrpc-request-worker-")

(def ^:no-doc default-request-handler-threads
  "Default cap on concurrently executing reverse request handlers.

   Implementation support for `client`'s option defaults, not documented API."
  16)

(def ^:no-doc default-request-handler-queue-size
  "Default number of reverse requests queued before overload is reported.

   Implementation support for `client`'s option defaults, not documented API."
  256)

;; -----------------------------------------------------------------------------
;; NIO-based Message Framing (Content-Length based, vscode-jsonrpc compatible)
;; -----------------------------------------------------------------------------

(defn- read-byte
  "Read a single byte from channel. Returns byte value or -1 on EOF."
  [^ReadableByteChannel channel ^ByteBuffer buf]
  (.clear buf)
  (.limit buf 1)
  (let [n (.read channel buf)]
    (if (pos? n)
      (do (.flip buf) (bit-and (.get buf) 0xFF))
      -1)))

(defn- read-line-bytes
  "Read a line (until CRLF or LF) from channel. Returns string or nil on EOF."
  [^ReadableByteChannel channel ^ByteBuffer single-byte-buf]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (read-byte channel single-byte-buf)]
        (cond
          (neg? b) (if (pos? (.length sb)) (str sb) nil)
          (= b 10) (str sb)  ; LF
          (= b 13) (recur)   ; CR - skip
          :else (do (.append sb (char b)) (recur)))))))

(defn- read-bytes
  "Read exactly n bytes from channel into a new byte array."
  [^ReadableByteChannel channel n]
  (let [buf (ByteBuffer/allocate n)]
    (loop [remaining n]
      (when (pos? remaining)
        (let [read (.read channel buf)]
          (when (neg? read)
            (throw (IOException. (str "EOF: expected " n " bytes, got " (- n remaining)))))
          (recur (- remaining read)))))
    (.array buf)))

(defn- read-headers
  "Read headers until empty line. Returns map of header-name -> value, or nil if connection closed."
  [^ReadableByteChannel channel ^ByteBuffer single-byte-buf]
  (loop [headers {}]
    (let [line (read-line-bytes channel single-byte-buf)]
      (cond
        (nil? line)
        nil  ;; Connection closed - return nil instead of throwing

        (str/blank? line)
        headers

        :else
        (let [[k v] (str/split line #": " 2)]
          (recur (assoc headers (str/lower-case (str/trim k)) (str/trim (or v "")))))))))

(defn- read-message
  "Read a single JSON-RPC message from channel. Returns parsed JSON map or nil on EOF/close."
  [^ReadableByteChannel channel ^ByteBuffer single-byte-buf]
  (when-let [headers (read-headers channel single-byte-buf)]
    (let [content-length (some-> (get headers "content-length") parse-long)]
      (when-not content-length
        (throw (IOException. "Missing Content-Length header")))
      (let [content-bytes (read-bytes channel content-length)
            content (String. content-bytes StandardCharsets/UTF_8)]
        (update-keys (json/read-str content) keyword)))))

(defn- write-message!
  "Write a JSON-RPC message to channel with Content-Length framing."
  [^WritableByteChannel channel msg]
  (let [json-str (json/write-str msg)
        content-bytes (.getBytes json-str StandardCharsets/UTF_8)
        header (str content-length-header (alength content-bytes) "\r\n\r\n")
        header-bytes (.getBytes header StandardCharsets/UTF_8)
        buf (ByteBuffer/allocate (+ (alength header-bytes) (alength content-bytes)))]
    (.put buf header-bytes)
    (.put buf content-bytes)
    (.flip buf)
    (while (.hasRemaining buf)
      (.write channel buf))))

;; -----------------------------------------------------------------------------
;; Connection Record - holds IO resources only, state is external
;; -----------------------------------------------------------------------------

(defrecord Connection
           [^ReadableByteChannel read-channel
            ^WritableByteChannel write-channel
            ^OutputStream output-stream   ; Keep reference for flushing
            state-atom                    ; atom owned by client, contains :connection key
            connection-id
            incoming-ch                   ; channel for incoming messages (responses + notifications)
            outgoing-ch                   ; channel for outgoing messages
            notification-queue            ; queue for notifications to avoid blocking reader
            notification-thread           ; Thread
            read-thread                   ; Thread
            request-executor              ; bounded pool running reverse request handlers
            rejected-requests             ; AtomicLong of overload-rejected reverse requests
            dropped-notifications])       ; AtomicLong of notifications dropped on a full queue

(defn- request-worker-thread-factory
  []
  (let [counter (AtomicLong.)]
    (reify ThreadFactory
      (newThread [_ runnable]
        (doto (Thread. ^Runnable runnable
                       (str request-worker-thread-name-prefix (.incrementAndGet counter)))
          (.setDaemon true))))))

(defn- make-request-executor
  "Bounded worker pool that executes reverse request handlers.

   Handlers are arbitrary caller code that may block, so they must not run on
   core.async `go` dispatch. Concurrency is capped at `threads`, bursts are
   absorbed by a bounded queue, and further submissions are rejected so the
   caller can answer with an explicit overload error instead of blocking the
   reader thread or silently discarding the request. Core threads time out, so
   an idle connection holds no worker threads."
  ^ThreadPoolExecutor [threads queue-size]
  (doto (ThreadPoolExecutor. (int threads) (int threads)
                             60 TimeUnit/SECONDS
                             (ArrayBlockingQueue. (int queue-size))
                             (request-worker-thread-factory)
                             (ThreadPoolExecutor$AbortPolicy.))
    (.allowCoreThreadTimeOut true)))

(defn- connection-stats
  "Snapshot of a connection's worker and queue counters, for diagnostics and
   tests.

   - `:dropped-notifications` -- notifications discarded because the bounded
     notification queue was full
   - `:rejected-requests` -- reverse requests answered with an overload error
     because the handler pool was saturated
   - `:active-request-workers` / `:queued-requests` -- current pool occupancy
   - `:request-workers-terminated?` -- whether the handler pool has shut down"
  [conn]
  (let [^ThreadPoolExecutor executor (:request-executor conn)]
    {:dropped-notifications (.get ^AtomicLong (:dropped-notifications conn))
     :rejected-requests (.get ^AtomicLong (:rejected-requests conn))
     :active-request-workers (.getActiveCount executor)
     :queued-requests (.size (.getQueue executor))
     :request-workers-terminated? (.isTerminated executor)}))

;; State path helpers
(defn- conn-state [state-atom] (get @state-atom :connection))
(defn- update-conn! [state-atom f & args] (apply swap! state-atom update :connection f args))

(defn- connection-running? [{:keys [state-atom connection-id]}]
  (let [state (conn-state state-atom)]
    (and (:running? state)
         (identical? connection-id (:connection-id state)))))

(def ^:private connection-closed {:code -32000 :message "Connection closed"})

(defn- close-input!
  "The first terminal error owns the pending requests. The notification
   dispatcher still owns every frame it accepted before this transition."
  [{:keys [state-atom connection-id outgoing-ch]} error]
  (let [[old new]
        (locking state-atom
          (swap-vals! state-atom
                      (fn [{:keys [connection] :as state}]
                        (if (and (:running? connection)
                                 (identical? connection-id (:connection-id connection)))
                          (update state :connection assoc :running? false :pending-requests {}
                                  :terminal-error error)
                          state))))]
    (when-not (identical? old new)
      (close! outgoing-ch)
      (doseq [[_ {:keys [ch]}] (get-in old [:connection :pending-requests])]
        (put! ch {:error error})
        (close! ch)))))

(defn- enqueue! [conn msg]
  (cond
    (not (connection-running? conn)) false
    (async/offer! (:outgoing-ch conn) msg) true
    :else (do
            (close-input! conn {:code -32000 :message "Outgoing queue full"
                                :data {:code "outgoing_queue_full"}})
            false)))

(defn ^:no-doc end-input!
  "End input on runtime exit or with a sanitized JSON-RPC error from the host.
   Reject unanswered RPCs and let accepted notifications drain before cleanup.
   An obsolete connection cannot alter a replacement connection."
  ([conn] (end-input! conn connection-closed))
  ([conn terminal-error] (close-input! conn terminal-error)))

(defn- pop-pending!
  "Atomically remove and return the pending entry for `id`, or nil if it is no
   longer registered. Using `swap-vals!` guarantees the entry is claimed by
   exactly one caller, so `handle-response!` and `close-input!` can never both
   deliver to the same response channel."
  [state-atom id]
  (let [[old _] (swap-vals! state-atom update-in [:connection :pending-requests] dissoc id)]
    (get-in old [:connection :pending-requests id])))

;; -----------------------------------------------------------------------------
;; Message Handling
;; -----------------------------------------------------------------------------

(declare normalize-incoming preserve-event-opaque-fields)

(defn- decode-record [row opaque-key idiom-key]
  (cond-> (util/wire->clj (walk/keywordize-keys (dissoc row opaque-key)))
    (contains? row opaque-key) (assoc idiom-key (get row opaque-key))))

(defn- decode-record-list [msg records opaque-key idiom-key]
  (let [rows (get-in msg [:result records])
        normalized (normalize-incoming (walk/keywordize-keys (update msg :result dissoc records)))]
    (assoc-in normalized [:result (keyword records)]
              (mapv #(decode-record % opaque-key idiom-key) rows))))

(defn- normalize-response
  "Use the originating method to preserve opaque JSON in each result shape."
  [method msg]
  (let [event (get-in msg [:result :response])]
    (cond-> (normalize-incoming msg)
      (and (= "session.sendAndWait" method) event)
      (update-in [:result :response] #(preserve-event-opaque-fields event %)))))

(defn- handle-response!
  "Handle an incoming response message. Delivers to pending channel.

   If the original `send-request` call supplied an `:on-response-inline`
   callback, invoke it synchronously **before** delivering the result to
   the response channel. This callback runs in the reader thread, so any
   work it performs (e.g. session registration after a server-assigned
   sessionId) is guaranteed to complete before the next inbound message
   is dispatched. Used by `client/create-session` for the cloud-no-id
   flow (upstream PR #1479)."
  [state-atom raw-msg]
  (let [id (:id raw-msg)]
    (log/debug "Received response for id=" id)
    ;; Atomically claim the pending entry so a concurrent close-input!
    ;; (disconnect / EOF) can never also deliver to this response channel.
    (when-let [{:keys [ch on-response-inline method raw-json?]} (pop-pending! state-atom id)]
      (try
        (let [msg (cond
                    (and raw-json? (not (:error raw-msg))) raw-msg
                    (and (= method "models.list") (vector? (get-in raw-msg [:result "models"])))
                    (decode-record-list raw-msg "models" "metadata" :metadata)
                    (and (= method "session.tools.getCurrentMetadata") (vector? (get-in raw-msg [:result "tools"])))
                    (decode-record-list raw-msg "tools" "input_schema" :input-schema)
                    (and (= method "session.ui.elicitation")
                         (= "accept" (get-in raw-msg [:result "action"]))
                         (contains? (:result raw-msg) "content"))
                    (-> (update raw-msg :result dissoc "content")
                        walk/keywordize-keys
                        normalize-incoming
                        (assoc-in [:result :content] (get-in raw-msg [:result "content"])))
                    :else (normalize-response method (walk/keywordize-keys raw-msg)))]
          (if-let [error (:error msg)]
            (do
              (log/debug "Response error: " error)
              (put! ch {:error error})
              (close! ch))
            (let [result (:result msg)]
              (log/debug "Response success for id=" id)
              (when on-response-inline
                (try
                  (on-response-inline result)
                  (catch Throwable t
                    (log/error t "on-response-inline callback threw for id=" id))))
              (put! ch {:result result})
              (close! ch))))
        (catch Throwable failure
          (log/error failure
                     "Failed to process response"
                     {:id id :method method})
          (put! ch
                {:error
                 {:code -32603
                  :message "Failed to process response"
                  :data {:id id :method method}}})
          (close! ch)
          (when-not (instance? Exception failure)
            (throw failure)))))))

(defn- preserve-outgoing-opaque-fields
  "Per-method outgoing escape hatch: after recursive kebab→camelCase
   conversion via `util/clj->wire`, restore opaque user-supplied values
   that must round-trip verbatim (e.g. SQL column names in
   `sessionFs.sqliteQuery` result rows). Without this, a provider
   returning `{:rows [{:user_id 1}]}` would be serialized as
   `{:userId 1}`, producing rows that no longer match the `columns`
   array."
  [method raw-result wire-result]
  (cond
    ;; Upstream PR #1299: SQL row column names are opaque identifiers.
    ;; Preserve the original :rows vector verbatim while keeping the
    ;; sibling SDK fields (rows-affected → rowsAffected, etc.) converted.
    (and (= "sessionFs.sqliteQuery" method)
         (map? raw-result)
         (contains? raw-result :rows))
    (assoc wire-result :rows (:rows raw-result))

    (and (= "sessionFs.sqliteTransaction" method)
         (map? raw-result)
         (vector? (:results raw-result)))
    (update wire-result :results
            (fn [wire-results]
              (mapv (fn [index wire-entry]
                      (let [raw-entry (get (:results raw-result) index)]
                        (if (and (map? raw-entry) (contains? raw-entry :rows))
                          (assoc wire-entry :rows (:rows raw-entry))
                          wire-entry)))
                    (range)
                    wire-results)))

    :else wire-result))

(defn- run-request-handler!
  "Execute one reverse request and answer the peer.

   Runs on a worker thread, never inside `go`: `request-handler` is arbitrary
   caller code that may perform ordinary blocking Clojure or Java work before
   it returns its result channel.

   There is deliberately no timeout around the handler's result. A wedged
   handler occupies exactly one worker; the pool bound and the overload error
   in [[handle-request!]] are what make that visible, rather than a fallback
   that hides it."
  [request-handler conn method id params]
  (try
    (let [result (if request-handler
                   (let [result-ch (request-handler method params)]
                     (when-not (satisfies? async-protocols/ReadPort result-ch)
                       (throw (ex-info "Request handler must return a core.async channel"
                                       {:method method
                                        :returned-type (some-> result-ch class str)})))
                     (<!! result-ch))
                   {:error {:code -32601 :message "Method not found"}})]
      (if-let [error (:error result)]
        (do
          (log/debug "Request error response: " error)
          (enqueue! conn {:jsonrpc "2.0" :id id :error (util/clj->wire error)}))
        (do
          (log/debug "Request success response for id=" id)
          (enqueue! conn {:jsonrpc "2.0" :id id
                          :result (preserve-outgoing-opaque-fields
                                   method
                                   (:result result)
                                   (util/clj->wire (:result result)))}))))
    (catch InterruptedException _
      ;; Expected: `disconnect` interrupts workers still inside a handler.
      (.interrupt (Thread/currentThread))
      (log/debug "Reverse request worker interrupted for id=" id))
    (catch Exception t
      (log/error "Request handler exception for method=" method " id=" id ": " (ex-message t))
      (enqueue! conn {:jsonrpc "2.0"
                      :id id
                      :error {:code -32603
                              :message (str "Internal error: " (ex-message t))}}))))

(defn- reject-request!
  "Answer a reverse request that the worker pool refused.

   Saturation and shutdown are reported as explicit JSON-RPC errors. The
   reader thread never blocks waiting for capacity."
  [^ThreadPoolExecutor executor ^AtomicLong rejected-requests conn method id]
  (if (.isShutdown executor)
    (do
      (log/debug "Rejecting request during shutdown: method=" method " id=" id)
      (enqueue! conn
                {:jsonrpc "2.0"
                 :id id
                 :error {:code -32000
                         :message "Connection closed"
                         :data (util/clj->wire {:code "connection_closed"
                                                :method method})}}))
    (let [queue (.getQueue executor)
          max-concurrency (.getMaximumPoolSize executor)
          queue-size (+ (.size queue) (.remainingCapacity queue))
          total (.incrementAndGet rejected-requests)]
      (log/warn "Reverse request handler pool saturated, rejecting request: method=" method
                " id=" id " max-concurrency=" max-concurrency
                " queue-size=" queue-size " rejected-total=" total)
      (enqueue! conn
                {:jsonrpc "2.0"
                 :id id
                 :error {:code -32000
                         :message "Reverse request handler pool saturated"
                         :data (util/clj->wire {:code "request_handler_saturated"
                                                :method method
                                                :max-concurrency max-concurrency
                                                :queue-size queue-size})}}))))

(defn- handle-request!
  "Submit an incoming request message (e.g. hooks.invoke) to the connection's
   bounded reverse-request worker pool.

   Called on the reader thread. An optional request preparer may perform
   non-blocking, order-sensitive SDK bookkeeping before submission. Handler
   execution remains on the bounded worker pool."
  [conn msg]
  (let [{:keys [state-atom]} conn
        ^ThreadPoolExecutor executor (:request-executor conn)
        {:keys [request-handler request-preparer]} (conn-state state-atom)
        id (:id msg)
        method (:method msg)
        params (:params msg)]
    (log/debug "Received request: method=" method " id=" id)
    (try
      (let [preparation
            (if request-preparer
              (request-preparer method params)
              {:params params})
            on-reject (:on-reject preparation)
            prepared-params (:params preparation)]
        (try
          (.execute executor
                    ^Runnable (fn []
                                (run-request-handler!
                                 request-handler conn
                                 method id prepared-params)))
          (catch RejectedExecutionException failure
            (when on-reject
              (on-reject))
            (throw failure))))
      (catch RejectedExecutionException _
        (reject-request! executor (:rejected-requests conn) conn method id))
      (catch Exception failure
        (log/error failure
                   "Reverse request preparation failed"
                   {:method method :id id})
        (enqueue! conn
                  {:jsonrpc "2.0"
                   :id id
                   :error {:code -32603
                           :message "Internal error preparing reverse request"}})))))

(defn- restore-opaque-path
  [raw converted wire-path idiom-path]
  (if (empty? wire-path)
    raw
    (let [wire-key (first wire-path)
          idiom-key (first idiom-path)
          remaining-wire (next wire-path)
          remaining-idiom (next idiom-path)]
      (if (= :* wire-key)
        (if (and (sequential? raw) (sequential? converted))
          (mapv (fn [raw-value converted-value]
                  (restore-opaque-path raw-value converted-value
                                       remaining-wire remaining-idiom))
                raw
                converted)
          converted)
        (if (= :map-keys wire-key)
          (if (and (map? raw) (map? converted))
            (into
             (empty raw)
             (map
              (fn [[raw-key raw-value]]
                (let [;; Shallower :map-keys paths run first and restore raw
                      ;; keys. Reuse their values so sibling restorations
                      ;; compose without normalized-key collisions.
                      converted-value
                      (if remaining-wire
                        (if (contains? converted raw-key)
                          (get converted raw-key)
                          (let [converted-key
                                (first
                                 (keys (util/wire->clj {raw-key nil})))]
                            (if (contains? converted converted-key)
                              (get converted converted-key)
                              (util/wire->clj raw-value))))
                        (util/wire->clj raw-value))]
                  [raw-key
                   (if remaining-wire
                     (restore-opaque-path
                      raw-value converted-value
                      remaining-wire remaining-idiom)
                     converted-value)])))
             raw)
            converted)
          (if (and (map? raw)
                   (map? converted)
                   (contains? raw wire-key)
                   (contains? converted idiom-key))
            (assoc converted idiom-key
                   (restore-opaque-path (get raw wire-key)
                                        (get converted idiom-key)
                                        remaining-wire remaining-idiom))
            converted))))))

(defn- preserve-event-opaque-fields
  "Given a raw wire event (pre-`wire->clj`) and a converted event, restore
   source-defined / opaque fields verbatim onto the converted shape so
   kebab-casing doesn't mangle user-supplied keys. Applies the per-event-type
   rules used by `normalize-incoming` for live notifications, so live and
    historical events share the same shape."
  [raw-event converted-event]
  (let [restored
        (reduce
         (fn [converted {:keys [wire idiom]}]
           (restore-opaque-path raw-event converted wire idiom))
         converted-event
         (get event-metadata/opaque-json-paths (:type raw-event)))]
    (if (and (= "session.custom_notification" (:type raw-event))
             (map? (:data raw-event))
             (map? (:data restored))
             (contains? (:data raw-event) :subject))
      (assoc-in restored [:data :subject] (get-in raw-event [:data :subject]))
      restored)))

(defn- normalize-incoming
  "Convert wire-format keys to Clojure keys, preserving opaque user data.

   For v3 `external_tool.requested` broadcast events, tool arguments are
   kept in their original wire format so user-defined tool handlers receive
   the keys the server sent. For v3 `session.custom_notification` events,
   the source-defined `:subject` and opaque `:payload` are also preserved
   verbatim. Assistant `:reasoning-blocks` retain provider-defined key
   spelling. For v3 `mcp_app.tool_call_complete` events (schema 1.0.52-4,
   SEP-1865), the `:arguments` and `:result` payloads are similarly preserved.
   JSON-RPC response `:error :data` payloads also retain their original keys.
   The same preservation applies to historical events returned in
   `session.getMessages` responses so live and historical event shapes agree."
  [msg]
  (let [method (:method msg)
        params (:params msg)
        hook-input (when (and (= "hooks.invoke" method) (map? (:input params))) (:input params))
        hook-keys {:toolArgs :tool-args :arguments :arguments :_meta :_meta :error :error}
        binary-parts (get-in hook-input [:toolResult :binaryResultsForLlm])
        typed-msg (if hook-input
                    (assoc-in msg [:params :input]
                              (cond-> (apply dissoc hook-input (keys hook-keys))
                                (map? (:toolResult hook-input))
                                (update :toolResult dissoc :toolTelemetry)
                                (vector? binary-parts) (update :toolResult dissoc :binaryResultsForLlm)))
                    msg)
        converted
        (cond-> (util/wire->clj typed-msg)
          (and (map? (:error msg))
               (contains? (:error msg) :data))
          (assoc-in [:error :data] (get-in msg [:error :data])))
        raw-events (get-in msg [:result :events])]
    (cond
      ;; Upstream PR #1299: SQL bind parameters are opaque keyed values
      ;; (e.g. `$user_id`). Preserve the raw map so kebab-case conversion
      ;; doesn't mangle placeholder names before the handler binds them.
      (and (= "sessionFs.sqliteQuery" method) (map? params) (contains? params :params))
      (assoc-in converted [:params :params] (:params params))

      (and (= "sessionFs.sqliteTransaction" method)
           (vector? (:statements params)))
      (update-in converted [:params :statements]
                 (fn [converted-statements]
                   (mapv (fn [index converted-statement]
                           (let [raw-statement (get (:statements params) index)]
                             (if (contains? raw-statement :params)
                               (assoc converted-statement :params (:params raw-statement))
                               converted-statement)))
                         (range)
                         converted-statements)))

      hook-input
      (cond-> (reduce-kv (fn [message k idiom]
                           (if (contains? hook-input k)
                             (assoc-in message [:params :input idiom] (get hook-input k))
                             message))
                         converted hook-keys)
        (and (map? (:toolResult hook-input)) (contains? (:toolResult hook-input) :toolTelemetry))
        (assoc-in [:params :input :tool-result :tool-telemetry] (get-in hook-input [:toolResult :toolTelemetry]))
        (vector? binary-parts)
        (assoc-in [:params :input :tool-result :binary-results-for-llm]
                  (mapv #(decode-record % :metadata :metadata) binary-parts)))

      (and (= "systemMessage.transform" method) (map? (:sections params)))
      (assoc-in converted [:params :sections] (update-vals (:sections params) util/wire->clj))

      ;; v3: preserve raw arguments / subject / payload in broadcast events
      (and (= "session.event" method)
           (map? (:event params)))
      (assoc-in converted [:params :event]
                (preserve-event-opaque-fields (:event params)
                                              (get-in converted [:params :event])))

      ;; Response carrying an event collection (e.g. session.getMessages).
      ;; Preserve opaque fields per-event so historical custom_notification
      ;; events keep their subject/payload keys, and historical
      ;; external_tool.requested events keep their arguments. Without this,
      ;; live and historical events would have divergent key shapes.
      (and (:id msg) (not method) (sequential? raw-events))
      (assoc-in converted [:result :events]
                (mapv (fn [raw conv]
                        (preserve-event-opaque-fields raw conv))
                      raw-events
                      (get-in converted [:result :events])))

      :else
      converted)))

(defn- enqueue-notification! [{:keys [prepare-notification state-atom notification-queue] :as conn} msg]
  (let [prepared (try
                   (let [prepared (prepare-notification msg)]
                     (when-not (map? prepared)
                       (throw (IllegalStateException. "Expected a prepared notification map")))
                     prepared)
                   (catch Throwable failure
                     (close-input! conn {:code -32000 :message "Notification admission failed"
                                         :data {:code "notification_admission_failed"}})
                     (when-not (instance? Exception failure) (throw failure))
                     nil))]
    (when prepared
      ;; Input closure must not overtake an accepted notification.
      (locking state-atom
        (when (and (connection-running? conn)
                   (not (.offer ^LinkedBlockingQueue notification-queue prepared)))
          (.incrementAndGet ^AtomicLong (:dropped-notifications conn))
          (log/warn "Notification queue full; closing connection" {:method (:method msg)})
          (close-input! conn {:code -32000 :message "Notification queue full"
                              :data {:code "notification_queue_full" :method (:method msg)}}))))))

(defn- dispatch-message!
  "Route incoming message to appropriate handler."
  [conn msg]
  (let [{:keys [state-atom]} conn]
    ;; The pending request determines which parts of the result are opaque.
    (if (and (:id msg) (not (:method msg)))
      (handle-response! state-atom msg)
      (let [normalized (normalize-incoming (walk/keywordize-keys msg))]
        (cond
          ;; Request (has id and method) - hand off to the bounded worker pool
          (and (:id normalized) (:method normalized))
          (handle-request! conn normalized)

          (:method normalized) (enqueue-notification! conn normalized)

          :else nil)))))

;; -----------------------------------------------------------------------------
;; Reader and Writer Loops
;; -----------------------------------------------------------------------------

(defn- start-read-loop!
  "Start background thread that reads messages from NIO channel.
   Exits cleanly when channel is closed (AsynchronousCloseException)."
  [conn]
  (let [{:keys [read-channel]} conn
        single-byte-buf (ByteBuffer/allocate 1)]
    (Thread.
     (fn []
       (log/debug "Read loop started")
       (try
         (loop []
           (when (connection-running? conn)
             (if-let [msg (read-message read-channel single-byte-buf)]
               (do
                 (when (connection-running? conn)
                   (dispatch-message! conn msg))
                 (recur))
               (do
                 (log/debug "Read loop: EOF from remote")
                 (close-input! conn {:code -32000
                                     :message "Connection closed by remote"})))))
         (catch AsynchronousCloseException _
           (log/debug "Read loop: channel closed asynchronously"))
         (catch ClosedChannelException _
           (log/debug "Read loop: channel already closed"))
         (catch IOException e
           ;; "Pipe closed" is normal during shutdown when the other end
           ;; closes. Either way, if we were still running this is an
           ;; unexpected remote close: stop the loop and resolve pending
           ;; requests so callers don't hang. (During a local disconnect,
           ;; :running? is already false and pending already drained, so the
           ;; drain below is a harmless no-op.)
           (let [pipe-closed? (= "Pipe closed" (ex-message e))]
             (when (connection-running? conn)
               (if pipe-closed?
                 (log/debug "Read loop: pipe closed by remote")
                 (log/error "Read loop IO exception: " (ex-message e)))
               (close-input! conn {:code -32000
                                   :message (if pipe-closed?
                                              "Connection closed by remote"
                                              (str "Connection error: " (ex-message e)))}))))
         (catch Exception e
           (when (connection-running? conn)
             (log/error "Read loop exception: " (ex-message e))
             (close-input! conn {:code -32000
                                 :message (str "Connection error: " (ex-message e))})))
         (finally
           (close-input! conn {:code -32000 :message "Connection input closed"})
           (log/debug "Read loop ending")))))))

(defn- start-write-loop!
  "Write and flush frames on one connection-owned thread."
  [conn]
  (let [{:keys [write-channel output-stream outgoing-ch state-atom]} conn
        writer-thread (Thread.
                       (fn []
                         (try
                           (loop []
                             (when-let [msg (<!! outgoing-ch)]
                               (when (connection-running? conn)
                                 (write-message! write-channel msg)
                                 (.flush ^OutputStream output-stream)
                                 (recur))))
                           (catch Throwable failure
                             (close-input! conn
                                           {:code -32000
                                            :message (str "Connection write failed: " (ex-message failure))
                                            :data {:code "write_failed"}})
                             (when-not (instance? Exception failure)
                               (throw failure))))))]
    (.setDaemon writer-thread true)
    (.setName writer-thread "jsonrpc-nio-writer")
    (.start writer-thread)
    ;; Store thread reference for cleanup
    (update-conn! state-atom assoc :writer-thread writer-thread)))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn initial-connection-state
  "Return initial connection state to be stored in client's atom under :connection key."
  []
  {:running? true
   :connection-id (Object.)
   :pending-requests {}
   :request-handler nil
   :request-preparer nil
   :writer-thread nil})

(defn connect
  "Create a JSON-RPC connection from input/output streams.
   Uses NIO channels for interruptible I/O.
   
   state-atom: atom containing :connection key with connection state.
   Internal [:options :prepare-notification] can stamp a normalized notification
   with its current registration before enqueue. It runs on the reader thread
   and must return a map without blocking or calling application code.
   
   Returns a Connection record."
  [^InputStream in ^OutputStream out state-atom]
  (log/debug "Creating JSON-RPC connection with NIO channels")
  (let [read-ch (Channels/newChannel in)
        write-ch (Channels/newChannel out)
        incoming-ch (chan 1024)
        outgoing-ch (chan 1024)
        options (get @state-atom :options)
        queue-size (or (:notification-queue-size options) 4096)
        notification-queue (LinkedBlockingQueue. queue-size)
        conn (map->Connection
              {:read-channel read-ch
               :write-channel write-ch
               :output-stream out  ; Keep for flushing
               :state-atom state-atom
               :connection-id (:connection-id (conn-state state-atom))
               :incoming-ch incoming-ch
               :outgoing-ch outgoing-ch
               :notification-queue notification-queue
               :prepare-notification (get options :prepare-notification identity)
               :notification-thread nil
               :read-thread nil
               :request-executor (make-request-executor
                                  (or (:request-handler-threads options)
                                      default-request-handler-threads)
                                  (or (:request-handler-queue-size options)
                                      default-request-handler-queue-size))
               :rejected-requests (AtomicLong.)
               :dropped-notifications (AtomicLong.)})]

    (start-write-loop! conn)

    ;; Start notification dispatcher thread
    (let [thread (Thread.
                  (fn []
                    (log/debug "Notification dispatcher started")
                    (try
                      (loop []
                        (when (or (connection-running? conn)
                                  (not (.isEmpty notification-queue)))
                          (if-let [msg (.poll notification-queue 100 TimeUnit/MILLISECONDS)]
                            (when (>!! incoming-ch msg)
                              (recur))
                            (recur))))
                      (catch InterruptedException _
                        (log/debug "Notification dispatcher interrupted"))
                      (catch Exception e
                        (log/error "Notification dispatcher exception: " (ex-message e)))
                      (finally
                        (close! incoming-ch)
                        (log/debug "Notification dispatcher ending")))))]
      (.setDaemon thread true)
      (.setName thread "jsonrpc-notification-dispatcher")
      (.start thread)
      (update-conn! state-atom assoc :notification-thread thread))

    ;; Start reader thread
    (let [thread (start-read-loop! conn)]
      (.setDaemon thread true)
      (.setName thread "jsonrpc-nio-reader")
      (.start thread)
      (log/debug "JSON-RPC connection established")
      (assoc conn :read-thread thread))))

(defn- shutdown-request-executor!
  "Stop the reverse-request worker pool.

   Blocked handlers are interrupted so a wedged handler cannot delay disconnect
   indefinitely. A pool that still has not terminated is reported rather than
   silently ignored -- a handler that swallows interruption is real diagnostic
   information. Idempotent.

   An interrupted wait is re-flagged on the calling thread rather than
   swallowed, but is never propagated: `disconnect` must always reach the
   channel closes and thread joins that follow it. A later teardown join may
   legitimately consume the flag."
  [^ThreadPoolExecutor executor]
  (when executor
    (.shutdownNow executor)
    (let [terminated? (try
                        (.awaitTermination executor 1000 TimeUnit/MILLISECONDS)
                        (catch InterruptedException _
                          (.interrupt (Thread/currentThread))
                          false))]
      (when-not terminated?
        (log/warn "Reverse request handler pool did not terminate within 1000ms; "
                  (.getActiveCount executor) " handler(s) still running")))))

(defn ^:no-doc release-disconnected-connection!
  "Release resources for a connection whose shared state is already stopped.

   `connection-state` must be the snapshot for this exact connection. Passing
   the snapshot keeps cleanup from touching thread references belonging to a
   later connection that reuses the same client state atom."
  [conn connection-state]
  (shutdown-request-executor! (:request-executor conn))
  (close! (:outgoing-ch conn))
  (close! (:incoming-ch conn))
  (let [failures
        (td/collect
         [(when-let [^Thread writer (:writer-thread connection-state)]
            (td/attempt {:operation :join :resource :writer-thread}
                        (.interrupt writer)
                        (.join writer 500)))

          (when-let [^Thread thread (:notification-thread connection-state)]
            (td/attempt {:operation :join :resource :notification-thread}
                        (.interrupt thread)
                        (.join thread 500)))

          (td/attempt {:operation :close :resource :read-channel}
                      (.close ^ReadableByteChannel (:read-channel conn)))
          (td/attempt {:operation :close :resource :write-channel}
                      (.close ^WritableByteChannel (:write-channel conn)))

          (when-let [^Thread thread (:read-thread conn)]
            (td/attempt {:operation :join :resource :read-thread}
                        (.interrupt thread)
                        (.join thread 1000)))])]
    (log/debug "JSON-RPC connection closed")
    failures))

(defn disconnect
  "Close the connection gracefully.
   Closes NIO channels which causes reader thread to exit via AsynchronousCloseException.

   Returns a vector of `ex-info` values describing *unexpected* teardown
   failures; expected close/interruption outcomes are not reported. Every step
   runs even when an earlier one fails, and the whole sequence is idempotent."
  [conn]
  (log/debug "Disconnecting JSON-RPC connection")
  (let [snapshot (conn-state (:state-atom conn))]
    (close-input! conn connection-closed)
    (release-disconnected-connection! conn (when (identical? (:connection-id conn)
                                                             (:connection-id snapshot))
                                             snapshot))))

(defn- pop-pending-by-chan!
  "Atomically remove and return a pending request entry by channel identity."
  [state-atom target-ch]
  (let [[old _]
        (swap-vals!
         state-atom
         (fn [state]
           (if (:connection state)
             (update-in
              state
              [:connection :pending-requests]
              (fn [pending]
                (reduce-kv (fn [m id {:keys [ch] :as entry}]
                             (if (identical? ch target-ch)
                               m
                               (assoc m id entry)))
                           {}
                           pending)))
             state)))]
    (some (fn [[_ {:keys [ch] :as entry}]]
            (when (identical? ch target-ch)
              entry))
          (get-in old [:connection :pending-requests]))))

(defn ^:no-doc cancel-request!
  "Cancel a pending request by response-channel identity.

   Returns true only when this call atomically removed the pending request.
   A response already claimed by the reader remains deliverable."
  [conn response-ch]
  (when (pop-pending-by-chan! (:state-atom conn) response-ch)
    (close! response-ch)
    true))

(defn send-request
  "Send a JSON-RPC request and return a channel for the response.
   The channel delivers a single {:result ...} or {:error ...} map, then closes.

   Optional `opts` map:
   - `:raw-json?` — for `session.getMessages` only. Return the result as
     source JSON data with string keys and exact integers, under `:result`.
     This is a local decoder option and is not sent to the runtime.
   - `:on-response-inline` — 1-arg fn `(fn [result])` invoked synchronously
     in the read thread, **before** the result is delivered to the response
     channel, on success only. Use this when you need to mutate shared
     state (e.g. register a session under a server-assigned id) before
     any later inbound message can be dispatched. See upstream PR #1479."
  ([conn method params]
   (send-request conn method params {}))
  ([conn method params {:keys [on-response-inline raw-json?]}]
   (when (and raw-json? (not= "session.getMessages" method))
     (throw (IllegalArgumentException. ":raw-json? requires session.getMessages")))
   (let [state-atom (:state-atom conn)
         id (str (java.util.UUID/randomUUID))
         ch (chan 1)
         wire-params (when params (util/clj->wire params))
         msg {:jsonrpc "2.0"
              :id id
              :method method
              :params wire-params}
         entry (cond-> {:ch ch :method method}
                 raw-json? (assoc :raw-json? true)
                 on-response-inline (assoc :on-response-inline on-response-inline))]
     (log/debug "Sending request: method=" method " id=" id)
     ;; Register the pending entry only if the connection is still running, in a
     ;; single atomic step so a concurrent disconnect either sees the entry (and
     ;; drains it) or refuses registration. Without this, a request registered
     ;; after disconnect would never be resolved and the caller would hang.
     (let [[old new] (swap-vals! state-atom
                                 (fn [s]
                                   (if (and (get-in s [:connection :running?])
                                            (identical? (:connection-id conn)
                                                        (get-in s [:connection :connection-id])))
                                     (assoc-in s [:connection :pending-requests id] entry)
                                     s)))
           registered? (not (identical? old new))]
       (if registered?
         (enqueue! conn msg)
         (do
           (put! ch {:error (if (identical? (:connection-id conn) (get-in old [:connection :connection-id]))
                              (get-in old [:connection :terminal-error] connection-closed)
                              connection-closed)})
           (close! ch)))
       ch))))

(defn send-request-with-timeout
  "Send a JSON-RPC request and return a channel for its bounded response.
   A timeout delivers a JSON-RPC-shaped error only when it atomically removes
   the pending request. If the reader already claimed the response, this waits
   for that response instead of reporting an ambiguous timeout.

   The 5-arity form accepts the same opts as `send-request`."
  ([conn method params timeout-ms]
   (send-request-with-timeout conn method params timeout-ms {}))
  ([conn method params timeout-ms opts]
   (let [response-ch (send-request conn method params opts)
         result-ch (chan 1)
         timeout-ch (async/timeout timeout-ms)]
     (async/go
       (let [[result port] (async/alts! [response-ch timeout-ch] :priority true)]
         (if (= port timeout-ch)
           (if (cancel-request! conn response-ch)
             (async/>! result-ch
                       {:error ^{::timeout true}
                        {:code -32000
                         :message "Request timeout"
                         :data {:method method :timeout-ms timeout-ms}}})
             (when-let [claimed-response (async/<! response-ch)]
               (async/>! result-ch claimed-response)))
           (when (some? result)
             (async/>! result-ch result)))
         (close! result-ch)))
     result-ch)))

(defn ^:no-doc await-bounded-response!!
  "Wait for a send-request-with-timeout response after admitting an operation
  that changes resource ownership. Interruption cannot undo the request.
  Use one take and preserve the interrupt flag after settlement. A supplied
  on-interrupt fences local callbacks on the waiting thread before it resumes
  waiting for the same bounded response."
  ([reply] (await-bounded-response!! reply nil))
  ([reply on-interrupt]
   (let [result (CompletableFuture.)]
     (async/take! reply #(.complete result %))
     (if-not on-interrupt
       (.join result)
       (try
         (when (Thread/interrupted) (throw (InterruptedException.)))
         (let [v (.get result)]
           (when (Thread/interrupted) (throw (InterruptedException.)))
           v)
         (catch InterruptedException e
           (try
             (on-interrupt e)
             (.join result)
             (finally (.interrupt (Thread/currentThread))))))))))

(defn ^:no-doc response-result!
  "Return a completed RPC result or throw its original structured error."
  [method response]
  (cond
    (nil? response)
    (throw (ex-info "Response channel closed" {:method method}))
    (:error response)
    (throw (ex-info (get-in response [:error :message] "RPC error")
                    {:error (:error response) :method method}))
    :else (:result response)))

(defn send-request!
  "Send a JSON-RPC request and block for the response.
   Returns result or throws on error.

   The 5-arity form accepts an `opts` map forwarded to `send-request`
   (see its docstring for supported keys, e.g. `:on-response-inline`).
   The 3- and 4-arity forms apply the default empty opts."
  ([conn method params]
   (send-request! conn method params 60000 {}))
  ([conn method params timeout-ms]
   (send-request! conn method params timeout-ms {}))
  ([conn method params timeout-ms opts]
   (let [response-ch (send-request conn method params opts)
         timeout-ch (when timeout-ms (async/timeout timeout-ms))
         [initial-result port]
         (async/alts!! (cond-> [response-ch]
                         timeout-ch (conj timeout-ch))
                       :priority true)
         result
         (if (and timeout-ch (= port timeout-ch))
           (if (cancel-request! conn response-ch)
             (throw
              (ex-info "Request timeout"
                       {:method method :timeout-ms timeout-ms}))
             (<!! response-ch))
           initial-result)]
     (response-result! method result))))

(defn send-notification
  "Send a JSON-RPC notification (no response expected)."
  [conn method params]
  (log/debug "Sending notification: method=" method)
  (let [wire-params (when params (util/clj->wire params))
        msg {:jsonrpc "2.0"
             :method method
             :params wire-params}]
    (enqueue! conn msg)))

(defn ^:no-doc set-request-dispatch!
  "Set reverse-request handling and non-blocking inline preparation atomically."
  [conn handler request-preparer]
  (update-conn! (:state-atom conn) assoc
                :request-handler handler
                :request-preparer request-preparer))

(defn set-request-handler!
  "Set handler for incoming requests.
   Handler is (fn [method params] -> channel with {:result ...} or {:error ...})"
  [conn handler]
  (set-request-dispatch! conn handler nil))

(defn notifications
  "Return the channel of accepted notifications. On input closure, the
   channel drains in order; [:connection :terminal-error] in the connection
   state atom identifies the cause."
  [conn]
  (:incoming-ch conn))
