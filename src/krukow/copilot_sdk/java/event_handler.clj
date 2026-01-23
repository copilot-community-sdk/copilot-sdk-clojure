(ns krukow.copilot-sdk.java.event-handler
  "Java IEventHandler interface for handling session events.")

(gen-interface
 :name krukow.copilot_sdk.IEventHandler
 :methods [[handle [krukow.copilot_sdk.Event] void]])
