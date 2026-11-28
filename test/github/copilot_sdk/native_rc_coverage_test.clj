(ns github.copilot-sdk.native-rc-coverage-test
  (:require [clojure.core.async :as a]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [copilot.sdk :as sdk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.session-options :as options]
            [copilot.sdk.specs :as specs])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util HexFormat]))

(defn- resource [file] (io/resource (str "resources/" file)))

(defn- blob-id [bytes]
  (let [digest (MessageDigest/getInstance "SHA-1")]
    (.update digest (.getBytes (str "blob " (alength ^bytes bytes) "\u0000") "UTF-8"))
    (.formatHex (HexFormat/of) (.digest digest bytes))))

(defn- exact-partition! [members allocations]
  (let [assigned (mapcat val allocations)
        actual (set assigned)
        expected (set members)]
    (is (= (count assigned) (count actual)) "contract assigned more than once")
    (is (= expected actual)
        (pr-str {:unassigned (set/difference expected actual)
                 :unknown (set/difference actual expected)}))))

(defn- registry-var [prefixes id]
  (when-let [ns-sym (get prefixes (symbol (namespace id)))]
    (require ns-sym)
    (ns-resolve ns-sym (symbol (name id)))))

(deftest overlay-covers-the-frozen-contracts
  (let [{:keys [catalog catalog-git-blob rows root-values methods field-owners field-overrides
                callbacks symbol-prefixes upstream builder-probes]} (edn/read-string (slurp (resource "native_2_0_1_rc_overlay.edn")))
        bytes (with-open [in (io/input-stream (resource catalog))] (.readAllBytes in))
        baseline (edn/read-string (String. bytes "UTF-8"))
        all-fields (set (for [[owner groups] (:option-fields baseline) field (mapcat val groups)]
                          (symbol (name owner) (name field))))]
    (is (= catalog-git-blob (blob-id bytes)) "Do not update the M1 baseline in place")
    (is (= upstream (get-in baseline [:upstream :commit])))
    (exact-partition! (keys (:root-values baseline)) root-values)
    (is (= (set (keys (:methods baseline))) (set (keys methods))))
    (doseq [[owner allocations] methods]
      (testing (name owner)
        (exact-partition! (keys (get-in baseline [:methods owner])) allocations)))
    (exact-partition! (keys (:option-fields baseline)) field-owners)
    (let [overrides (mapcat val field-overrides)]
      (is (apply distinct? overrides))
      (is (every? all-fields overrides)))
    (let [fields (into {} (for [[row members] field-overrides member members] [member row]))
          owners (into {} (for [[row types] field-owners owner types] [owner row]))]
      (doseq [{:keys [field options wire-path value]} builder-probes
              build [#(options/create-params {:mode :coding-agent} %)
                     #(options/resume-params {:mode :coding-agent} "s" %)]]
        (testing (str field)
          (case (:status (rows (get fields field (owners (symbol (namespace field))))))
            :mapped (is (= (get-in (build options) wire-path ::absent) value))
            (:missing :excluded) (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported option" (build options)))))))
    (exact-partition! (map #(symbol (str/replace (:path %) "." "/")) (:callbacks baseline)) callbacks)
    (is (= (set (keys rows))
           (into #{} (mapcat keys) (concat (vals methods) [root-values field-owners field-overrides callbacks]))))
    (doseq [[id {:keys [status local tests]}] rows]
      (testing (name id)
        (is (#{:mapped :partial :missing :excluded} status))
        (is (or (= :excluded status) (seq local)))
        (doseq [s local]
          (is (var? (registry-var symbol-prefixes s)) (str "Missing source var " s)))
        (doseq [s tests]
          (let [metadata (meta (registry-var symbol-prefixes s))]
            ;; The test runner temporarily moves the :test fn for excluded tests.
            (is (fn? (or (:test metadata) (:cognitect.test-runner/test metadata))) (str "Missing behavioral test " s))))))))

(deftest public-system-message-sections-encode-on-create-and-resume
  (let [sections specs/system-message-sections
        overrides (zipmap (keys sections) (repeat {:action :preserve}))
        wire (into {} (map (fn [k] [(str/replace (name k) "-" "_") {"action" "preserve"}])) (keys sections))
        config {:system-message {:mode :customize :sections overrides}}]
    (is (seq sections))
    (is (every? #(not (str/blank? (:description %))) (vals sections)))
    (doseq [request [(options/create-params {:mode :coding-agent} config)
                     (options/resume-params {:mode :coding-agent} "s" config)]]
      (is (= (get-in request ["systemMessage" "sections"]) wire)))))

(deftest public-lifecycle-and-model-switch
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        requests (atom [])
        timestamp "2026-01-01T00:00:00Z"]
    (try
      (rpc/set-request-handler!
       conn
       (fn [method params]
         (swap! requests conj [method params])
         (a/go {:result (case method
                          "connect" {:protocol-version 4}
                          "session.resume" {:session-id (:session-id params) :workspace-path "/virtual/s"
                                            :capabilities {:ui {:elicitation true}}
                                            :permission-mode-result {:success false :mode "manual" :reason "managed_policy"}
                                            :transcript-recovery {:invalid-line-numbers [5]}}
                          ("session.model.switchTo" "session.log" "session.detach" "runtime.shutdown") {:success true})})))
      (with-redefs [host/open! (fn [_] {:in <-client :out ->client :configure-ui! (constantly nil) :close! #(.close ->client)})]
        (sdk/with-client [c {:mode :coding-agent :client-info {:application-name "rc" :integration-version ""}
                             :observability {:on-get-trace-context (constantly {:traceparent "trace-id"})}}]
          (let [events (sdk/lifecycle-events c)]
            (try
              (rpc/send-notification conn "session.lifecycle"
                                     {:type "session.updated" :session-id "s"
                                      :metadata {:start-time timestamp :modified-time timestamp}})
              (let [[event ch] (a/alts!! [events (a/timeout 5000)])]
                (is (= ch events))
                (is (= event {:type :session.updated :session-id "s"
                              :metadata {:start-time (Instant/parse timestamp) :modified-time (Instant/parse timestamp)}})))
              (with-open [s (sdk/resume-session!! c "s" {})]
                (is (= (sdk/session-info s) {:session-id "s" :workspace-path "/virtual/s"
                                             :capabilities {:ui {:elicitation true}}
                                             :permission-mode-result {:success false :mode "manual" :reason "managed_policy"}
                                             :transcript-recovery {:invalid-line-numbers [5]}}))
                (is (nil? (sdk/await!! (sdk/set-model! s "new" {:reasoning-effort "low"
                                                                :model-capabilities {:supports {:tool-calls? false}}}))))
                (is (nil? (sdk/await!! (sdk/log! s "test" {:level :info :ephemeral? false})))))
              (finally (a/close! events))))))
      (let [calls (into {} @requests)]
        (is (= (get-in calls ["connect" :client-info]) {:editor-name "rc"}))
        (is (= (get-in calls ["session.resume" :traceparent]) "trace-id"))
        (is (= (get calls "session.model.switchTo") {:session-id "s" :model-id "new" :reasoning-effort "low"
                                                     :model-capabilities {:supports {:tool-calls false}}}))
        (is (= (get calls "session.log") {:session-id "s" :message "test" :level "info" :ephemeral false})))
      (finally (rpc/disconnect conn)))))
