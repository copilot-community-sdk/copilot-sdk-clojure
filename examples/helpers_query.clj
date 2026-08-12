(ns helpers-query
  (:require [clojure.core.async :refer [<!! close!]]
            [github.copilot-sdk :as copilot :refer [evt]]
            [github.copilot-sdk.helpers :as h])
  (:import [java.util.concurrent Callable ExecutionException FutureTask
            TimeUnit TimeoutException]))

;; See examples/README.md for usage

(def defaults
  {:prompt "What is the capital of Japan? Answer in one sentence."})

(def session-config
  {:on-permission-request copilot/approve-all
   :model "claude-haiku-4.5"})

(defn run
  [{:keys [prompt] :or {prompt (:prompt defaults)}}]
  (println "Query:" prompt)
  (println "🤖:" (h/query prompt :session session-config)))

(defn run-multi
  [{:keys [questions] :or {questions ["What is 2+2? Just the number."
                                      "What is the capital of France? Just the city."
                                      "Who wrote Hamlet? Just the name."]}}]
  (doseq [q questions]
    (println "Q:" q)
    (println "A:" (h/query q :session session-config))
    (println)))

;; Define a multimethod for handling events by type
(defmulti handle-event :type)
(defmethod handle-event :default [_] nil)
(defmethod handle-event (evt :assistant.message_delta) [{{:keys [delta-content]} :data}]
  (print delta-content)
  (flush))
(defmethod handle-event (evt :assistant.message) [_] (println))
(defmethod handle-event (evt :session.error) [{:keys [data] :as event}]
  (throw (ex-info (or (:message data) "Copilot session failed")
                  {:type :example-session-error
                   :event event})))

(defn- run-bounded!
  [label timeout-ms cancel! f]
  (when-not (pos-int? timeout-ms)
    (throw (ex-info ":timeout-ms must be a positive integer"
                    {:timeout-ms timeout-ms})))
  (let [finished (promise)
        task (FutureTask.
              ^Callable
              (fn []
                (try
                  (f)
                  (finally
                    (deliver finished true)))))]
    (doto (Thread. ^Runnable task label)
      (.setDaemon true)
      (.start))
    (try
      (.get task timeout-ms TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        ;; Cancel the helper source before interrupting its blocking consumer.
        (cancel!)
        (.cancel task true)
        (when-not (deref finished 5000 false)
          (throw (ex-info (str label " timed out; cleanup did not complete")
                          {:type :example-cleanup-timeout
                           :timeout-ms timeout-ms})))
        (throw (ex-info (str label " timed out")
                        {:type :example-timeout
                         :timeout-ms timeout-ms})))
      (catch ExecutionException e
        (throw (.getCause e)))
      (catch InterruptedException e
        (cancel!)
        (.cancel task true)
        (.interrupt (Thread/currentThread))
        (throw e)))))

(defn- consume-channel!
  [events]
  (loop []
    (when-let [event (<!! events)]
      (handle-event event)
      (recur))))

(defn run-streaming
  [{:keys [prompt timeout-ms]
    :or {prompt "Explain the concept of immutability in 2-3 sentences."
         timeout-ms 120000}}]
  (println "Query:" prompt)
  (println)
  (run-bounded!
   "helpers-query/run-streaming"
   timeout-ms
   (constantly nil)
   #(h/with-query-seq [events prompt :session {:on-permission-request copilot/approve-all
                                               :model "gpt-5.4" :streaming? true}]
      (run! handle-event events))))

(defn run-async
  [{:keys [prompt timeout-ms]
    :or {prompt "Tell me a short joke."
         timeout-ms 120000}}]
  (println "Query:" prompt)
  (println)
  (let [events (atom nil)]
    (run-bounded!
     "helpers-query/run-async"
     timeout-ms
     #(some-> @events close!)
     #(let [ch (h/query-chan prompt :session {:on-permission-request copilot/approve-all
                                              :model "gpt-5.4" :streaming? true})]
        (reset! events ch)
        (try
          (consume-channel! ch)
          (finally
            (close! ch)))))))
