(ns github.copilot-sdk-test
  (:require [clojure.core.async :as a]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.session-options :as options]
            [github.copilot-sdk.specs :as specs]))

(deftest mcp-tool-result-conversion
  (let [resource-uri "file:///Report_DIR/Chart.PNG?My_Key=AbC"
        result (sdk/mcp-tool-result
                {:content [{:type "text" :text "Start"}
                           {:type "image" :mime-type "image/SVG+xml" :data "aQ=="}
                           {:type "text" :text ""}
                           {:type "resource" :resource {:uri resource-uri :text "Report" :blob "AQI="}}
                           {:type "resource" :resource {:uri "urn:A_b" :text "" :blob "Ag==" :mime-type ""}}
                           {:type "resource" :resource {:uri "urn:X" :blob "Aw==" :mime-type "text/X-Data"}}
                           {:type "text" :text "End"}]
                 :is-error? true})]
    (is (= result {:text-result-for-llm "Start\n\nReport\nEnd" :result-type :failure
                   :binary-results-for-llm [{:type "image" :mime-type "image/SVG+xml" :data "aQ=="}
                                            {:type "resource" :mime-type "application/octet-stream"
                                             :data "AQI=" :description resource-uri}
                                            {:type "resource" :mime-type "application/octet-stream"
                                             :data "Ag==" :description "urn:A_b"}
                                            {:type "resource" :mime-type "text/X-Data"
                                             :data "Aw==" :description "urn:X"}]}))
    (is (s/valid? ::specs/tool-result-object result)))
  (doseq [input [{:content []}
                 {:content [{:type "text"} {:type "resource"} {:type "image" :data ""}
                            {:type "audio" :data "AQ=="}]
                  :is-error? false}]]
    (is (= (sdk/mcp-tool-result input) {:text-result-for-llm "" :result-type :success}))))

(deftest mcp-tool-result-validation
  (doseq [input [{} {:content nil} {:content [] :is-error? nil} {:content [] :isError true}
                 {:content [{:type "image" :data "not base64!" :mime-type "image/png"}]}
                 {:content [{:type "resource" :resource {:blob "AQ=="}}]}]]
    (is (thrown? clojure.lang.ExceptionInfo (sdk/mcp-tool-result input)))))

(deftest invalid-provider-options-do-not-disclose-credentials
  (let [marker "credential-value-used-only-for-this-test"
        config {:inference {:providers [{:name "p" :base-url "http://localhost"
                                         :api-key marker :headers {"Authorization" marker}
                                         :provider-type :invalid}]}}]
    (doseq [build [#(options/create-params {:mode :coding-agent} config)
                   #(options/resume-params {:mode :coding-agent} "s" config)]]
      (let [error (try (build) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= (:path (ex-data error)) [:inference :providers 0 :provider-type]))
        (is (not (str/includes? (pr-str (Throwable->map error)) marker)))))))

(deftest failed-open-and-detach-preserve-the-primary-error-and-session-ownership
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        calls (atom [])
        reject? (atom true)]
    (try
      (rpc/set-request-handler!
       conn (fn [method params]
              (swap! calls conj [method (:session-id params)])
              (a/go {:result
                     (case method
                       "connect" {:protocol-version 4}
                       "session.create" {:session-id (:session-id params)}
                       ("session.options.update" "session.detach") {:success (not @reject?)}
                       "session.abort" {:success true}
                       "runtime.shutdown" {})})))
      (with-redefs [host/open! (fn [_] {:in <-client :out ->client :configure-ui! (constantly nil) :close! #(.close ->client)})]
        (sdk/with-client [c {:mode :coding-agent}]
          (let [id "same-session"
                options {:session-id id :tools {:manage-schedule-enabled? false}}
                failure (try (sdk/open-session!! c options) nil
                             (catch clojure.lang.ExceptionInfo e e))]
            (is (some? failure))
            (when failure
              (is (re-find #"options patch" (ex-message failure)))
              (is (= ["Runtime did not detach the session"] (mapv ex-message (.getSuppressed failure)))))
            (is (= @calls [["connect" nil] ["session.create" id] ["session.options.update" id] ["session.detach" id]]))
            (reset! reject? false)
            (let [session (sdk/open-session!! c options)
                  body-error (ex-info "Body failed" {})
                  error (try
                          (sdk/with-session [other c {:session-id "other"}]
                            (reset! reject? true)
                            (throw body-error))
                          (catch Throwable t t))]
              (is (identical? body-error error))
              (is (= ["Runtime did not detach the session"] (mapv ex-message (.getSuppressed error))))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not detach" (sdk/close!! session)))
              (is (nil? (sdk/await!! (sdk/abort! session))))
              (reset! reject? false)
              (sdk/close!! session)))))
      (is (= (last @calls) ["runtime.shutdown" nil]))
      (finally (rpc/disconnect conn)))))
