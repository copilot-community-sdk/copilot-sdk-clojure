(ns copilot.sdk.helpers-test
  (:require [clojure.core.async :as a]
            [clojure.core.async.impl.protocols :as ap]
            [clojure.spec.test.alpha :as stest]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [copilot.sdk :as sdk]
            [copilot.sdk.helpers :as h]
            [copilot.sdk.instrument]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-fs :as fs]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]))

(deftest complete-json-values
  (doseq [[text expected]
          [[" \t{\"ID/No\": [true, false, null], \"id_no\": \"\\u0061\\n\"}\r\n"
            {"ID/No" [true false nil] "id_no" "a\n"}]
           ["false " false] ["null" nil] ["\"\"" ""] ["1e400" 1E+400M]
           ["123456789012345678901234567890" 123456789012345678901234567890N]]]
    (is (= (h/decode-json text) expected)))
  (doseq [invalid ["" "[] null" "false!" "null\u000b" "01" "-01" "+1" "1." "1e+" "NaN"
                   "[false,]" "{\"a\": 0,}" "\"line\nbreak\"" "\"\\u+123\"" "\"\\u１２３４\""
                   "\"\\q\"" "\"unfinished" "1e9999999999999999"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one complete JSON value" (h/decode-json invalid))))
  (let [marker "confidential-reply"
        error (try (h/decode-json (str "{\"secret\":\"" marker "\"} " marker))
                   (catch clojure.lang.ExceptionInfo e e))]
    (is (= (ex-data error) {:type :invalid-json}))
    (is (not (str/includes? (pr-str (Throwable->map error)) marker)))))

(deftest json-depth-counts-containers-not-strings-or-siblings
  (let [nested (fn [n leaf]
                 (nth (iterate #(str "[{\"k\":" % "}]") leaf) n))
        marker "private-decoder-input"
        leaf (str "\"" marker "\"")
        error-for (fn [text]
                    (try (h/decode-json text) nil (catch clojure.lang.ExceptionInfo e e)))]
    (is (= (h/decode-json leaf) marker))
    (is (= (get-in (h/decode-json (nested 64 leaf)) (take 128 (cycle [0 "k"]))) marker))
    (doseq [over-limit [(nested 64 (str "[" leaf "]"))
                        (str "[\"" marker "\"," (apply str (repeat 30000 \[)))]]
      (let [error (error-for over-limit)]
        (is (= (ex-data error) {:type :invalid-json :max-depth 128}))
        (is (not (str/includes? (pr-str (Throwable->map error)) marker)))))
    (is (= (ex-data (error-for (nested 64 (str marker "!")))) {:type :invalid-json})))
  (let [brackets (apply str (repeat 256 "[{"))
        token (str "\"\\\"\\\\" brackets "\"")
        text (str "[" (str/join "," (repeat 256 token)) "]")]
    (is (= (h/decode-json text) (vec (repeat 256 (str "\"\\" brackets)))))))

(deftest json-string-controls
  (doseq [n (range 32)]
    (is (thrown? clojure.lang.ExceptionInfo (h/decode-json (str "\"" (char n) "\""))))
    (is (= (h/decode-json (format "\"\\u%04x\"" (int n))) (str (char n)))))
  (is (= (h/decode-json "\"\u0085\u2028\u2029\"") "\u0085\u2028\u2029")))

(def ^:private config {:client {:mode :coding-agent} :session {:session-id "q"}})

(deftest invalid-config-does-not-start-a-host
  (with-redefs [host/open! (fn [_] (throw (AssertionError. "Unexpected host startup")))]
    (doseq [invalid [nil {} {:client {:mode :coding-agent}} (assoc config :client {})
                     (assoc-in config [:session :session-id] false)
                     {:client {:mode :empty} :session {}}
                     (assoc config :session nil) (assoc config :unknown true)]]
      (is (thrown? clojure.lang.ExceptionInfo (h/with-conversation [_ invalid] :opened))))
    (let [marker "fixture-only-private-config"
          bad (assoc-in (assoc config :extra true) [:session :github :token] marker)
          error (try (h/query-once!! bad "hello") (catch clojure.lang.ExceptionInfo e e))]
      (is (= (ex-data error) {:type :invalid-conversation-config}))
      (is (not (str/includes? (pr-str (Throwable->map error)) marker))))))

(deftest invalid-helper-input-does-not-open-a-host-or-escape-in-spec-errors
  (let [marker "fixture-only-secret"
        headers {"Authorization" marker}
        err (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))
        helpers [`h/query-once!! `h/query!! `h/query-json!! `h/decode-json]]
    (with-redefs [host/open! (fn [_] (throw (AssertionError. "Invalid message opened a host")))]
      (try
        (stest/instrument helpers)
        (doseq [[invalid kind] [[{:request-headers headers} :invalid-message]
                                [{:prompt false :request-headers headers} :invalid-message]
                                [{:prompt "x" :attachments false :request-headers headers} :invalid-message]
                                [(sorted-map "prompt" marker) :invalid-message]
                                [{:prompt "x" :attachments [(sorted-map "type" marker)]} :invalid-message]
                                [{:prompt "x" :timeout-ms marker} :invalid-timeout]]]
          (let [e (err #(h/query-once!! config invalid))]
            (is (= (:type (ex-data e)) kind))
            (is (not (str/includes? (pr-str (Throwable->map e)) marker)))))
        (doseq [call [#(h/query-once!! (assoc config :extra marker) "hello")
                      #(h/decode-json {:content marker})
                      #(h/query-json!! nil {:prompt marker})
                      #(h/query!! nil {:prompt marker})]]
          (let [e (err call)]
            (is (some? e))
            (is (not (str/includes? (pr-str (Throwable->map e)) marker)))))
        (finally (stest/unstrument helpers) (copilot.sdk.instrument/instrument-all!))))))

(deftest filesystem-config-fails-before-host-or-factory-acquisition
  (let [config-fs {:initial-cwd "/work" :session-state-path "/sessions" :conventions "posix"}
        fs-client {:mode :empty :session-fs config-fs}
        session {:cloud {} :tools {:available-tools []}}
        factory (fn [_] (throw (AssertionError. "Called a factory during validation")))
        marker "fixture-private-fs"
        fs-sym `fs/provider-params]
    (with-redefs [host/open! (fn [_] (throw (AssertionError. "Invalid filesystem opened a host")))]
      (try
        (stest/instrument [fs-sym])
        (doseq [[client-options session-options]
                [[fs-client session]
                 [{:mode :empty :base-directory "/unused"} (assoc-in session [:state :create-session-fs-provider] factory)]
                 [fs-client (assoc-in session [:state :create-session-fs-provider] {})]
                 [(assoc-in fs-client [:session-fs :github] {:token marker})
                  (assoc-in session [:state :create-session-fs-provider] factory)]]]
          (let [e (try (h/query-once!! {:client client-options :session session-options} "test")
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (instance? clojure.lang.ExceptionInfo e))
            (is (not (str/includes? (pr-str (Throwable->map e)) marker)))))
        (finally (stest/unstrument [fs-sym]))))))

(defn- with-turn-peer [exercise]
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        calls (atom [])]
    (try
      (rpc/set-request-handler!
       conn (fn [method {:keys [session-id prompt] :as params}]
              (swap! calls conj [method params])
              (a/go {:result (case method
                               "connect" {:protocol-version 4}
                               "sessionFs.setProvider" {:success true}
                               "session.create" {:session-id session-id}
                               "session.sendAndWait"
                               (cond-> {:message-id "reply" :aborted (= "abort" prompt)}
                                 (not= "silent" prompt)
                                 (assoc :response {:type "assistant.message" :data {:content prompt}}))
                               "session.detach" {:success true}
                               "runtime.shutdown" {})})))
      (with-redefs [host/open! (fn [options]
                                 (swap! calls conj [:open options])
                                 {:in <-client :out ->client :configure-ui! (constantly nil)
                                  :close! #(swap! calls conj [:close])})]
        (exercise conn calls))
      (finally (rpc/disconnect conn) (.close <-client) (.close ->client)))))

(deftest bad-static-provider-does-not-allocate-a-cloud-session
  (with-turn-peer
    (fn [_ calls]
      (sdk/with-client [c {:mode :empty :session-fs {:initial-cwd "/w" :session-state-path "/s" :conventions "posix"}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SessionFs provider"
                              (sdk/open-session!! c {:cloud {} :tools {:available-tools []}
                                                     :state {:create-session-fs-provider {}}}))))
      (is (= (mapv first @calls) [:open "connect" "sessionFs.setProvider" "runtime.shutdown" :close])))))

(deftest invalid-session-acquisition-never-retains-options
  (with-turn-peer
    (fn [_ calls]
      (sdk/with-client [c (:client config)]
        (let [marker "private-session-credential"
              options {:github {:token marker}}]
          (doseq [[call expected] [[#(sdk/open-session!! nil options) :invalid-client]
                                   [#(sdk/with-session [_ c [marker]] :unexpected) :invalid-session-options]
                                   [#(sdk/resume-session!! nil "s" options) :invalid-client]
                                   [#(sdk/with-resumed-session [_ c "" options] :unexpected) :invalid-session-id]]]
            (let [failure (try (call) nil (catch clojure.lang.ExceptionInfo e e))]
              (is (= (:type (ex-data failure)) expected))
              (is (not (str/includes? (pr-str (Throwable->map failure)) marker)))))))
      (is (= (mapv first @calls) [:open "connect" "runtime.shutdown" :close])))))

(deftest qualified-extra-fields-do-not-trigger-raw-nested-spec-errors
  (let [marker "synthetic-private-value"
        capture (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))
        config-error (capture #(h/query-once!! (assoc config :client {:mode :coding-agent
                                                                      :github {:token marker}
                                                                      :copilot.sdk.specs/session-id false}) "hello"))]
    (is (instance? clojure.lang.ExceptionInfo config-error))
    (is (not (str/includes? (pr-str (Throwable->map config-error)) marker)))
    (let [e (capture #(sdk/open-client!! {:mode :coding-agent "baseDirectory" "/w" :github {:token marker}}))]
      (is (= (:type (ex-data e)) :invalid-client-options))
      (is (not (str/includes? (pr-str (Throwable->map e)) marker))))
    (with-turn-peer
      (fn [_ _]
        (h/with-conversation [s config]
          (let [e (capture #(sdk/send-and-wait!! s {:prompt "test" "displayPrompt" "typo"
                                                    :request-headers {"Authorization" marker}}))]
            (is (= (:type (ex-data e)) :invalid-message))
            (is (not (str/includes? (pr-str (Throwable->map e)) marker))))
          (doseq [query [h/query!! h/query-json!!]]
            (let [error (capture #(query s {:prompt "x" :response-schema {"type" "number"}
                                            :request-headers {"Authorization" marker}
                                            :copilot.sdk.specs/session-id false}))]
              (is (= (:type (ex-data error)) :invalid-message))
              (is (not (str/includes? (pr-str (Throwable->map error)) marker))))))))))

(deftest one-shot-query-owns-one-conversation
  (with-turn-peer
    (fn [_ calls]
      (is (= (h/query-once!! config "answer") "answer"))
      (is (= (mapv first @calls) [:open "connect" "session.create" "session.sendAndWait"
                                  "session.detach" "runtime.shutdown" :close]))
      (is (= (get-in @calls [0 1 :mode]) :coding-agent))
      (is (= (get-in @calls [2 1 :session-id]) "q")))))

(deftest overlapping-one-shot-clients-keep-their-own-policy-and-lifetime
  (let [opened (atom #{}) closed (atom #{}) creates (atom {}) sent (atom {})
        both-running (promise)
        peers (atom [])
        configs (mapv (fn [id] {:client {:mode :coding-agent :base-directory id}
                                :session {:session-id id :tools {:available-tools [id]}}}) ["a" "b"])]
    (try
      (with-redefs [host/open!
                    (fn [{id :base-directory}]
                      (let [{:keys [conn ->client <-client] :as p} (peer/open-connection {})]
                        (swap! peers conj p)
                        (swap! opened conj id)
                        (rpc/set-request-handler!
                         conn (fn [method params]
                                (case method
                                  "connect" (a/go {:result {:protocol-version 4}})
                                  "session.create" (do (swap! creates assoc id params)
                                                       (a/go {:result {:session-id (:session-id params)}}))
                                  "session.sendAndWait"
                                  (let [ch (a/promise-chan)]
                                    (when (= 2 (count (swap! sent assoc id [(:prompt params) ch])))
                                      (deliver both-running true))
                                    ch)
                                  "session.detach" (a/go {:result {:success true}})
                                  "runtime.shutdown" (a/go {:result {}}))))
                        {:in <-client :out ->client :configure-ui! (constantly nil)
                         :close! #(swap! closed conj id)}))]
        (let [[a b] (mapv #(future (h/query-once!! % (get-in % [:session :session-id]))) configs)
              finish! (fn [id]
                        (a/put! (second (get @sent id))
                                {:result {:message-id id :aborted false
                                          :response {:type "assistant.message" :data {:content id}}}}))]
          (is (true? (deref both-running 5000 false)))
          (is (= @opened #{"a" "b"}))
          (is (= (mapv #(get-in @creates [% :tools :available-tools]) ["a" "b"]) [["a"] ["b"]]))
          (finish! "b")
          (is (= (deref b 5000 ::timeout) "b"))
          (is (= @closed #{"b"}))
          (finish! "a")
          (is (= (deref a 5000 ::timeout) "a"))
          (is (= @closed #{"a" "b"}))))
      (finally
        (doseq [{:keys [conn ->client <-client]} @peers]
          (rpc/disconnect conn) (.close <-client) (.close ->client))))))

(deftest borrowed-queries-use-one-turn-each
  (with-turn-peer
    (fn [_ calls]
      (let [evaluations (atom 0)
            schema {"type" "boolean"}
            structured (fn [prompt] {:prompt prompt :response-schema schema :timeout-ms 3000})]
        (h/with-conversation [s (do (swap! evaluations inc) config)]
          (is (= @evaluations 1))
          (is (nil? (h/query!! s "silent")))
          (is (false? (h/query-json!! s (structured "false"))))
          (is (nil? (h/query-json!! s (structured "null"))))
          (is (thrown? clojure.lang.ExceptionInfo (h/query-json!! s {:prompt "must not send"})))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one complete JSON value"
                                (h/query-json!! s (structured "false []"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"without a structured"
                                (h/query-json!! s (structured "silent"))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"aborted" (h/query!! s "abort")))
          (is (= (h/query!! s "ready") "ready")))
        (let [turns (mapv second (filter #(= "session.sendAndWait" (first %)) @calls))]
          (is (= (mapv :prompt turns) ["silent" "false" "null" "false []" "silent" "abort" "ready"]))
          (is (= (get-in turns [1 :response-format :json-schema :schema]) {:type "boolean"})))))))

(deftest early-subscription-exit-leaves-the-session-and-other-subscribers-open
  (with-turn-peer
    (fn [conn _]
      (h/with-conversation [s config]
        (h/with-subscription [other s]
          (let [body-failure (ex-info "Consumer exited" {})
                closed (atom nil)]
            (is (identical? body-failure
                            (try (h/with-subscription [events s]
                                   (reset! closed events)
                                   (throw body-failure))
                                 (catch Exception e e))))
            (is (ap/closed? @closed))
            (rpc/send-notification conn "session.event"
                                   {:session-id (sdk/session-id s) :event {:type "session.info" :id "later"}})
            (is (= (:id (first (a/alts!! [other (a/timeout 3000)]))) "later"))
            (is (= (h/query!! s "continued") "continued"))))))))
