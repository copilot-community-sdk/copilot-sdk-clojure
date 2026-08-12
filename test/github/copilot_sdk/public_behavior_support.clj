(ns github.copilot-sdk.public-behavior-support
  (:require [clojure.core.async :as async]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.mock-server :as mock]))

(def ^:dynamic *mock-server* nil)
(def ^:dynamic *test-client* nil)

(defn with-piped-client
  [test-fn]
  (let [server (mock/start-mock-server! (mock/create-mock-server))
        sdk-client (sdk/client {:auto-start? false})
        [in out] (mock/client-streams server)]
    (client/connect-with-streams! sdk-client in out)
    (binding [*mock-server* server
              *test-client* sdk-client]
      (try
        (test-fn)
        (finally
          (try
            (sdk/stop! sdk-client)
            (finally
              (mock/stop-mock-server! server))))))))

(defn read-within!!
  ([ch]
   (read-within!! ch 5000))
  ([ch timeout-ms]
   (let [timeout-ch (async/timeout timeout-ms)
         [value port] (async/alts!! [ch timeout-ch])]
     (when (= port timeout-ch)
       (throw (ex-info "Timed out reading channel" {:timeout-ms timeout-ms})))
     value)))

(defn read-value-then-close!!
  ([ch]
   (read-value-then-close!! ch 5000))
  ([ch timeout-ms]
   (let [value (read-within!! ch timeout-ms)
         after-value (read-within!! ch timeout-ms)]
     (when (nil? value)
       (throw (ex-info "Channel closed before delivering a value" {})))
     (when (some? after-value)
       (throw (ex-info "Channel delivered more than one value"
                       {:first value :second after-value})))
     value)))
