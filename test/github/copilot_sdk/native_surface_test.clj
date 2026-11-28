(ns github.copilot-sdk.native-surface-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [copilot.sdk]
            [copilot.sdk.instrument]))

(def ^:private path "resources/copilot/sdk/api_surface.edn")

(def ^:private public-namespaces
  '[copilot.sdk copilot.sdk.helpers copilot.sdk.instrument copilot.sdk.specs copilot.sdk.tool-set])

(defn- surface []
  {:vars (into (sorted-map)
               (for [n public-namespaces [_ v] (ns-publics n)
                     :let [{:keys [macro arglists]} (meta v)
                           sym (symbol v)
                           contract (s/get-spec sym)]]
                 [sym (cond-> {:kind (cond macro :macro arglists :fn :else :value)}
                        arglists (assoc :arglists arglists)
                        contract (assoc :fdef (s/form contract)))]))
   :spec-keys (sort (filter #(and (keyword? %) (= "copilot.sdk.specs" (namespace %)))
                            (keys (s/registry))))})

(deftest native-public-contract
  (let [actual (surface)]
    (is (= public-namespaces (get-in (edn/read-string (slurp "deps.edn"))
                                     [:aliases :codox :exec-args :namespaces])))
    (is (= actual (edn/read-string (slurp path))))
    (is (every? (fn [{:keys [kind fdef]}] (or (not= :fn kind) fdef)) (vals (:vars actual))))))

(defn -main [& args]
  (when-not (= ["--update"] args)
    (throw (ex-info "Use --update to replace the 2.x API snapshot" {})))
  (io/make-parents path)
  (with-open [out (io/writer path)]
    (binding [*out* out] (pp/pprint (surface)))))
