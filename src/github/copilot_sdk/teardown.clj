(ns ^:no-doc github.copilot-sdk.teardown
  "One teardown-outcome contract shared by the client, protocol, and native host.

   Closing a socket, closing an NIO channel, and joining a finished thread are
   idempotent by contract: they do not throw when the resource is already gone.
   A teardown step that *does* throw is therefore either an interruption of the
   calling thread or a genuine failure that left the resource live. Collapsing
   both into `(catch Exception _)` is what makes a leaked resource invisible.

   `attempt` keeps the two apart: expected outcomes are silent, unexpected ones
   become an `ex-info` carrying the operation and resource identity so the
   caller can return or log it. Nothing is propagated, so a failing step cannot
   short-circuit the steps that follow it."
  (:require [github.copilot-sdk.logging :as log])
  (:import [java.nio.channels ClosedChannelException]))

(defn expected-failure?
  "True when `t` is a normal outcome of releasing a resource that is already
   closing: interruption of the calling thread, or a channel some other
   teardown step already closed."
  [^Throwable t]
  (or (instance? InterruptedException t)
      (instance? ClosedChannelException t)))

(defn failure
  "Build the `ex-info` reported for an unexpected teardown failure.
   `step` is a `{:operation _ :resource _}` map identifying what was released,
   plus any extra context (stage, timeout) worth carrying to the caller.

   `cause` is optional: a resource can fail to be released without anything
   being thrown, e.g. a process that is still alive after a forced kill."
  ([step] (failure step nil))
  ([step ^Throwable cause]
   (ex-info (str (name (:operation step)) " failed for " (name (:resource step)))
            step
            cause)))

(defn collect
  "Drop the nils from a sequence of `attempt` results."
  [results]
  (into [] (remove nil?) results))

(defmacro attempt
  "Run a teardown step for effect. Returns nil on success or on an expected
   close/interruption outcome, otherwise the `failure` for `step`.

   An interrupt is re-flagged on the calling thread rather than swallowed, so
   later cancellation logic still observes it, but is never propagated:
   teardown must always reach the steps that follow."
  [step & body]
  `(try
     ~@body
     nil
     (catch InterruptedException _#
       (.interrupt (Thread/currentThread))
       nil)
     (catch Exception e#
       (when-not (expected-failure? e#)
         (failure ~step e#)))))

(defn cleanup-preserving!
  "Run `cleanup`, preserving `primary` when both body and cleanup fail.

   Cleanup-only failures are rethrown. Interrupted failures restore the current
   thread's interrupt status before control returns to the caller."
  [primary cleanup]
  (let [interrupted? (or (.isInterrupted (Thread/currentThread))
                         (instance? InterruptedException primary))]
    (try
      (cleanup)
      (catch Throwable cleanup-failure
        (when (instance? InterruptedException cleanup-failure)
          (.interrupt (Thread/currentThread)))
        (if primary
          (do
            (when-not (identical? primary cleanup-failure)
              (.addSuppressed ^Throwable primary cleanup-failure))
            (log/warn cleanup-failure "Cleanup failed while preserving the primary failure")
            primary)
          (throw cleanup-failure)))
      (finally
        (when interrupted?
          (.interrupt (Thread/currentThread)))))))

(defmacro with-cleanup
  "Evaluate body, then call cleanup. Preserve a body failure as primary.
   The body stays lexically visible to core.async/go."
  [cleanup & body]
  `(let [primary# (volatile! nil)]
     (try
       ~@body
       (catch Throwable failure#
         (vreset! primary# failure#)
         (throw failure#))
       (finally
         (cleanup-preserving! @primary# ~cleanup)))))
