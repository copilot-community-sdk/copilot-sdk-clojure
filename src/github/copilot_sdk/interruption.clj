(ns ^:no-doc github.copilot-sdk.interruption
  "Owner-scoped interruption of application tasks, never on a protocol or go thread."
  (:require [github.copilot-sdk.logging :as log])
  (:import [java.util.concurrent ArrayBlockingQueue Future FutureTask ThreadFactory ThreadPoolExecutor TimeUnit]))

(defn owner [capacity]
  (doto (ThreadPoolExecutor.
         capacity capacity 30 TimeUnit/SECONDS (ArrayBlockingQueue. capacity)
         (reify ThreadFactory
           (newThread [_ r] (doto (Thread. nil r "copilot-sdk-interrupt" 0 false) (.setDaemon true)))))
    (.allowCoreThreadTimeOut true)))

(defn task [^ThreadPoolExecutor owner ^Future work]
  (let [submitted? (volatile! false)
        ;; Stream.close can run inside Thread.interrupt and throw. Report it
        ;; here even when the cancellation request has no waiting caller.
        interrupt (FutureTask. ^Runnable
                   #(try (.cancel work true)
                         (catch Throwable t
                           (if (and (instance? Error t) (not (instance? AssertionError t))) (throw t)
                               (log/warn "Application I/O close failed during interruption"
                                         {:exception (.getName (class t))}))))
                               nil)]
    (fn [operation]
      ;; Only running application workers need interruption. Queued callbacks
      ;; are fenced by their owner's cancel signal. Remove a queued interrupt
      ;; on actual worker exit before its slot is available to a successor.
      (locking submitted?
        (case operation
          :cancel (cond
                    @submitted? interrupt
                    (not (.isDone work)) (do (.execute owner interrupt) (vreset! submitted? true) interrupt))
          :finish (when (and @submitted? (.remove owner interrupt)) (.cancel interrupt false)))))))
