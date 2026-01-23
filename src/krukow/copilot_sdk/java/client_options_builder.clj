(ns krukow.copilot-sdk.java.client-options-builder
  "Builder for ClientOptions."
  (:require [krukow.copilot-sdk.java.client-options])
  (:gen-class
   :name krukow.copilot_sdk.ClientOptionsBuilder
   :state state
   :init init
   :constructors {[] []}
   :methods [[cliPath [String] Object]
             [cwd [String] Object]
             [port [int] Object]
             [useStdio [boolean] Object]
             [logLevel [String] Object]
             [autoStart [boolean] Object]
             [autoRestart [boolean] Object]
             [env [java.util.Map] Object]
             [build [] Object]]))

(defn -init []
  [[] (atom {})])

(defn -cliPath [this v]
  (swap! (.state this) assoc "cli-path" v)
  this)

(defn -cwd [this v]
  (swap! (.state this) assoc "cwd" v)
  this)

(defn -port [this v]
  (swap! (.state this) assoc "port" (Integer/valueOf v))
  this)

(defn -useStdio [this v]
  (swap! (.state this) assoc "use-stdio?" (Boolean/valueOf v))
  this)

(defn -logLevel [this v]
  (swap! (.state this) assoc "log-level" v)
  this)

(defn -autoStart [this v]
  (swap! (.state this) assoc "auto-start?" (Boolean/valueOf v))
  this)

(defn -autoRestart [this v]
  (swap! (.state this) assoc "auto-restart?" (Boolean/valueOf v))
  this)

(defn -env [this v]
  (swap! (.state this) assoc "env" v)
  this)

(defn -build [this]
  (krukow.copilot_sdk.ClientOptions. (java.util.HashMap. @(.state this))))
