(ns github.copilot-sdk.events-behavior-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.public-behavior-support :as support]))

(use-fixtures :each support/with-piped-client)

(deftest events-to-chan-applies-tool-event-filter
  (let [session (sdk/create-session support/*test-client* {:session-id "filtered-events"})
        events-ch (sdk/events->chan
                   session
                   {:xf (filter (comp sdk/tool-events :type))})
        session-id (sdk/session-id session)]
    (mock/send-session-event! support/*mock-server* session-id
                              :copilot/session.snapshot_rewind
                              {:upToEventId "before-tool" :eventsRemoved 0})
    (mock/send-session-event! support/*mock-server* session-id
                              :copilot/tool.execution_start
                              {:toolCallId "tool-1"
                               :toolName "search"
                               :arguments {}})
    (is (= :copilot/tool.execution_start
           (:type (support/read-within!! events-ch))))
    (sdk/unsubscribe-events! session events-ch)))

(deftest unsubscribe-events-closes-owned-channel
  (let [session (sdk/create-session support/*test-client* {:session-id "unsubscribe-events"})
        events-ch (sdk/events->chan session)]
    (sdk/unsubscribe-events! session events-ch)
    (is (nil? (support/read-within!! events-ch)))))

(deftest events-to-chan-retains-latest-value-in-sliding-buffer
  (let [session (sdk/create-session support/*test-client* {:session-id "sliding-events"})
        processed (promise)
        xf (keep (fn [event]
                   (let [sequence (get-in event [:data :events-removed])]
                     (if (= 4 sequence)
                       (do
                         (deliver processed :all-processed)
                         nil)
                       event))))
        events-ch (sdk/events->chan session {:buffer 1 :xf xf})
        session-id (sdk/session-id session)]
    (doseq [sequence (range 1 5)]
      (mock/send-session-event! support/*mock-server* session-id
                                :copilot/session.snapshot_rewind
                                {:upToEventId (str "event-" sequence)
                                 :eventsRemoved sequence}))
    (is (= :all-processed (deref processed 5000 ::timeout)))
    (is (= 3 (get-in (support/read-within!! events-ch)
                     [:data :events-removed])))
    (sdk/unsubscribe-events! session events-ch)))
