(ns github.copilot-sdk.native-specs-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [copilot.sdk.specs :as specs]))

(deftest opaque-json-has-exact-member-names-and-json-leaves
  (is (s/valid? ::specs/json-object {"A_b/C" [nil false 0 42N 2.5M {"nested-key" ""}]}))
  (doseq [not-json [1/3 ##NaN ##Inf Float/NEGATIVE_INFINITY :keyword #{1} '("list")
                    {"bucket" [{:keyword "not a JSON member name"}]}]]
    (is (not (s/valid? ::specs/json-value not-json)))))

(deftest structured-tool-result-checks-binary-items-and-optional-fields
  (let [result {:text-result-for-llm "" :result-type :success}
        binary {:type "image" :data "AA==" :mime-type "image/png"}]
    (is (s/valid? ::specs/tool-result-object
                  (assoc result :binary-results-for-llm [binary]
                         :tool-telemetry {"Host" {"key_1" nil}} :tool-references [])))
    (doseq [bad [(assoc result :error nil)
                 (assoc result :tool-telemetry {"Host" ["not an object"]})
                 (assoc result :binary-results-for-llm [(dissoc binary :mime-type)])
                 (assoc result :binary-results-for-llm [(assoc binary :type "video")])
                 (assoc result :binary-results-for-llm [(assoc binary :data "AB==")])]]
      (is (not (s/valid? ::specs/tool-result-object bad))))))

(deftest virtual-filesystem-does-not-use-host-path-conventions
  (let [config {:initial-cwd "V:\\work" :session-state-path "V:\\state" :conventions "windows"}]
    (is (s/valid? ::specs/session-fs (assoc config :capabilities {:read-file-range false})))
    (doseq [bad [(assoc config :capabilities nil) (assoc config :capabilities {:read-file-range nil})
                 (assoc config :capabilities {:unknown true}) (assoc config :initial-cwd " ")
                 (assoc config :cli-path "/bin/unused")]]
      (is (not (s/valid? ::specs/session-fs bad))))))

(deftest provider-validates-the-positional-interface-before-registration
  (let [unused (fn [& _])
        p (zipmap [:read-file :write-file :append-file :exists :stat :mkdir
                   :readdir :readdir-with-types :rm :rename] (repeat unused))
        sql {:query (fn [_qtype _sql _bindings]) :exists (fn [])}]
    (is (s/valid? ::specs/session-fs-provider (assoc p :sqlite sql :read-file-range (fn [_p _off _len]))))
    (doseq [bad [(dissoc p :rename) (assoc p :write-file (fn [_params]))
                 (assoc p :read-file-range (fn [_p])) (assoc p :sqlite-query unused)
                 (assoc p :sqlite nil) (assoc p :sqlite (assoc sql :transaction nil))]]
      (is (not (s/valid? ::specs/session-fs-provider bad))))))
