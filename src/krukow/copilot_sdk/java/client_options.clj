(ns krukow.copilot-sdk.java.client-options
  "Java ClientOptions class with builder pattern."
  (:gen-class
   :name krukow.copilot_sdk.ClientOptions
   :state state
   :init init
   :constructors {[java.util.Map] []}
   :methods [[getCliPath [] String]
             [getCwd [] String]
             [getPort [] Integer]
             [getUseStdio [] Boolean]
             [getLogLevel [] String]
             [getAutoStart [] Boolean]
             [getAutoRestart [] Boolean]
             [getEnv [] java.util.Map]
             [toMap [] java.util.Map]]))

(defn -init [opts]
  [[] opts])

(defn -getCliPath [this] (.get ^java.util.Map (.state this) "cli-path"))
(defn -getCwd [this] (.get ^java.util.Map (.state this) "cwd"))
(defn -getPort [this] (.get ^java.util.Map (.state this) "port"))
(defn -getUseStdio [this] (.get ^java.util.Map (.state this) "use-stdio?"))
(defn -getLogLevel [this] (.get ^java.util.Map (.state this) "log-level"))
(defn -getAutoStart [this] (.get ^java.util.Map (.state this) "auto-start?"))
(defn -getAutoRestart [this] (.get ^java.util.Map (.state this) "auto-restart?"))
(defn -getEnv [this] (.get ^java.util.Map (.state this) "env"))

(defn -toMap [this]
  (.state this))
