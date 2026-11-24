(ns github.copilot-sdk.util-test
  (:require [clojure.test :refer [deftest is]]
            [github.copilot-sdk.util :as util]))

(deftest protocol-keys-and-opaque-keys-have-distinct-rules
  (let [metadata {"tenant/id" 1 "account/id" 2 "Mixed_case" [{"child-key" false}]}
        idiom {:tool-call-id "t" :metadata metadata}
        wire {:toolCallId "t" :metadata metadata}]
    (is (= metadata (util/opaque-json->wire {:tenant/id 1 "account/id" 2 :Mixed_case [{:child-key false}]})))
    (is (= wire (util/clj->wire idiom)))
    (is (= idiom (util/wire->clj wire)))
    (is (= {:sha-256 "hash" :parent-id nil} (util/wire->clj {:sha256 "hash" :parent_id nil})))))

(deftest opaque-json-rejects-ambiguous-or-unserializable-values
  (doseq [value [{:ns/name nil "ns/name" false} {"nested" [{:key 1 "key" 2}]}
                 {1 true} {:key :keyword-value} #{1} [##NaN] 1/3 (Object.)
                 (lazy-seq (throw (AssertionError. "A JSON encoder must not realize arbitrary sequences")))]]
    (is (thrown? clojure.lang.ExceptionInfo (util/opaque-json->wire value)))))

(deftest opaque-json-keeps-nested-empty-and-null-values
  (is (nil? (util/opaque-json->wire nil)))
  (is (= [nil [] {} false] (util/opaque-json->wire [nil [] {} false])))
  (let [depth 10000
        nested (reduce (fn [v _] {:member [v]}) "leaf" (range depth))]
    (is (= "leaf" (loop [v (util/opaque-json->wire nested) remaining depth]
                    (if (zero? remaining)
                      v
                      (recur (get-in v ["member" 0]) (dec remaining))))))))
