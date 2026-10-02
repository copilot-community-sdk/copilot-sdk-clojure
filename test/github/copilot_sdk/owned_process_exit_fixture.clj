(ns github.copilot-sdk.owned-process-exit-fixture
  (:require [clojure.java.io :as io]
            [github.copilot-sdk.stdio-runtime-fixture :as rpc])
  (:import [java.io BufferedInputStream]
           [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.util.concurrent TimeUnit]))

(defn -main [mode & [destination]]
  (case mode
    "holder" @(promise)
    "tcp" (do (println "listening on port" destination) (flush) @(promise))
    "stdio"
    (let [holder
          (.start
           (doto (ProcessBuilder.
                  ^java.util.List
                  [(str (io/file (System/getProperty "java.home") "bin" "java"))
                   "-cp" (System/getProperty "java.class.path")
                   "clojure.main" "-m" "github.copilot-sdk.owned-process-exit-fixture" "holder"])
             (.redirectOutput ProcessBuilder$Redirect/INHERIT)
             (.redirectError ProcessBuilder$Redirect/INHERIT)))
          input (BufferedInputStream. System/in)]
      (spit destination (str (.pid holder)))
      (loop []
        (when-let [{:keys [id method params]} (rpc/read-message! input)]
          (let [result
                (case method
                  "connect" {:protocolVersion 3}
                  "session.create" {:sessionId (:sessionId params)}
                  "session.detach" {}
                  "models.list" {:models [{:id (str (.pid (ProcessHandle/current)))
                                           :name "Fixture model"
                                           :capabilities {:supports {} :limits {}}}]}
                  "ping" (if (= (:message params) "pending")
                           (do
                             (rpc/write-message! System/out
                                                 {:jsonrpc "2.0" :method "fixture.pingReceived" :params {}})
                             ::pending)
                           {:message (:message params) :timestamp "2026-10-02T00:00:00Z"})
                  "session.send"
                  (do
                    (doseq [[type data] [["assistant.message" {:content "Complete before exit"}]
                                         ["session.idle" {}]]]
                      (rpc/write-message!
                       System/out
                       {:jsonrpc "2.0" :method "session.event"
                        :params {:sessionId (:sessionId params)
                                 :event {:type type :data data}}}))
                    {:messageId "completed-message"})
                  "runtime.shutdown"
                  (do
                    (.destroyForcibly holder)
                    (when-not (.waitFor holder 5 TimeUnit/SECONDS)
                      (throw (ex-info "Fixture descendant did not terminate" {})))
                    {})
                  (throw (ex-info "Unexpected process-exit fixture request" {:method method})))]
            (when-not (= result ::pending)
              (rpc/write-message! System/out {:jsonrpc "2.0" :id id :result result}))
            (recur)))))))
