(ns copilot.sdk.helpers
  "Blocking queries and lexical scopes for native Copilot conversations."
  (:require [clojure.core.async :as a]
            [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [copilot.sdk :as sdk]
            [copilot.sdk.specs :as specs]
            [github.copilot-sdk.session-options :as options]
            [github.copilot-sdk.teardown :as teardown]))

(defn query!!
  "Send one message on a borrowed session. Block for its final assistant
  text, or nil if there was no text. Errors propagate without a retry."
  [session message]
  (let [text (get-in (sdk/send-and-wait!! session message) [:data :content])]
    (when-not (or (nil? text) (string? text))
      (throw (ex-info "Expected assistant text" {:type :invalid-assistant-content})))
    text))

(defn- binding-pair [bindings]
  (when-not (and (vector? bindings) (= 2 (count bindings)) (simple-symbol? (first bindings)))
    (throw (IllegalArgumentException. "Expected a binding vector [name expression]")))
  bindings)

(defmacro with-conversation
  "Open one client and one session from the explicit :client and :session
  option maps in config. Bind the session for the body, then detach it and
  shut down the client. Do not return work that still needs these resources."
  {:arglists '([[session config] & body])}
  [bindings & body]
  (let [[session config] (binding-pair bindings)]
    `(let [config# (options/conversation-config ~config)]
       (sdk/with-client [client# (:client config#)]
         (sdk/with-session [~session client# (:session config#)] ~@body)))))

(defn query-once!!
  "Open a conversation, send one message, and return its text or nil.
  Supply the same explicit config as with-conversation."
  [config message]
  ;; A typo must not start a native host or create persisted session state.
  (when-not (s/valid? ::specs/turn-message message)
    (throw (ex-info "Expected a prompt string or a message map with :prompt" {:type :invalid-message})))
  (when (and (map? message) (contains? message :timeout-ms)
             (not (s/valid? ::specs/turn-timeout-ms (:timeout-ms message))))
    (throw (ex-info "Invalid turn deadline" {:type :invalid-timeout})))
  (options/encode-message (if (map? message) (dissoc message :timeout-ms) message))
  (with-conversation [session config] (query!! session message)))

(defmacro with-subscription
  "Bind a subscription to events from the whole session. Close only this
  subscription on exit. A body failure remains primary if close fails."
  {:arglists '([[events session] & body])}
  [bindings & body]
  (let [[events session] (binding-pair bindings)]
    `(let [~events (sdk/subscribe ~session)]
       (teardown/with-cleanup #(a/close! ~events) ~@body))))

(def ^:private max-json-depth 128)

;; Consume each complete string as one token so its brackets never count.
;; A quote matched alone starts an invalid string. data.json itself accepts
;; raw controls and signed or non-ASCII digits in Unicode escapes.
(def ^:private json-delimiters
  #"(?x) \" (?: [^\"\\\x00-\x1f]++ | \\ (?: [\"\\/bfnrt] | u[0-9a-fA-F]{4} ) )*+ \" | [\"{}\[\]]")

(defn- invalid-json []
  (ex-info "Expected one complete JSON value" {:type :invalid-json}))

(defn- check-json-limits! [^String text]
  (let [tokens (re-matcher json-delimiters text)]
    (loop [depth 0]
      (if (.find tokens)
        (case (.charAt text (.start tokens))
          \" (if (= (.end tokens) (inc (.start tokens)))
               (throw (invalid-json))
               (recur depth))
          (\{ \[) (if (= depth max-json-depth)
                    (throw (ex-info "JSON nesting exceeds the decoder limit"
                                    {:type :invalid-json :max-depth max-json-depth}))
                    (recur (inc depth)))
          (\} \]) (if (zero? depth) (throw (invalid-json)) (recur (dec depth))))
        (when-not (zero? depth) (throw (invalid-json)))))))

(defn- only-whitespace [value ^java.io.Reader remaining]
  (loop []
    (case (.read remaining)
      -1 value
      (9 10 13 32) (recur)
      (throw (invalid-json)))))

(defn decode-json
  "Decode one complete JSON value. Preserve object keys as strings, integers
  without rounding, and decimals as BigDecimals. JSON false and null return
  false and nil. Arrays and objects may nest up to 128 levels in total.
  Invalid input throws without including the response text."
  [text]
  (when-not (string? text) (throw (invalid-json)))
  (check-json-limits! text)
  (try
    (json/read-str text :bigdec true :extra-data-fn only-whitespace)
    ;; Parser exceptions can contain the input, including in nested causes.
    (catch Exception _ (throw (invalid-json)))))

(defn query-json!!
  "Send one message with a :prompt and explicit :response-schema map on a
  borrowed session. Decode its final text on the calling thread. The schema
  requests structured output; validate the decoded value in the application."
  [session message]
  (when-not (s/valid? ::specs/structured-message message)
    (throw (ex-info "Expected a :prompt string and a :response-schema map"
                    {:type :invalid-structured-message})))
  (decode-json (query!! session message)))
