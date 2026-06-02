(ns github.copilot-sdk.protocol
  "JSON-RPC 2.0 protocol implementation using java.nio channels.
   
   Architecture:
   - NIO channels for interruptible I/O (clean shutdown)
   - core.async channels for message flow
   - Single reader thread puts to incoming-ch
   - Writer go-loop takes from outgoing-ch
   - State is managed externally (passed in as atom)
   
   This design allows clean shutdown: closing NIO channels causes
   reader to throw AsynchronousCloseException and exit gracefully."
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async :refer [go go-loop <! >! >!! <!! chan close! put!]]
            [clojure.string :as str]
            [github.copilot-sdk.logging :as log]
            [github.copilot-sdk.util :as util])
  (:import [java.io InputStream OutputStream IOException]
           [java.nio ByteBuffer]
           [java.nio.channels Channels ReadableByteChannel WritableByteChannel ClosedChannelException]
           [java.nio.channels AsynchronousCloseException]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private content-length-header "Content-Length: ")

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
        (json/read-str content :key-fn keyword)))))

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
            incoming-ch                   ; channel for incoming messages (responses + notifications)
            outgoing-ch                   ; channel for outgoing messages
            notification-queue            ; queue for notifications to avoid blocking reader
            notification-thread           ; Thread
            read-thread])                 ; Thread

;; State path helpers
(defn- conn-state [state-atom] (get @state-atom :connection))
(defn- update-conn! [state-atom f & args] (apply swap! state-atom update :connection f args))

(defn- drain-pending!
  "Atomically clear all pending requests and deliver `error` to each response
   channel. Safe to call concurrently from the read loop and `disconnect`: the
   single `swap-vals!` guarantees each pending entry is captured exactly once, so
   no response channel is delivered to twice. `error` is the `:error` payload
   (e.g. `{:code -32000 :message \"...\"}`)."
  [state-atom error]
  (let [[old _] (swap-vals! state-atom assoc-in [:connection :pending-requests] {})]
    (doseq [[_ {:keys [ch]}] (get-in old [:connection :pending-requests])]
      (put! ch {:error error})
      (close! ch))))

(defn- pop-pending!
  "Atomically remove and return the pending entry for `id`, or nil if it is no
   longer registered. Using `swap-vals!` guarantees the entry is claimed by
   exactly one caller, so `handle-response!` and `drain-pending!` can never both
   deliver to the same response channel."
  [state-atom id]
  (let [[old _] (swap-vals! state-atom update-in [:connection :pending-requests] dissoc id)]
    (get-in old [:connection :pending-requests id])))

;; -----------------------------------------------------------------------------
;; Message Handling
;; -----------------------------------------------------------------------------

(defn- handle-response!
  "Handle an incoming response message. Delivers to pending channel.

   If the original `send-request` call supplied an `:on-response-inline`
   callback, invoke it synchronously **before** delivering the result to
   the response channel. This callback runs in the reader thread, so any
   work it performs (e.g. session registration after a server-assigned
   sessionId) is guaranteed to complete before the next inbound message
   is dispatched. Used by `client/create-session` for the cloud-no-id
   flow (upstream PR #1479)."
  [state-atom msg]
  (let [id (:id msg)]
    (log/debug "Received response for id=" id)
    ;; Atomically claim the pending entry so a concurrent drain-pending!
    ;; (disconnect / EOF) can never also deliver to this response channel.
    (when-let [{:keys [ch on-response-inline]} (pop-pending! state-atom id)]
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
          (close! ch))))))

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

    ;; Upstream PR #1366: preMcpToolCall hook output `:meta-to-use` is
    ;; opaque MCP metadata. The inner map's keys are source-defined and
    ;; must NOT be camelCased. `contains?` (not truthiness) is used so
    ;; that an explicit `nil` value survives as JSON `null` (the
    ;; tri-state contract: absent = preserve, null = remove, object =
    ;; replace). Only preMcpToolCall hooks use this key, so unconditional
    ;; preservation for any `hooks.invoke` response is safe.
    (and (= "hooks.invoke" method)
         (map? raw-result)
         (contains? raw-result :meta-to-use))
    (assoc wire-result :metaToUse (:meta-to-use raw-result))

    :else wire-result))

(defn- handle-request!
  "Handle an incoming request message (e.g., tool.call). Sends response via outgoing-ch."
  [state-atom outgoing-ch msg]
  (go
    (let [request-handler (:request-handler (conn-state state-atom))
          id (:id msg)
          method (:method msg)
          params (:params msg)]
      (log/debug "Received request: method=" method " id=" id)
      (try
        (let [result (if request-handler
                       (<! (request-handler method params))
                       {:error {:code -32601 :message "Method not found"}})]
          (if (:error result)
            (do
              (log/debug "Request error response: " (:error result))
              (>! outgoing-ch {:jsonrpc "2.0" :id id :error (util/clj->wire (:error result))}))
            (do
              (log/debug "Request success response for id=" id)
              (>! outgoing-ch {:jsonrpc "2.0" :id id
                               :result (preserve-outgoing-opaque-fields
                                        method
                                        (:result result)
                                        (util/clj->wire (:result result)))}))))
        (catch Exception e
          (log/error "Request handler exception: " (ex-message e))
          (>! outgoing-ch {:jsonrpc "2.0"
                           :id id
                           :error {:code -32603
                                   :message (str "Internal error: " (ex-message e))}}))))))

(defn- restore-extension-context-payloads
  "Restore the opaque `:payload` of each `extension_context` attachment from
   the raw (pre-`wire->clj`) attachments onto the converted attachments, so
   source-defined payload keys aren't kebab-cased. Raw and converted
   attachment vectors are positionally aligned (conversion preserves order
   and count)."
  [raw-atts conv-atts]
  (mapv (fn [raw conv]
          (if (and (= "extension_context" (:type raw))
                   (contains? raw :payload))
            (assoc conv :payload (:payload raw))
            conv))
        raw-atts
        conv-atts))

(defn- preserve-event-opaque-fields
  "Given a raw wire event (pre-`wire->clj`) and a converted event, restore
   source-defined / opaque fields verbatim onto the converted shape so
   kebab-casing doesn't mangle user-supplied keys. Applies the per-event-type
   rules used by `normalize-incoming` for live notifications, so live and
   historical events share the same shape."
  [raw-event converted-event]
  (case (:type raw-event)
    "external_tool.requested"
    (cond-> converted-event
      (contains? (:data raw-event) :arguments)
      (assoc-in [:data :arguments] (get-in raw-event [:data :arguments])))

    "session.custom_notification"
    (cond-> converted-event
      (contains? (:data raw-event) :subject)
      (assoc-in [:data :subject] (get-in raw-event [:data :subject]))
      (contains? (:data raw-event) :payload)
      (assoc-in [:data :payload] (get-in raw-event [:data :payload])))

    ;; Upstream schema 1.0.52-4 (SEP-1865): MCP App invoked a tool on an MCP
    ;; server. Both the supplied `:arguments` map and the returned `:result`
    ;; (standard MCP CallToolResult) are source-defined opaque payloads —
    ;; preserve their raw keys so consumers can forward them verbatim.
    "mcp_app.tool_call_complete"
    (cond-> converted-event
      (contains? (:data raw-event) :arguments)
      (assoc-in [:data :arguments] (get-in raw-event [:data :arguments]))
      (contains? (:data raw-event) :result)
      (assoc-in [:data :result] (get-in raw-event [:data :result])))

    ;; Upstream schema 1.0.57: `extension_context` attachments carry an opaque
    ;; caller-supplied `:payload`. These appear on `user.message` attachments
    ;; (reachable via `session.getMessages`) and on the ephemeral
    ;; `session.extensions.attachments_pushed` event. Restore each payload so
    ;; its source-defined keys aren't kebab-cased.
    ("user.message" "session.extensions.attachments_pushed")
    (cond-> converted-event
      (seq (get-in raw-event [:data :attachments]))
      (assoc-in [:data :attachments]
                (restore-extension-context-payloads
                 (get-in raw-event [:data :attachments])
                 (get-in converted-event [:data :attachments]))))

    converted-event))

(defn- normalize-incoming
  "Convert wire-format keys to Clojure keys, preserving opaque user data.

   For v3 `external_tool.requested` broadcast events, tool arguments are
   kept in their original wire format so user-defined tool handlers receive
   the keys the server sent. For v3 `session.custom_notification` events,
   the source-defined `:subject` and opaque `:payload` are also preserved
   verbatim. For v3 `mcp_app.tool_call_complete` events (schema 1.0.52-4,
   SEP-1865), the `:arguments` and `:result` payloads are similarly
   preserved. The same preservation applies to historical events returned
   in `session.getMessages` responses so live and historical event shapes
   agree."
  [msg]
  (let [method (:method msg)
        params (:params msg)
        converted (util/wire->clj msg)
        raw-events (get-in msg [:result :events])]
    (cond
      ;; Upstream PR #1299: SQL bind parameters are opaque keyed values
      ;; (e.g. `$user_id`). Preserve the raw map so kebab-case conversion
      ;; doesn't mangle placeholder names before the handler binds them.
      (and (= "sessionFs.sqliteQuery" method) (map? params) (contains? params :params))
      (assoc-in converted [:params :params] (:params params))

      ;; Upstream PR #1366: preMcpToolCall hook input has two opaque
      ;; fields that must NOT be recursively kebab-cased:
      ;; - `:arguments`: MCP tool call arguments (source-defined keys)
      ;; - `:_meta`: MCP metadata. csk would also collapse the key
      ;;   `:_meta` to `:meta`, so we re-key explicitly.
      (and (= "hooks.invoke" method)
           (map? params)
           (= "preMcpToolCall" (:hookType params))
           (map? (:input params)))
      (let [raw-input (:input params)]
        (cond-> converted
          (contains? raw-input :arguments)
          (assoc-in [:params :input :arguments] (:arguments raw-input))

          (contains? raw-input :_meta)
          (-> (update-in [:params :input] dissoc :meta)
              (assoc-in [:params :input :_meta] (:_meta raw-input)))))

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

(defn- dispatch-message!
  "Route incoming message to appropriate handler."
  [conn msg]
  (let [{:keys [state-atom incoming-ch outgoing-ch]} conn
        normalized (normalize-incoming msg)]
    (cond
      ;; Response (has id, no method) - deliver to pending channel
      (and (:id normalized) (not (:method normalized)))
      (handle-response! state-atom normalized)

      ;; Request (has id and method) - handle and respond
      (and (:id normalized) (:method normalized))
      (handle-request! state-atom outgoing-ch normalized)

      ;; Notification (has method, no id) - put to incoming-ch for routing
      (:method normalized)
      (do
        (log/debug "Received notification: method=" (:method normalized))
        (when-not (.offer ^LinkedBlockingQueue (:notification-queue conn) normalized)
          (log/debug "Dropping notification due to full queue")))

      :else nil)))

;; -----------------------------------------------------------------------------
;; Reader and Writer Loops
;; -----------------------------------------------------------------------------

(defn- start-read-loop!
  "Start background thread that reads messages from NIO channel.
   Exits cleanly when channel is closed (AsynchronousCloseException)."
  [conn]
  (let [{:keys [read-channel state-atom]} conn
        single-byte-buf (ByteBuffer/allocate 1)]
    (Thread.
     (fn []
       (log/debug "Read loop started")
       (try
         (loop []
           (when (:running? (conn-state state-atom))
             (if-let [msg (read-message read-channel single-byte-buf)]
               (do
                 (dispatch-message! conn msg)
                 (recur))
               (do
                 (log/debug "Read loop: EOF from remote")
                 (update-conn! state-atom assoc :running? false)
                 (drain-pending! state-atom {:code -32000
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
             (when (:running? (conn-state state-atom))
               (if pipe-closed?
                 (log/debug "Read loop: pipe closed by remote")
                 (log/error "Read loop IO exception: " (ex-message e)))
               (update-conn! state-atom assoc :running? false)
               (drain-pending! state-atom {:code -32000
                                           :message (if pipe-closed?
                                                      "Connection closed by remote"
                                                      (str "Connection error: " (ex-message e)))}))))
         (catch Exception e
           (when (:running? (conn-state state-atom))
             (log/error "Read loop exception: " (ex-message e))))
         (finally
           (log/debug "Read loop ending")
           (close! (:incoming-ch conn))))))))

(defn- start-write-loop!
  "Start go-loop that writes messages from outgoing-ch to NIO channel.
   Uses a dedicated thread for actual writes to avoid locking issues in go blocks."
  [conn]
  (let [{:keys [write-channel output-stream outgoing-ch state-atom]} conn
        write-queue (java.util.concurrent.LinkedBlockingQueue.)
        writer-thread (Thread.
                       (fn []
                         (try
                           (while (:running? (conn-state state-atom))
                             (when-let [msg (.poll write-queue 100 java.util.concurrent.TimeUnit/MILLISECONDS)]
                               (when (and (:running? (conn-state state-atom)) (.isOpen write-channel))
                                 (try
                                   (when (and (:id msg)
                                              (not (:method msg))
                                              (map? (:result msg))
                                              (or (contains? (:result msg) :kind)
                                                  (and (map? (:result (:result msg)))
                                                       (contains? (:result (:result msg)) :kind))))
                                     (log/debug "Sending permission response: " (json/write-str msg)))
                                   (log/debug "Writing message: " (if (:id msg) (str "id=" (:id msg)) "notification"))
                                   (write-message! write-channel msg)
                                   (.flush output-stream)
                                   (log/debug "Message written and flushed")
                                   (catch java.nio.channels.ClosedChannelException _
                                     (log/debug "Write channel closed"))
                                   (catch java.io.IOException _
                                     (log/debug "Write stream closed"))
                                   (catch Exception e
                                     (when (:running? (conn-state state-atom))
                                       (log/error "Write error: " (ex-message e))))))))
                           (catch InterruptedException _
                             (log/debug "Writer thread interrupted")))))]
    (.setDaemon writer-thread true)
    (.setName writer-thread "jsonrpc-nio-writer")
    (.start writer-thread)
    ;; Store thread reference for cleanup
    (update-conn! state-atom assoc :writer-thread writer-thread)
    ;; Go-loop to transfer from core.async channel to blocking queue
    (go-loop []
      (when-let [msg (<! outgoing-ch)]
        (when (:running? (conn-state state-atom))
          (.put write-queue msg))
        (recur)))))

;; -----------------------------------------------------------------------------
;; Public API
;; -----------------------------------------------------------------------------

(defn initial-connection-state
  "Return initial connection state to be stored in client's atom under :connection key."
  []
  {:running? true
   :pending-requests {}
   :request-handler nil
   :writer-thread nil})

(defn connect
  "Create a JSON-RPC connection from input/output streams.
   Uses NIO channels for interruptible I/O.
   
   state-atom: atom containing :connection key with connection state
   
   Returns a Connection record."
  [^InputStream in ^OutputStream out state-atom]
  (log/debug "Creating JSON-RPC connection with NIO channels")
  (let [read-ch (Channels/newChannel in)
        write-ch (Channels/newChannel out)
        incoming-ch (chan 1024)
        outgoing-ch (chan 1024)
        queue-size (or (get-in @state-atom [:options :notification-queue-size]) 4096)
        notification-queue (LinkedBlockingQueue. queue-size)
        conn (map->Connection
              {:read-channel read-ch
               :write-channel write-ch
               :output-stream out  ; Keep for flushing
               :state-atom state-atom
               :incoming-ch incoming-ch
               :outgoing-ch outgoing-ch
               :notification-queue notification-queue
               :notification-thread nil
               :read-thread nil})]

    ;; Start writer loop
    (start-write-loop! conn)

    ;; Start notification dispatcher thread
    (let [thread (Thread.
                  (fn []
                    (log/debug "Notification dispatcher started")
                    (try
                      (loop []
                        (when (:running? (conn-state state-atom))
                          (when-let [msg (.poll notification-queue 100 TimeUnit/MILLISECONDS)]
                            (>!! incoming-ch msg))
                          (recur)))
                      (catch InterruptedException _
                        (log/debug "Notification dispatcher interrupted"))
                      (catch Exception e
                        (log/error "Notification dispatcher exception: " (ex-message e)))
                      (finally
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

(defn disconnect
  "Close the connection gracefully.
   Closes NIO channels which causes reader thread to exit via AsynchronousCloseException."
  [conn]
  (log/debug "Disconnecting JSON-RPC connection")
  (let [state-atom (:state-atom conn)]
    ;; Signal loops to stop
    (update-conn! state-atom assoc :running? false)

    ;; Resolve any in-flight requests so callers blocked on their response
    ;; channels unblock immediately instead of hanging forever. Done after
    ;; clearing :running? so a concurrent send-request fails fast rather than
    ;; registering a new entry we'd miss.
    (drain-pending! state-atom {:code -32000 :message "Connection closed"})

    ;; Close outgoing channel first to stop write go-loop
    (close! (:outgoing-ch conn))

    ;; Interrupt writer thread if it exists
    (when-let [^Thread writer (:writer-thread (conn-state state-atom))]
      (.interrupt writer)
      (try (.join writer 500) (catch Exception _)))

    ;; Interrupt notification dispatcher thread
    (when-let [^Thread thread (:notification-thread (conn-state state-atom))]
      (.interrupt thread)
      (try (.join thread 500) (catch Exception _)))

    ;; Close NIO channels - this unblocks any blocked reads
    (try (.close ^ReadableByteChannel (:read-channel conn)) (catch Exception _))
    (try (.close ^WritableByteChannel (:write-channel conn)) (catch Exception _))

    ;; Wait for read thread to exit
    (when-let [^Thread thread (:read-thread conn)]
      (try
        (.interrupt thread)
        (.join thread 1000)
        (catch Exception _)))

    (log/debug "JSON-RPC connection closed")))

(defn- remove-pending-by-chan!
  "Remove a pending request entry by channel identity."
  [state-atom target-ch]
  (update-conn! state-atom update :pending-requests
                (fn [pending]
                  (reduce-kv (fn [m id {:keys [ch] :as entry}]
                               (if (identical? ch target-ch)
                                 m
                                 (assoc m id entry)))
                             {}
                             pending))))

(defn send-request
  "Send a JSON-RPC request and return a channel for the response.
   The channel delivers a single {:result ...} or {:error ...} map, then closes.

   Optional `opts` map:
   - `:on-response-inline` — 1-arg fn `(fn [result])` invoked synchronously
     in the read thread, **before** the result is delivered to the response
     channel, on success only. Use this when you need to mutate shared
     state (e.g. register a session under a server-assigned id) before
     any later inbound message can be dispatched. See upstream PR #1479."
  ([conn method params]
   (send-request conn method params {}))
  ([conn method params {:keys [on-response-inline] :as _opts}]
   (let [state-atom (:state-atom conn)
         id (str (java.util.UUID/randomUUID))
         ch (chan 1)
         wire-params (when params (util/clj->wire params))
         msg {:jsonrpc "2.0"
              :id id
              :method method
              :params wire-params}
         entry (cond-> {:ch ch :method method}
                 on-response-inline (assoc :on-response-inline on-response-inline))]
     (log/debug "Sending request: method=" method " id=" id)
     ;; Register the pending entry only if the connection is still running, in a
     ;; single atomic step so a concurrent disconnect either sees the entry (and
     ;; drains it) or refuses registration. Without this, a request registered
     ;; after disconnect would never be resolved and the caller would hang.
     (let [[old new] (swap-vals! state-atom
                                 (fn [s]
                                   (if (get-in s [:connection :running?])
                                     (assoc-in s [:connection :pending-requests id] entry)
                                     s)))
           registered? (not (identical? old new))]
       (if registered?
         (put! (:outgoing-ch conn) msg
               (fn [enqueued?]
                 ;; If the outgoing channel was already closed the message was
                 ;; dropped, so resolve the pending entry with an error rather
                 ;; than leaving the caller blocked.
                 (when-not enqueued?
                   (remove-pending-by-chan! state-atom ch)
                   (put! ch {:error {:code -32000 :message "Connection closed"}})
                   (close! ch))))
         (do
           (put! ch {:error {:code -32000 :message "Connection closed"}})
           (close! ch)))
       ch))))

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
   (let [state-atom (:state-atom conn)
         response-ch (send-request conn method params opts)
         timeout-ch (async/timeout timeout-ms)
         [result port] (async/alts!! [response-ch timeout-ch])]
     (cond
       (= port timeout-ch)
       (do
         (remove-pending-by-chan! state-atom response-ch)
         (close! response-ch)
         (throw (ex-info "Request timeout" {:method method :timeout-ms timeout-ms})))

       (nil? result)
       (throw (ex-info "Response channel closed" {:method method}))

       (:error result)
       (throw (ex-info (get-in result [:error :message] "RPC error")
                       {:error (:error result) :method method}))

       :else
       (:result result)))))

(defn send-notification
  "Send a JSON-RPC notification (no response expected)."
  [conn method params]
  (log/debug "Sending notification: method=" method)
  (let [wire-params (when params (util/clj->wire params))
        msg {:jsonrpc "2.0"
             :method method
             :params wire-params}]
    (put! (:outgoing-ch conn) msg)))

(defn set-request-handler!
  "Set handler for incoming requests. 
   Handler is (fn [method params] -> channel with {:result ...} or {:error ...})"
  [conn handler]
  (update-conn! (:state-atom conn) assoc :request-handler handler))

(defn notifications
  "Returns the channel that receives incoming notifications."
  [conn]
  (:incoming-ch conn))
