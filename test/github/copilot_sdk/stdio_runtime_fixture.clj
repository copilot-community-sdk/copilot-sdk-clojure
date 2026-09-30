(ns github.copilot-sdk.stdio-runtime-fixture
  "Small real subprocess that finalizes telemetry only after stdin EOF."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io BufferedInputStream InputStream OutputStream]
           [java.nio.charset StandardCharsets]))

(defn- read-line! [^InputStream input]
  (let [line (StringBuilder.)]
    (loop []
      (let [byte (.read input)]
        (cond
          (neg? byte) (when (pos? (.length line)) (str line))
          (= byte 10) (str line)
          (= byte 13) (recur)
          :else (do (.append line (char byte)) (recur)))))))

(defn read-message! [^InputStream input]
  (loop [length nil]
    (when-let [line (read-line! input)]
      (if (str/blank? line)
        (let [body (.readNBytes input (int length))]
          (when-not (= (alength body) length)
            (throw (ex-info "Truncated fixture request" {:length length})))
          (json/read-str (String. body StandardCharsets/UTF_8) :key-fn keyword))
        (let [[key value] (str/split line #": " 2)]
          (recur (if (= (str/lower-case key) "content-length")
                   (parse-long value)
                   length)))))))

(defn write-message! [^OutputStream output message]
  (let [body (.getBytes (json/write-str message) StandardCharsets/UTF_8)
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n")
                          StandardCharsets/UTF_8)]
    (.write output header)
    (.write output body)
    (.flush output)))

(defn -main [marker mode & _]
  (let [input (BufferedInputStream. System/in)
        shutdown? (atom false)]
    (loop []
      (when-let [{:keys [id method]} (read-message! input)]
        (let [result
              (case method
                "connect" {:protocolVersion (if (= mode "start-failure") 1 3)}
                "runtime.shutdown" (do
                                     (reset! shutdown? true)
                                     (when (= mode "blocked-writer")
                                       (spit (str marker ".requested") "ready\n")
                                       (loop []
                                         (when-let [line (read-line! input)]
                                           (when-not (str/blank? line)
                                             (recur)))))
                                     {})
                (throw (ex-info "Unexpected fixture request" {:method method})))]
          (write-message! System/out {:jsonrpc "2.0" :id id :result result})
          (if (and @shutdown? (= mode "blocked-writer"))
            @(promise)
            (recur)))))
    (when @shutdown?
      (spit marker "{\"type\":\"span\"}\n"))
    (when (= mode "fallback")
      @(promise))))
