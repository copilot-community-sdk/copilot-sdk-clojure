(ns github.copilot-sdk.framed-peer
  "A framed JSON-RPC peer for tests of incoming requests."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [github.copilot-sdk.protocol :as protocol])
  (:import [java.io EOFException InputStream OutputStream PipedInputStream PipedOutputStream]
           [java.nio.charset StandardCharsets]))

(defn write-framed! [^OutputStream out message]
  (let [body (.getBytes ^String (json/write-str message) StandardCharsets/UTF_8)]
    (.write out (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") StandardCharsets/US_ASCII))
    (.write out body)
    (.flush out)))

(defn- header-line [^InputStream in]
  (let [line (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (case b
          -1 (throw (EOFException. "EOF in frame header"))
          10 (str line)
          13 (recur)
          (do (.append line (char b)) (recur)))))))

(defn read-framed [^InputStream in]
  (let [headers (into {} (map #(str/split % #": " 2))
                      (take-while seq (repeatedly #(header-line in))))
        length (parse-long (get headers "Content-Length"))
        body (.readNBytes in (int length))]
    (when-not (= length (alength body))
      (throw (EOFException. "EOF in frame body")))
    (json/read-str (String. body StandardCharsets/UTF_8) :key-fn keyword)))

(defn open-connection [options]
  (let [->client (PipedOutputStream.)
        client-in (PipedInputStream. ->client 262144)
        client-out (PipedOutputStream.)
        <-client (PipedInputStream. client-out 262144)
        state-atom (atom {:connection (protocol/initial-connection-state) :options options})]
    {:conn (protocol/connect client-in client-out state-atom)
     :state-atom state-atom :->client ->client :<-client <-client}))

(defn with-peer
  "Call f with a connection and a bounded, single-request-at-a-time raw wire peer."
  [options f]
  (let [{:keys [conn ->client <-client]} (open-connection options)
        request-lock (Object.)
        request! (fn [method params]
                   (locking request-lock
                     (let [id (str (random-uuid))
                           task (future
                                  (write-framed! ->client {:jsonrpc "2.0" :id id :method method :params params})
                                  (read-framed <-client))]
                       (try
                         (let [reply (deref task 5000 ::timeout)]
                           (when-not (= id (:id reply))
                             (throw (ex-info "Missing matching RPC reply" {:method method :reply reply})))
                           reply)
                         (finally (future-cancel task))))))]
    (try
      (f conn request!)
      (finally
        (protocol/disconnect conn)
        (.close ^OutputStream ->client)
        (.close ^InputStream <-client)))))
