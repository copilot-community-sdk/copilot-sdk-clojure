(ns github.copilot-sdk.util-test
  (:require [camel-snake-kebab.core :as csk]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.util :as util]))

(defn- names-of-length
  [alphabet length]
  (if (zero? length)
    [""]
    (for [prefix (names-of-length alphabet (dec length))
          character alphabet]
      (str prefix character))))

(deftest case-conversion-fast-path-preserves-library-results
  (let [key-cases (map keyword
                       (mapcat #(names-of-length [\a \b \z \0 \1 \9 \-] %)
                               (range 1 5)))]
    (is (every?
         (fn [key]
           (= (csk/->kebab-case-keyword key)
              (first (keys (util/wire->clj {key true})))))
         key-cases))
    (is (every?
         (fn [key]
           (= (csk/->camelCaseKeyword key)
              (first (keys (util/clj->wire {key true})))))
         key-cases))))

(deftest simple-keywords-bypass-case-conversion
  (testing "inbound conversion delegates only keys that can change"
    (let [original csk/->kebab-case-keyword
          converted (atom [])]
      (with-redefs [csk/->kebab-case-keyword
                    (fn [key]
                      (swap! converted conj key)
                      (original key))]
        (is (= {:jsonrpc "2.0"
                :method "session.event"
                :params {:session-id "s"
                         :sha-256 "hash"
                         :event {:parent-id nil
                                 :data {:content "ok"}}}}
               (util/wire->clj
                {:jsonrpc "2.0"
                 :method "session.event"
                 :params {:sessionId "s"
                          :sha256 "hash"
                          :event {:parent_id nil
                                  :data {:content "ok"}}}})))
        (is (= #{:sessionId :sha256 :parent_id} (set @converted))))))

  (testing "outbound conversion delegates only keys that can change"
    (let [original csk/->camelCaseKeyword
          converted (atom [])]
      (with-redefs [csk/->camelCaseKeyword
                    (fn [key]
                      (swap! converted conj key)
                      (original key))]
        (is (= {:jsonrpc "2.0"
                :method "session.send"
                :params {:sessionId "s"
                         :sha256 "hash"
                         :prompt "hi"}}
               (util/clj->wire
                {:jsonrpc "2.0"
                 :method "session.send"
                 :params {:session-id "s"
                          :sha256 "hash"
                          :prompt "hi"}})))
        (is (= [:session-id] @converted)))))

  (testing "namespaced and punctuated keys preserve library semantics"
    (doseq [key [:my.app/user_id :already.kebab/key-name :question? :dot.name
                 :-leading :trailing- :repeated--separator :utf8 :sha256
                 :v1beta2 :line10col5]]
      (is (= (csk/->kebab-case-keyword key)
             (first (keys (util/wire->clj {key true})))))
      (is (= (csk/->camelCaseKeyword key)
             (first (keys (util/clj->wire {key true}))))))))
