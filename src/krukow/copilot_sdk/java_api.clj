(ns krukow.copilot-sdk.java-api
  "Java-friendly API for the Copilot SDK.
   
   This namespace provides a static API that can be easily called from Java:
   
   ```java
   import krukow.copilot_sdk.Copilot;
   import krukow.copilot_sdk.SessionOptions;
   
   // Simple query
   String answer = Copilot.query(\"What is 2+2?\");
   
   // With options
   SessionOptions opts = SessionOptions.builder()
       .model(\"gpt-5.2\")
       .build();
   String answer = Copilot.query(\"Explain monads\", opts);
   
   // Streaming
   Copilot.queryStreaming(\"Tell me a story\", opts, event -> {
       if (event.isMessageDelta()) {
           System.out.print(event.getDeltaContent());
       }
   });
   ```"
  (:require [clojure.core.async :as async]
            [clojure.walk :as walk]
            [krukow.copilot-sdk :as copilot]
            [krukow.copilot-sdk.helpers :as helpers]
            ;; Require the gen-class namespaces to ensure they're compiled
            ;; Order matters: event-handler must come before event (which uses the interface)
            krukow.copilot-sdk.java.event-handler
            krukow.copilot-sdk.java.event
            krukow.copilot-sdk.java.client-options-builder
            krukow.copilot-sdk.java.client-options
            krukow.copilot-sdk.java.session-options-builder
            krukow.copilot-sdk.java.session-options)
  (:import [krukow.copilot_sdk ClientOptions SessionOptions Event])
  (:gen-class
   :name krukow.copilot_sdk.Copilot
   :methods [^:static [query [String] String]
             ^:static [query [String krukow.copilot_sdk.SessionOptions] String]
             ^:static [query [String krukow.copilot_sdk.SessionOptions long] String]
             ^:static [queryStreaming [String krukow.copilot_sdk.SessionOptions krukow.copilot_sdk.IEventHandler] void]
             ^:static [createClient [] Object]
             ^:static [createClient [krukow.copilot_sdk.ClientOptions] Object]
             ^:static [startClient [Object] void]
             ^:static [stopClient [Object] Object]
             ^:static [forceStopClient [Object] void]
             ^:static [createSession [Object krukow.copilot_sdk.SessionOptions] Object]
             ^:static [destroySession [Object] void]
             ^:static [sendAndWait [Object String long] String]
             ^:static [sendStreaming [Object String krukow.copilot_sdk.IEventHandler] void]]))

(defn- java-map->clj-map
  "Convert a Java Map to a Clojure map with keyword keys."
  [^java.util.Map m]
  (when m
    (into {}
          (map (fn [[k v]]
                 [(if (string? k) (keyword k) k)
                  (cond
                    (instance? java.util.Map v) (java-map->clj-map v)
                    (instance? java.util.List v) (vec v)
                    :else v)]))
          m)))

(defn- session-opts->clj
  "Convert SessionOptions to Clojure map."
  [^SessionOptions opts]
  (when opts
    (java-map->clj-map (.toMap opts))))

(defn- client-opts->clj
  "Convert ClientOptions to Clojure map."
  [^ClientOptions opts]
  (when opts
    (java-map->clj-map (.toMap opts))))

(defn- clj-event->java
  "Convert a Clojure event map to Java Event."
  [event]
  (Event. (name (:type event))
          (walk/stringify-keys (:data event))
          (:id event)
          (:timestamp event)))

;; =============================================================================
;; Simple Query API (uses helpers namespace)
;; =============================================================================

(defn -query
  "Execute a simple query and return the response text."
  ([^String prompt]
   (helpers/query prompt))
  ([^String prompt ^SessionOptions session-opts]
   (helpers/query prompt :session (session-opts->clj session-opts)))
  ([^String prompt ^SessionOptions session-opts ^long timeout-ms]
   (helpers/query prompt 
                  :session (session-opts->clj session-opts)
                  :timeout-ms timeout-ms)))

(defn -queryStreaming
  "Execute a streaming query, calling handler for each event."
  [^String prompt ^SessionOptions session-opts handler]
  (let [events (helpers/query-seq prompt :session (session-opts->clj session-opts))]
    (doseq [event events]
      (.handle handler (clj-event->java event)))))

;; =============================================================================
;; Full Client/Session API
;; =============================================================================

(defn -createClient
  "Create a new CopilotClient."
  ([]
   (copilot/client {}))
  ([^ClientOptions opts]
   (copilot/client (or (client-opts->clj opts) {}))))

(defn -startClient
  "Start the client and connect to CLI."
  [client]
  (copilot/start! client))

(defn -stopClient
  "Stop the client gracefully."
  [client]
  (copilot/stop! client))

(defn -forceStopClient
  "Force stop the client."
  [client]
  (copilot/force-stop! client))

(defn -createSession
  "Create a new session."
  [client ^SessionOptions opts]
  (copilot/create-session client (or (session-opts->clj opts) {})))

(defn -destroySession
  "Destroy a session."
  [session]
  (copilot/destroy! session))

(defn -sendAndWait
  "Send a prompt and wait for the response."
  [session ^String prompt ^long timeout-ms]
  (let [response (copilot/send-and-wait! session {:prompt prompt} timeout-ms)]
    (get-in response [:data :content])))

(defn -sendStreaming
  "Send a prompt and call handler for each event."
  [session ^String prompt handler]
  (let [events-ch (copilot/subscribe-events session)]
    (copilot/send! session {:prompt prompt})
    (loop []
      (when-let [event (async/<!! events-ch)]
        (.handle handler (clj-event->java event))
        (when-not (#{:session.idle :session.error} (:type event))
          (recur))))))
