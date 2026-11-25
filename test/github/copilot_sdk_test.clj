(ns github.copilot-sdk-test
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]
            [github.copilot-sdk.session-options :as options]
            [github.copilot-sdk.specs :as specs])
  (:import [java.util.concurrent.locks Lock]))

(deftest interrupted-await-delivers-a-result-already-claimed
  (let [parked (promise)
        finished (promise)
        port (reify ap/ReadPort
               (take! [_ h] (deliver parked h) nil))
        waiter (Thread. #(try
                           (let [v (sdk/await!! port)]
                             (deliver finished [v (.isInterrupted (Thread/currentThread))]))
                           (catch Exception e (deliver finished e))))]
    (try
      (.start waiter)
      (let [^Lock h (deref parked 3000 nil)
            _ (.lock h)
            deliver! (try (ap/commit h) (finally (.unlock h)))]
        ;; The channel selected the take, but its delivery callback has not run.
        (.interrupt waiter)
        (deliver! {:ok :claimed})
        (is (= (deref finished 3000 ::timeout) [:claimed true])))
      (finally (.interrupt waiter) (.join waiter 3000)))))

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
                       ("session.create" "session.resume") {:session-id (:session-id params)}
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
            (is (= (try (sdk/open-session!! c options)
                        (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
                   :session-cleanup-unconfirmed))
            (let [session (sdk/open-session!! c (assoc options :session-id "independent"))
                  body-error (ex-info "Body failed" {})
                  error (try
                          (sdk/with-session [other c {:session-id "other"}]
                            (reset! reject? true)
                            (throw body-error))
                          (catch Throwable t t))]
              (is (identical? body-error error))
              (is (= ["Runtime did not detach the session"] (mapv ex-message (.getSuppressed error))))
              (let [history-error (ex-info "History failed" {})
                    resumed-error (try (sdk/with-resumed-session [resumed c "saved" {}]
                                         (is (= (sdk/session-id resumed) "saved"))
                                         (throw history-error))
                                       (catch Exception e e))]
                (is (identical? resumed-error history-error))
                (is (= (mapv ex-message (.getSuppressed resumed-error)) ["Runtime did not detach the session"])))
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not detach" (sdk/close!! session)))
              (is (nil? (sdk/await!! (sdk/abort! session))))
              (reset! reject? false)
              (sdk/close!! session)))))
      (is (= (last @calls) ["runtime.shutdown" nil]))
      (finally (rpc/disconnect conn)))))
