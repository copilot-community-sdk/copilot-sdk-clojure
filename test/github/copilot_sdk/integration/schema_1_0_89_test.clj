(ns github.copilot-sdk.integration.schema-1-0-89-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.generated.event-specs :as wire]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* await-event-type! with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]))

(defn- valid?
  [spec value]
  (and (some? (s/get-spec spec)) (s/valid? spec value)))

(deftest model-deselection-is-a-public-session-event
  (let [event-type :copilot/session.model_deselected]
    (is (contains? sdk/event-types event-type))
    (is (contains? sdk/session-events event-type))
    (is (s/valid? ::specs/event-type event-type))
    (when (contains? sdk/event-types event-type)
      (is (= (sdk/evt :session.model_deselected) event-type))))
  (doseq [spec [::specs/session.model_deselected-data
                ::wire/session.model_deselected-data]]
    (doseq [data [{:previous-model "host/model" :reason "provider_withdrawn"}
                  {:previous-model "" :reason "provider_withdrawn"}
                  {:previous-model "host/model" :reason "provider_withdrawn"
                   :future-field true}]]
      (is (valid? spec data) (str spec " " data)))
    (doseq [data [{}
                  {:previous-model "host/model"}
                  {:reason "provider_withdrawn"}
                  {:previous-model nil :reason "provider_withdrawn"}
                  {:previous-model false :reason "provider_withdrawn"}
                  {:previous-model "host/model" :reason nil}
                  {:previous-model "host/model" :reason "unknown"}]]
      (is (not (valid? spec data)) (str spec " " data))))
  (is (s/valid? ::specs/session.model_change-data
                {:previous-model nil :new-model "default"})
      "Model-change retains its distinct nullable previous-model contract"))

(deftest optional-tool-title-contract
  (doseq [spec [::specs/tool.execution_start-data
                ::wire/tool.execution_start-data]
          [title expected] [[::absent true] ["" true] ["Search code" true]
                            [nil false] [false false] [1 false]]]
    (let [data (cond-> {:tool-call-id "call-1" :tool-name "search"}
                 (not= title ::absent) (assoc :tool-title title))]
      (is (= (valid? spec data) expected) (str spec " " data)))))

(deftest ordered-system-message-block-contract
  (doseq [spec [::specs/system.message-data ::wire/system.message-data]
          [blocks expected] [[::absent true] [[] true]
                             [[{:content ""}] true]
                             [[{:content "one"} {:content "two"}] true]
                             [nil false] [false false] [{} false]
                             [#{{:content "unordered"}} false]
                             [[{}] false] [[{:content nil}] false]
                             [[{:content "one" :unknown true}] false]]]
    (let [data (cond-> {:content "prompt" :role "system"}
                 (not= blocks ::absent) (assoc :content-blocks blocks))]
      (is (= (valid? spec data) expected) (str spec " " data))))
  (doseq [blocks [{} #{{:content "unordered"}} '({:content "list"})]]
    (is (not (valid? ::specs/system.message-data
                     {:role "system" :content "prompt" :content-blocks blocks}))
        "The public idiom contract requires an ordered vector"))
  (doseq [spec [::specs/system.message-data ::wire/system.message-data]
          cache-breakpoint [::absent false true nil ""]
          is-static [::absent false true nil ""]]
    (let [block (cond-> {:content "prompt"}
                  (not= cache-breakpoint ::absent)
                  (assoc :cache-breakpoint cache-breakpoint)
                  (not= is-static ::absent)
                  (assoc :is-static is-static))
          expected (and (contains? #{::absent false true} cache-breakpoint)
                        (contains? #{::absent false true} is-static))]
      (is (= (valid? spec {:role "developer" :content "prompt"
                           :content-blocks [block]})
             expected)
          (str spec " " block)))))

(deftest event-fields-survive-live-and-historical-protocol-paths
  (with-mock-server
    (fn []
      (let [session (sdk/create-session *test-client*
                                        {:on-permission-request sdk/approve-all})
            session-id (sdk/session-id session)
            events (sdk/subscribe-events session)
            cases
            (concat
             [[:copilot/session.model_deselected
               {:previousModel "host/model" :reason "provider_withdrawn"}
               {:previous-model "host/model" :reason "provider_withdrawn"}]]
             (for [title [::absent "" "Search code"]]
               [:copilot/tool.execution_start
                (cond-> {:toolCallId "call-1" :toolName "search"}
                  (not= title ::absent) (assoc :toolTitle title))
                (cond-> {:tool-call-id "call-1" :tool-name "search"}
                  (not= title ::absent) (assoc :tool-title title))])
             (for [[wire-blocks idiom-blocks]
                   [[::absent ::absent]
                    [[] []]
                    [[{:content "first"}
                      {:content "" :cacheBreakpoint false :isStatic true}
                      {:content "last" :cacheBreakpoint true :isStatic false}]
                     [{:content "first"}
                      {:content "" :cache-breakpoint false :is-static true}
                      {:content "last" :cache-breakpoint true :is-static false}]]]]
               [:copilot/system.message
                (cond-> {:role "system" :content "prompt"}
                  (not= wire-blocks ::absent) (assoc :contentBlocks wire-blocks))
                (cond-> {:role "system" :content "prompt"}
                  (not= idiom-blocks ::absent) (assoc :content-blocks idiom-blocks))]))]
        (try
          (doseq [[event-type wire-data expected] cases]
            (testing (str event-type " " wire-data)
              (mock/send-session-event! *mock-server* session-id event-type wire-data)
              (let [event (await-event-type! events event-type 2000)]
                (is (= (:data event) expected)))
              (mock/set-session-messages!
               *mock-server* session-id
               [{:type (name event-type) :data wire-data}])
              (let [[event] (sdk/get-messages session)]
                (is (= (:type event) event-type))
                (is (= (:data event) expected)))))
          (finally
            (sdk/unsubscribe-events! session events)
            (sdk/disconnect! session)))))))
