(ns github.copilot-sdk.credentials-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.credentials :as credentials]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-client :as client]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.native-offline-fixture :as fixture]
            [github.copilot-sdk.protocol :as rpc]))

(def ^:private token {:kind :token :access-token "fixture-only" :expires-in 28800})

(deftest initial-and-refresh-expose-only-the-public-arguments
  (let [received (atom [])
        provider (fn [args]
                   (swap! received conj args)
                   (case (:reason args)
                     :initial (a/go token)
                     :refresh {:kind :cancelled}))
        reg (credentials/prepare "s" {:github {:token-provider provider}})
        request {:registration-id (:registration-id reg) :host "example.invalid"}]
    (is (= (credentials/handle reg (assoc request :reason "initial"))
           {:result (assoc token :kind "token")}))
    (is (= (credentials/handle reg (assoc request :reason "refresh" :session-id "s"))
           {:result {:kind "cancelled"}}))
    (is (= @received [{:host "example.invalid" :session-id "s" :reason :initial}
                      {:host "example.invalid" :session-id "s" :reason :refresh}]))
    (testing "a cloud session without an assigned ID omits it"
      (let [pending (credentials/prepare nil {:github {:token-provider provider}})]
        (is (= (credentials/handle pending (assoc request :registration-id (:registration-id pending)
                                                  :reason "initial"))
               {:result (assoc token :kind "token")}))
        (is (= (peek @received) {:host "example.invalid" :reason :initial}))))))

(deftest malformed-requests-never-trigger-acquisition
  (let [reg (credentials/prepare "s" {:github {:token-provider (fn [_] (throw (AssertionError.)))}})
        request {:registration-id (:registration-id reg) :host "example.invalid" :reason "initial"}]
    (doseq [bad [{:registration-id "another-registration"} {:host ""}
                 {:session-id "another-session"} {:session-id nil} {:reason "unknown"}]]
      (is (= -32602 (get-in (credentials/handle reg (merge request bad)) [:error :code]))))))

(deftest credential-errors-never-include-provider-data
  (let [result (atom nil)
        reg (credentials/prepare "s" {:github {:token-provider (fn [_] (@result))}})
        request {:registration-id (:registration-id reg) :host "example.invalid" :reason "refresh"}
        call #(credentials/handle reg request)]
    (doseq [bad [(dissoc token :access-token) (assoc token :expires-in 3600)
                 (assoc token :expires-in 3601.5) (assoc token :expires-in nil)
                 (assoc token :token-type nil) (assoc token :access-token " ")
                 {:kind :cancelled :access-token "fixture-only"}]]
      (reset! result (constantly bad))
      (is (= (call) {:error {:code -32603 :message "Invalid GitHub token provider result"}})))
    (reset! result #(throw (ex-info "fixture-only" {:local-secret "fixture-only"})))
    (is (= (call) {:error {:code -32603 :message "GitHub token provider failed"}}))
    (reset! result #(assoc token :token-type "Bearer" :expires-in 3601))
    (is (= (call) {:result (assoc token :kind "token" :token-type "Bearer" :expires-in 3601)}))))

(defn- acquire [runtime registration reason]
  (rpc/send-request-with-timeout runtime "gitHubToken.getToken"
                                 {:registration-id registration :host "example.invalid" :reason reason} 5000))

(defn- with-runtime [f]
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        opens (atom [])]
    (try
      (rpc/set-request-handler!
       conn (fn [method params]
              (let [response
                    (case method
                      "connect" {:result {:protocol-version 4}}
                      ("session.create" "session.resume")
                      (let [github (:github params)
                            reg (:token-provider-registration-id github)
                            result (when reg (a/<!! (acquire conn reg "initial")))]
                        (swap! opens conj [method github])
                        (if (or (not reg) (= "token" (get-in result [:result :kind])))
                          {:result {:session-id (:session-id params)}}
                          {:error {:code -32000 :message "Initial acquisition failed"}}))
                      "session.detach" {:result {:success true}}
                      "runtime.shutdown" {:result {}}
                      {:error {:code -32601 :message "Unexpected method"}})]
                (a/go response))))
      (with-redefs [host/open! (fn [_] {:in <-client :out ->client :configure-ui! (constantly nil) :close! (constantly nil)})]
        (with-open [c (client/open-client! {:mode :coding-agent :github {:use-logged-in-user? false}})]
          (f c conn opens)))
      (finally (rpc/disconnect conn)))))

(deftest registrations-rotate-and-fail-closed-through-framed-rpc
  (let [calls (atom [])
        config (fn [tag response]
                 {:github {:token-provider (fn [args] (swap! calls conj [tag args]) response)}})
        args {:host "example.invalid" :session-id "s" :reason :initial}
        config-a (config :first token)
        config-b (config :second (assoc token :token-type "Bearer"))
        previous (atom nil)]
    (with-runtime
      (fn [c conn opens]
        (let [last-id #(get-in (peek @opens) [1 :token-provider-registration-id])
              refresh #(a/<!! (acquire conn % "refresh"))]
          (testing "credential conflicts and explicit null never reach the runtime"
            (doseq [github [{:token "" :token-provider (constantly token)}
                            {:token-provider nil}
                            {:token-provider-registration-id "supplied-by-caller"}]]
              (is (thrown? clojure.lang.ExceptionInfo (client/open-session! c {:github github})))
              (is (thrown? clojure.lang.ExceptionInfo (client/resume-session! c "s" {:github github}))))
            (is (empty? @opens)))
          (testing "create and detach"
            (with-open [_ (client/open-session! c (assoc config-a :session-id "s"))]
              (is (= @calls [[:first args]]))
              (reset! previous (last-id))
              (is (= (refresh (last-id)) {:result (assoc token :kind "token")})))
            (is (some? (:error (refresh (last-id))))))
          (testing "cancelled resume rejects the open and revokes its pending registration"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Initial acquisition failed"
                                  (client/resume-session! c "s" (config :cancel {:kind :cancelled}))))
            (is (some? (:error (refresh (last-id))))))
          (testing "successful resume uses a new registration; no previous provider is inherited"
            (with-open [_ (client/resume-session! c "s" config-b)]
              (is (= (refresh (last-id)) {:result (assoc token :kind "token" :token-type "Bearer")}))
              (is (= (peek @calls) [:second (assoc args :reason :refresh)])))
            (let [ids (mapv #(get-in % [1 :token-provider-registration-id]) @opens)]
              (is (and (every? parse-uuid ids) (apply distinct? ids)))
              (is (every? #(= #{:token-provider-registration-id} (set (keys (second %)))) @opens)))
            (with-open [_ (client/resume-session! c "s" {})]
              (is (nil? (last-id))))))))
    (testing "a new client must receive a fresh provider even for the same session ID"
      (with-runtime
        (fn [c conn opens]
          (with-open [_ (client/resume-session! c "s" (config :cold token))]
            (let [id (get-in (peek @opens) [1 :token-provider-registration-id])]
              (is (= (peek @calls) [:cold args]))
              (is (some? (:error (a/<!! (acquire conn @previous "refresh")))))
              (is (= (a/<!! (acquire conn id "refresh")) {:result (assoc token :kind "token")})))))))))

(deftest detach-interrupts-a-running-refresh-and-fences-its-result
  (with-runtime
    (fn [c conn opens]
      (let [entered (promise)
            interrupted (promise)
            release (promise)
            provider (fn [{:keys [reason]}]
                       (when (= reason :refresh)
                         (deliver entered true)
                         (try @release
                              (catch InterruptedException _ (deliver interrupted true))))
                       token)]
        (with-open [s (client/open-session! c {:github {:token-provider provider}})]
          (let [id (get-in (peek @opens) [1 :token-provider-registration-id])
                pending (acquire conn id "refresh")]
            (try
              (is (= true (deref entered 5000 ::timeout)))
              (client/close-session! s)
              (is (= true (deref interrupted 5000 ::timeout)))
              (let [{:keys [result error]} (a/<!! pending)]
                (is (or (= result {:kind "cancelled"})
                        (and error (not= "Request timeout" (:message error))))))
              (finally (deliver release true)))))))))

(defn -main [_endpoint _prompt]
  (try
    (let [id "credential-cancel-check"
          root (System/getProperty "user.home")
          seen (atom [])]
      (with-open [c (client/open-client! {:mode :empty :base-directory root :github {:use-logged-in-user? false}})]
        (let [status (client/await!! (client/status c))
              outcome (try
                        (with-open [_ (client/open-session!
                                       c {:session-id id :working-directory root :tools {:available-tools []}
                                          :github {:token-provider (fn [args]
                                                                     (swap! seen conj args)
                                                                     {:kind :cancelled})}})]
                          :accepted)
                        (catch clojure.lang.ExceptionInfo e (get-in (ex-data e) [:error :code])))]
          (spit "result.edn" (pr-str {:runtime (select-keys status [:version :protocol-version])
                                      :outcome outcome :calls @seen})))))
    (finally (shutdown-agents))))

(deftest ^:native native-initial-cancellation-does-not-fall-back-to-ambient-authentication
  (let [{:keys [event requests]} (fixture/exercise-turn!
                                  ["-m" "github.copilot-sdk.credentials-test"]
                                  (fn [_] (throw (ex-info "A cancelled acquisition must not call a model" {}))))]
    (is (= requests []))
    (is (= event {:runtime {:version "1.0.114" :protocol-version 4} :outcome -32603
                  :calls [{:host "https://github.com" :reason :initial :session-id "credential-cancel-check"}]}))))
