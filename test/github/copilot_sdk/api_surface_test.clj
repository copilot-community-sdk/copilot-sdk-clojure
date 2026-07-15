(ns github.copilot-sdk.api-surface-test
  "Fails when the public API surface drifts from the checked-in snapshot.

   The snapshot at `resources/github/copilot_sdk/api_surface.edn` is the
   record of the public contract. Any change to the facade namespace's
   public vars (kind, or `:arglists` where present) or the curated spec keys
   must be accompanied by a deliberate snapshot regeneration
   (`bb api-surface:update`), which makes the contract change visible in review."
  (:require [clojure.data :as data]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [github.copilot-sdk.api-surface :as surface]))

(defn- format-var-drift [only-live only-snap]
  (let [added (vec (sort (keys (or only-live {}))))
        removed (vec (sort (keys (or only-snap {}))))
        changed (vec (sort (set/intersection (set added) (set removed))))
        added-only (vec (remove (set removed) added))
        removed-only (vec (remove (set added) removed))]
    (cond-> []
      (seq added-only) (conj (str "  added vars: " added-only))
      (seq removed-only) (conj (str "  removed vars: " removed-only))
      (seq changed) (conj (str "  changed vars (kind/arglists): " changed)))))

(deftest api-surface-matches-snapshot
  (let [snapshot (surface/read-snapshot)]
    (is (some? snapshot)
        (str "Missing API-surface snapshot. Generate it with "
             "`bb api-surface:update`."))
    (when snapshot
      (let [live (surface/current-surface)]
        (testing "public var surface"
          ;; Maps diff by key, so this precisely reports added/removed/
          ;; changed vars without positional noise.
          (let [[only-live only-snap _] (data/diff (:vars live)
                                                   (:vars snapshot))]
            (is (and (nil? only-live) (nil? only-snap))
                (str "\nPublic API surface drift detected in "
                     "`github.copilot-sdk`.\n"
                     "If this change is intentional, regenerate the "
                     "snapshot with `bb api-surface:update` and commit "
                     "the diff.\n"
                     (str/join "\n" (format-var-drift only-live only-snap))
                     "\n"))))
        (testing "curated spec key surface"
          ;; Sets, not vectors -- a single mid-list insertion must not
          ;; produce a positional-diff avalanche.
          (let [live-keys (set (:spec-keys live))
                snap-keys (set (:spec-keys snapshot))
                added (vec (sort (set/difference live-keys snap-keys)))
                removed (vec (sort (set/difference snap-keys live-keys)))]
            (is (= live-keys snap-keys)
                (str "\nSpec-key surface drift detected in "
                     "`github.copilot-sdk.specs`.\n"
                     "If this change is intentional, regenerate the "
                     "snapshot with `bb api-surface:update` and commit "
                     "the diff.\n"
                     "  added keys: " added "\n"
                     "  removed keys: " removed "\n"))))))))
