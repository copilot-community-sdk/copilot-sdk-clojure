(ns krukow.copilot-sdk.java.event
  "Java Event class representing a session event.
   
   Events have a type, data map, id, and timestamp."
  (:gen-class
   :name krukow.copilot_sdk.Event
   :state state
   :init init
   :constructors {[String java.util.Map String String] []}
   :methods [[getType [] String]
             [getData [] java.util.Map]
             [get [String] Object]
             [getContent [] String]
             [getDeltaContent [] String]
             [getId [] String]
             [getTimestamp [] String]
             [isType [String] boolean]
             [isMessage [] boolean]
             [isMessageDelta [] boolean]
             [isIdle [] boolean]
             [isError [] boolean]]))

(defn -init [type data id timestamp]
  [[] {:type type :data data :id id :timestamp timestamp}])

(defn -getType [this]
  (:type (.state this)))

(defn -getData [this]
  (:data (.state this)))

(defn -get [this key]
  (when-let [data (:data (.state this))]
    (.get ^java.util.Map data key)))

(defn -getContent [this]
  (-get this "content"))

(defn -getDeltaContent [this]
  (-get this "delta-content"))

(defn -getId [this]
  (:id (.state this)))

(defn -getTimestamp [this]
  (:timestamp (.state this)))

(defn -isType [this expected-type]
  (= expected-type (:type (.state this))))

(defn -isMessage [this]
  (= "assistant.message" (:type (.state this))))

(defn -isMessageDelta [this]
  (= "assistant.message_delta" (:type (.state this))))

(defn -isIdle [this]
  (= "session.idle" (:type (.state this))))

(defn -isError [this]
  (= "session.error" (:type (.state this))))

(defn -toString [this]
  (let [s (.state this)]
    (str "Event{type='" (:type s) "', id='" (:id s) "', data=" (:data s) "}")))
