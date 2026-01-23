(ns krukow.copilot-sdk.java.session-options-builder
  "Builder for SessionOptions."
  (:require [krukow.copilot-sdk.java.session-options])
  (:gen-class
   :name krukow.copilot_sdk.SessionOptionsBuilder
   :state state
   :init init
   :constructors {[] []}
   :methods [[model [String] Object]
             [streaming [boolean] Object]
             [systemPrompt [String] Object]
             [allowedTools [java.util.List] Object]
             [allowedTool [String] Object]
             [excludedTools [java.util.List] Object]
             [excludedTool [String] Object]
             [configDir [String] Object]
             [skillDirectories [java.util.List] Object]
             [skillDirectory [String] Object]
             [disabledSkills [java.util.List] Object]
             [disabledSkill [String] Object]
             [build [] Object]]))

(defn -init []
  [[] (atom {})])

(defn -model [this v]
  (swap! (.state this) assoc "model" v)
  this)

(defn -streaming [this v]
  (swap! (.state this) assoc "streaming?" (Boolean/valueOf v))
  this)

(defn -systemPrompt [this v]
  (swap! (.state this) assoc "system-prompt" v)
  this)

(defn -allowedTools [this v]
  (swap! (.state this) assoc "available-tools" (java.util.ArrayList. v))
  this)

(defn -allowedTool [this v]
  (swap! (.state this) update "available-tools"
         (fn [lst] (let [l (or lst (java.util.ArrayList.))]
                     (.add ^java.util.List l v)
                     l)))
  this)

(defn -excludedTools [this v]
  (swap! (.state this) assoc "excluded-tools" (java.util.ArrayList. v))
  this)

(defn -excludedTool [this v]
  (swap! (.state this) update "excluded-tools"
         (fn [lst] (let [l (or lst (java.util.ArrayList.))]
                     (.add ^java.util.List l v)
                     l)))
  this)

(defn -configDir [this v]
  (swap! (.state this) assoc "config-dir" v)
  this)

(defn -skillDirectories [this v]
  (swap! (.state this) assoc "skill-directories" (java.util.ArrayList. v))
  this)

(defn -skillDirectory [this v]
  (swap! (.state this) update "skill-directories"
         (fn [lst] (let [l (or lst (java.util.ArrayList.))]
                     (.add ^java.util.List l v)
                     l)))
  this)

(defn -disabledSkills [this v]
  (swap! (.state this) assoc "disabled-skills" (java.util.ArrayList. v))
  this)

(defn -disabledSkill [this v]
  (swap! (.state this) update "disabled-skills"
         (fn [lst] (let [l (or lst (java.util.ArrayList.))]
                     (.add ^java.util.List l v)
                     l)))
  this)

(defn -build [this]
  (krukow.copilot_sdk.SessionOptions. (java.util.HashMap. @(.state this))))
