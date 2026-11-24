(ns ^:no-doc github.copilot-sdk.logging
  "Logging facade for the Copilot SDK using clojure.tools.logging.
   
   This wraps clojure.tools.logging to provide SDK-specific defaults.
   Configure logging via your preferred SLF4J backend (logback, log4j2, etc.)."
  (:require [clojure.tools.logging :as log]))

;; Re-export tools.logging macros for SDK use
;; Users can configure logging backend (SLF4J) as they prefer

(defn- log-form
  "Build the expansion for a facade logging macro.

   A leading Throwable is handed to the backend as a throwable so it keeps the
   backend's stack-trace rendering; the remaining arguments form the message.
   Otherwise every argument is concatenated into the message as before.

   Every argument is evaluated exactly once, and only after the level is known
   to be enabled: the whole form sits inside `enabled?`, so a disabled level
   costs nothing no matter how expensive the arguments are.

   Only tools.logging's portable `(log level throwable message)` arity is used,
   so this works on any SLF4J backend. Both `enabled?` and `log` capture the
   calling namespace, so events stay attributed to the caller rather than to
   this facade."
  [level args]
  `(when (log/enabled? ~level)
     (let [args# [~@args]
           throwable?# (instance? Throwable (first args#))]
       (log/log ~level
                (when throwable?# (first args#))
                (apply str (if throwable?# (rest args#) args#))))))

(defmacro debug
  "Log a debug message. A leading Throwable is attached to the log event."
  [& args]
  (log-form :debug args))

(defmacro info
  "Log an info message. A leading Throwable is attached to the log event."
  [& args]
  (log-form :info args))

(defmacro warn
  "Log a warning message. A leading Throwable is attached to the log event."
  [& args]
  (log-form :warn args))

(defmacro error
  "Log an error message. A leading Throwable is attached to the log event."
  [& args]
  (log-form :error args))
