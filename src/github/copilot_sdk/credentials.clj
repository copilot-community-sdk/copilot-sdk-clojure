(ns ^:no-doc github.copilot-sdk.credentials
  "Session-scoped GitHub credentials for protocol 4. The reverse dispatcher owns
  each registration and its interruptible work. Only the runtime caches tokens,
  decides when to refresh, and verifies the account and remaining lifetime."
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn- nonblank? [v] (and (string? v) (not (str/blank? v))))

(s/def ::host nonblank?)
(s/def ::session-id nonblank?)
(s/def ::reason #{:initial :refresh})
(s/def ::provider-args
  (s/and (s/keys :req-un [::host ::reason] :opt-un [::session-id])
         #(every? #{:host :session-id :reason} (keys %))))

(s/def ::access-token nonblank?)
(s/def ::token-type nonblank?)
(s/def ::expires-in #(and (integer? %) (< 3600 %) (<= % Long/MAX_VALUE)))
(s/def ::provider-result
  (s/or :cancelled #(= {:kind :cancelled} %)
        :token (s/and (s/keys :req-un [::access-token ::expires-in] :opt-un [::token-type])
                      #(= :token (:kind %))
                      #(every? #{:kind :access-token :expires-in :token-type} (keys %)))))

(defn prepare
  "Register a validated session option before create/resume can request a token.
  The dispatcher discards this registration on rollback, detach, or disconnect."
  [session-id options]
  (when-let [provider (get-in options [:github :token-provider])]
    {:registration-id (str (random-uuid)) :session-id session-id :provider provider}))

(defn- await-result [value]
  (if (satisfies? ap/ReadPort value)
    ;; Interrupting <!! alone would leave a live take on the provider's channel.
    (let [cancel (a/chan)]
      (try (first (a/alts!! [value cancel]))
           (finally (a/close! cancel))))
    value))

(defn handle
  "Run gitHubToken.getToken on the dispatcher's bounded worker. Never propagate
  provider exceptions, request values, or rejected credentials across the RPC."
  [{:keys [registration-id session-id provider]} request]
  (let [id (get request :session-id session-id)
        args (cond-> {:host (:host request)
                      :reason ({"initial" :initial "refresh" :refresh} (:reason request))}
               (or (contains? request :session-id) session-id) (assoc :session-id id))]
    (if-not (and provider (= registration-id (:registration-id request))
                 (or (nil? session-id) (= id session-id))
                 (s/valid? ::provider-args args))
      {:error {:code -32602 :message "Invalid GitHub token provider request"}}
      (try
        (let [result (await-result (provider args))]
          (cond
            (instance? Throwable result)
            {:error {:code -32603 :message "GitHub token provider failed"}}

            (s/valid? ::provider-result result)
            {:result (assoc result :kind (name (:kind result)))}

            :else
            {:error {:code -32603 :message "Invalid GitHub token provider result"}}))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          {:result {:kind "cancelled"}})
        (catch Throwable _
          {:error {:code -32603 :message "GitHub token provider failed"}})))))
