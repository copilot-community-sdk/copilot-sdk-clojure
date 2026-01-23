(ns krukow.copilot-sdk.java.session-options
  "Java SessionOptions class with builder pattern."
  (:gen-class
   :name krukow.copilot_sdk.SessionOptions
   :state state
   :init init
   :constructors {[java.util.Map] []}
   :methods [[getModel [] String]
             [getStreaming [] Boolean]
             [getSystemPrompt [] String]
             [getAllowedTools [] java.util.List]
             [getExcludedTools [] java.util.List]
             [getConfigDir [] String]
             [getSkillDirectories [] java.util.List]
             [getDisabledSkills [] java.util.List]
             [toMap [] java.util.Map]]))

(defn -init [opts]
  [[] opts])

(defn -getModel [this] (.get ^java.util.Map (.state this) "model"))
(defn -getStreaming [this] (.get ^java.util.Map (.state this) "streaming?"))
(defn -getSystemPrompt [this] (.get ^java.util.Map (.state this) "system-prompt"))
(defn -getAllowedTools [this] (.get ^java.util.Map (.state this) "available-tools"))
(defn -getExcludedTools [this] (.get ^java.util.Map (.state this) "excluded-tools"))
(defn -getConfigDir [this] (.get ^java.util.Map (.state this) "config-dir"))
(defn -getSkillDirectories [this] (.get ^java.util.Map (.state this) "skill-directories"))
(defn -getDisabledSkills [this] (.get ^java.util.Map (.state this) "disabled-skills"))

(defn -toMap [this]
  (.state this))
