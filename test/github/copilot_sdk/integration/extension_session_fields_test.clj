(ns github.copilot-sdk.integration.extension-session-fields-test
  "Stable extension-host session configuration parity with the Node SDK."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.integration.support
             :refer [*mock-server* *test-client* with-mock-server]]
            [github.copilot-sdk.mock-server :as mock]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util]))

(use-fixtures :each with-mock-server)

(defn- capture-session-request
  [scope config]
  (let [seen (atom nil)
        seed (when (= :resume scope)
               (sdk/create-session *test-client* {}))
        method (case scope
                 :create "session.create"
                 :resume "session.resume")]
    (mock/set-request-hook!
     *mock-server*
     (fn [request-method params]
       (when (= method request-method)
         (reset! seen params))))
    (try
      (case scope
        :create (sdk/create-session *test-client* config)
        :resume (sdk/resume-session *test-client* (sdk/session-id seed) config))
      {:params @seen}
      (catch Throwable error
        {:error error :params @seen}))))

(defn- assert-invalid-before-rpc
  [scope config]
  (let [{:keys [error params]} (capture-session-request scope config)]
    (is (instance? clojure.lang.ExceptionInfo error))
    (is (nil? params))))

(deftest stable-extension-fields-create-resume-wire-matrix
  (doseq [scope [:create :resume]]
    (testing (str (name scope) " omits all extension fields by default")
      (let [{:keys [error params]} (capture-session-request scope {})]
        (is (nil? error))
        (is (not (contains? params :requestExtensions)))
        (is (not (contains? params :extensionSdkPath)))
        (is (not (contains? params :extensionInfo)))))

    (testing (str (name scope) " preserves explicit false")
      (let [{:keys [error params]}
            (capture-session-request scope {:request-extensions? false})]
        (is (nil? error))
        (is (false? (:requestExtensions params)))
        (is (not (contains? params :requestExtensions?)))))

    (testing (str (name scope) " serializes exact extension field names and identity shape")
      (let [{:keys [error params]}
            (capture-session-request
             scope
             {:request-extensions? true
              :extension-sdk-path "missing-sdk-directory"
              :extension-info {:source "github-app"
                               :name "counter-provider"}})]
        (is (nil? error))
        (is (= {:requestExtensions true
                :extensionSdkPath "missing-sdk-directory"
                :extensionInfo {:source "github-app"
                                :name "counter-provider"}}
               (select-keys params [:requestExtensions
                                    :extensionSdkPath
                                    :extensionInfo])))
        (is (not (contains? params :requestExtensions?)))
        (is (not-any? #(contains? params %)
                      [:request-extensions?
                       :extension-sdk-path
                       :extension-info]))))

    (testing (str (name scope) " rejects nil instead of emitting JSON null")
      (doseq [config [{:request-extensions? nil}
                      {:extension-sdk-path nil}
                      {:extension-info nil}]]
        (assert-invalid-before-rpc scope config)))

    (testing (str (name scope) " rejects invalid extension field values")
      (doseq [config [{:request-extensions? "true"}
                      {:extension-sdk-path :not-a-path}
                      {:extension-info {}}
                      {:extension-info {:source "github-app"}}
                      {:extension-info {:name "counter-provider"}}
                      {:extension-info {:source :github-app
                                        :name "counter-provider"}}
                      {:extension-info {:source "github-app"
                                        :name 42}}
                      {:extension-info {:source "github-app"
                                        :name "counter-provider"
                                        :unknown true}}]]
        (assert-invalid-before-rpc scope config)))

    (testing (str (name scope) " still rejects unknown top-level fields")
      (assert-invalid-before-rpc scope {:unknown-extension-option true}))))

(deftest join-extension-fields-contract
  (let [config {:request-extensions? false
                :extension-info {:source "github-app"
                                 :name "counter-provider"}}
        wire (util/clj->wire
              (#'client/build-resume-session-params "session-1" config))]
    (is (s/valid? ::specs/join-session-config config))
    (is (false? (:requestExtensions wire)))
    (is (= {:source "github-app" :name "counter-provider"}
           (:extensionInfo wire)))
    (is (not (contains? wire :extensionSdkPath))))

  (testing "join excludes the create/resume-only SDK override"
    (is (not (s/valid? ::specs/join-session-config
                       {:extension-sdk-path "missing-sdk-directory"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid join session config"
         (sdk/join-session {:extension-sdk-path "missing-sdk-directory"}))))

  (testing "join applies the same nil and identity validation"
    (doseq [config [{:request-extensions? nil}
                    {:extension-info nil}
                    {:extension-info {:source "github-app"}}
                    {:extension-info {:source "github-app" :name false}}
                    {:extension-info {:source "github-app"
                                      :name "counter-provider"
                                      :unknown true}}]]
      (is (not (s/valid? ::specs/join-session-config config)))))

  (testing "join rejects nested identity unknown keys before reading SESSION_ID"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Invalid join session config"
         (sdk/join-session {:extension-info {:source "github-app"
                                             :name "counter-provider"
                                             :unknown true}})))))

(deftest join-environment-variable-request-contract
  (testing "requested names are join-only and non-blank"
    (is (s/valid? ::specs/join-session-config
                  {:requested-environment-variables ["MY_TOKEN" "a/b"]}))
    (doseq [config [{:requested-environment-variables nil}
                    {:requested-environment-variables [""]}
                    {:requested-environment-variables [" "]}
                    {:requested-environment-variables [:MY_TOKEN]}]]
      (is (not (s/valid? ::specs/join-session-config config))))
    (is (not (s/valid? ::specs/resume-session-config
                       {:requested-environment-variables ["MY_TOKEN"]}))))

  (testing "absent and empty requests are omitted from session.resume"
    (doseq [config [{} {:requested-environment-variables []}]]
      (let [wire (util/clj->wire
                  (#'client/build-resume-session-params "session-1" config))]
        (is (not (contains? wire :requestedEnvironmentVariables))))))

  (testing "non-empty requests use the exact extension wire key"
    (let [wire (util/clj->wire
                (#'client/build-resume-session-params
                 "session-1"
                 {:requested-environment-variables ["MY_TOKEN" "a/b"]}))]
      (is (= ["MY_TOKEN" "a/b"] (:requestedEnvironmentVariables wire)))
      (is (not (contains? wire :requested-environment-variables))))))

(deftest granted-environment-variable-keys-remain-opaque
  (let [normalize-response (var-get
                            (ns-resolve 'github.copilot-sdk.protocol
                                        'normalize-response))
        normalized
        (normalize-response
         "session.resume"
         {:jsonrpc "2.0"
          :id 1
          :result
          {:grantedEnvironmentVariables
           {:MY_VAR "upper"
            :myVar "lower"
            :a/b "slash"
            :UNREQUESTED "drop"}}})]
    (is (= {:MY_VAR "upper"
            :myVar "lower"
            :a/b "slash"
            :UNREQUESTED "drop"}
           (get-in normalized [:result :granted-environment-variables])))))

(deftest granted-environment-variables-are-projected-from-requested-names
  (let [projection-var
        (ns-resolve 'github.copilot-sdk.client
                    'project-granted-environment-variables)]
    (is (some? projection-var))
    (when projection-var
      (let [project-grants (var-get projection-var)
            raw {:MY_VAR "upper"
                 :myVar "lower"
                 :a/b "slash"
                 :UNREQUESTED "drop"}]
        (is (= {"MY_VAR" "upper"
                "myVar" "lower"
                "a/b" "slash"}
               (project-grants ["MY_VAR" "myVar" "a/b" "MISSING"] raw)))
        (is (= {} (project-grants [] raw)))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"invalid environment variable grants"
             (project-grants ["MY_VAR"] [])))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"non-string environment variable grant"
             (project-grants ["MY_VAR"] {:MY_VAR 42})))))))

(deftest join-session-returns-only-requested-environment-variable-grants
  (let [foreground-session-id-var
        (ns-resolve 'github.copilot-sdk.client 'foreground-session-id)
        resume-session-result-var
        (ns-resolve 'github.copilot-sdk.client 'resume-session-result*)
        fake-client {:client :fake}
        fake-session {:session :fake}
        observed-config (atom nil)]
    (is (some? foreground-session-id-var))
    (is (some? resume-session-result-var))
    (when (and foreground-session-id-var resume-session-result-var)
      (with-redefs-fn
        {foreground-session-id-var (constantly "foreground-session")
         #'client/client (fn [_] fake-client)
         resume-session-result-var
         (fn [c session-id config]
           (is (= fake-client c))
           (is (= "foreground-session" session-id))
           (reset! observed-config config)
           {:session fake-session
            :result {:granted-environment-variables
                     {:MY_TOKEN "secret"
                      :UNREQUESTED "drop"}}})}
        (fn []
          (is (= {:client fake-client
                  :session fake-session
                  :granted-environment-variables {"MY_TOKEN" "secret"}}
                 (sdk/join-session
                  {:requested-environment-variables ["MY_TOKEN"]})))
          (is (= sdk/default-join-session-permission-handler
                 (:on-permission-request @observed-config)))
          (is (true? (:disable-resume? @observed-config))))))))

(deftest join-session-omits-grants-when-no-environment-variables-are-requested
  (let [foreground-session-id-var
        (ns-resolve 'github.copilot-sdk.client 'foreground-session-id)
        resume-session-result-var
        (ns-resolve 'github.copilot-sdk.client 'resume-session-result*)
        fake-client {:client :fake}
        fake-session {:session :fake}]
    (is (some? foreground-session-id-var))
    (is (some? resume-session-result-var))
    (when (and foreground-session-id-var resume-session-result-var)
      (with-redefs-fn
        {foreground-session-id-var (constantly "foreground-session")
         #'client/client (fn [_] fake-client)
         resume-session-result-var
         (fn [_ _ _]
           {:session fake-session
            :result {:granted-environment-variables {:MY_TOKEN "secret"}}})}
        (fn []
          (is (= {:client fake-client :session fake-session}
                 (sdk/join-session {}))))))))

(deftest stable-extension-fields-work-under-instrumentation
  (let [instrument-all! (requiring-resolve 'github.copilot-sdk.instrument/instrument-all!)
        unstrument-all! (requiring-resolve 'github.copilot-sdk.instrument/unstrument-all!)
        config {:request-extensions? false
                :extension-sdk-path "missing-extension-sdk"
                :extension-info {:source "github-app" :name "instrumented"}}]
    (instrument-all!)
    (try
      (doseq [scope [:create :resume]]
        (let [{:keys [error params]} (capture-session-request scope config)]
          (is (nil? error))
          (is (false? (:requestExtensions params)))
          (is (= "missing-extension-sdk" (:extensionSdkPath params)))
          (is (= {:source "github-app" :name "instrumented"}
                 (:extensionInfo params)))))
      (finally
        (unstrument-all!)))))
