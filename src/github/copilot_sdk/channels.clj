(ns ^:no-doc github.copilot-sdk.channels
  "Read ports that do not invoke parked consumer callbacks on the publisher."
  (:require [clojure.core.async.impl.protocols :as p])
  (:import [java.util.concurrent.locks Lock]))

(defn take-dispatched! [source ^Lock handler]
  ;; Preserve alts! selection and locking, but not a request to run inline.
  (p/take! source
           (reify
             Lock
             (lock [_] (.lock handler))
             (unlock [_] (.unlock handler))
             p/Handler
             (lock-id [_] (p/lock-id handler))
             (active? [_] (p/active? handler))
             (blockable? [_] (p/blockable? handler))
             (commit [_]
               (let [callback (p/commit handler)]
                 (if (:on-caller? (meta callback)) #(callback %) callback))))))

(defn read-channel [source close!]
  (reify
    p/ReadPort
    (take! [_ handler] (take-dispatched! source handler))
    p/Channel
    (closed? [_] (p/closed? source))
    (close! [_] (close!))))
