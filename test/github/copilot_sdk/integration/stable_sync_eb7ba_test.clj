(ns github.copilot-sdk.integration.stable-sync-eb7ba-test
  "Stable upstream parity after the 4472fcb oracle."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private stable-delta-resource
  "resources/stable_upstream_delta_eb7ba.edn")

(defn- section
  [text start end]
  (let [start-index (str/index-of text start)
        end-index (when start-index
                    (str/index-of text end (+ start-index (count start))))]
    (when (and start-index end-index)
      (subs text start-index end-index))))

(deftest stable-delta-inventory-is-complete-and-internally-consistent
  (let [resource (io/resource stable-delta-resource)]
    (is (some? resource) "The post-4472fcb parity oracle must be committed")
    (when resource
      (let [report (-> resource slurp edn/read-string)
            source-symbols (:source-symbols report)
            classifications (:commit-classifications report)
            classification (first classifications)]
        (is (= "4472fcb9ad342b02aae14ccc3cf1c8083603863e"
               (get-in report [:upstream :base-commit])))
        (is (= "eb7ba2411171f5e1fea9d38df01b436acdfb7271"
               (get-in report [:upstream :target-commit])))
        (is (= :post-v1.0.11-unreleased
               (get-in report [:upstream :target-release-status])))
        (is (= "@github/copilot-linux-x64"
               (get-in report [:upstream :schema-package])))
        (is (= "1.0.80"
               (get-in report [:upstream :schema-version])))
        (is (= {:package "@github/copilot"
                :version "^1.0.80"}
               (get-in report [:upstream :node-runtime-dependency])))
        (is (= (slurp ".copilot-schema-version")
               (str (get-in report [:upstream :schema-version]) "\n")))
        (is (= #{} (:stable-delta-ids report)))
        (is (= [] (:stable-deltas report)))
        (is (empty? (:unclassified-stable report)))
        (is (= #{"nodejs/docs/factories.md"
                 "nodejs/docs/factory-patterns.md"
                 "nodejs/test/factory.test.ts"}
               (set (get-in report [:public-surface-audit :changed-files]))))
        (is (empty? (get-in report [:public-surface-audit
                                    :stable-source-delta-files])))
        (is (empty? (get-in report [:public-surface-audit
                                    :generated-source-delta-files])))
        (is (= #{"eb7ba2411171f5e1fea9d38df01b436acdfb7271"}
               (set (map :commit classifications))))
        (is (= :experimental (:classification classification)))
        (is (= :ported (:status classification)))
        (is (= :factory-limits-guidance-experimental-surface
               (:reason classification)))
        (is (= "https://github.com/github/copilot-sdk/pull/2353"
               (:pr classification)))
        (is (= (set (keys source-symbols))
               (set (:evidence classification))))
        (is (every? (fn [[_ {:keys [file symbol]}]]
                      (and (string? file)
                           (or (str/starts-with? file "nodejs/docs/")
                               (str/starts-with? file "nodejs/test/"))
                           (string? symbol)
                           (not (str/blank? symbol))))
                    source-symbols))
        (is (every? #(contains? #{:stable :experimental :internal
                                  :generated-only :language-specific}
                                (:classification %))
                    classifications))))))

(deftest factory-guidance-does-not-invite-invented-limits
  (let [guide (slurp "doc/guides/agent-factories.md")
        api-reference (slurp "doc/reference/API.md")
        example-source (slurp "examples/agent_factories.clj")
        example-readme (slurp "examples/README.md")
        guide-opening (section guide "## A working example" "## Overview")
        api-opening (section api-reference "**Defining a factory**"
                             "`define-factory` is also exposed")
        example-definition (section example-source "(def review-factory"
                                    ";; Extension entry point")
        readme-section (section example-readme "## Example 22: Agent Factories"
                                "## Clojure vs JavaScript Comparison")]
    (doseq [[label content] [[:guide guide-opening]
                             [:api-reference api-opening]
                             [:example-source example-definition]
                             [:example-readme readme-section]]]
      (testing (name label)
        (is (some? content) "Expected section markers were not found")
        (when content
          (is (not (str/includes? content ":limits"))
              "Introductory guidance must not invent resource limits"))))
    (is (str/includes? guide "Resource limits are optional."))
    (is (str/includes? guide "Do not guess limits"))
    (is (str/includes? api-reference "Resource limits are optional."))
    (is (str/includes? example-readme "This example omits resource limits"))))
