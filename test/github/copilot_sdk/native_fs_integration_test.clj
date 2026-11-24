(ns github.copilot-sdk.native-fs-integration-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.memory-fs-fixture :as memory-fs]
            [github.copilot-sdk.native-offline-fixture :as fixture])
  (:import [java.util UUID]))

(defn- record-calls [ops calls generation]
  (into {} (map (fn [[op f]]
                  [op (fn [& args]
                        (swap! calls conj [generation op (first args)])
                        (apply f args))]) ops)))

(defn -main [base-url _unused-prompt]
  (try
    (let [id (str (UUID/randomUUID))
          state-root (str "/sdk-memory-" id)
          tree (atom {"/" nil}) calls (atom []) generations (atom []) captured (atom [])
          factory (fn [s]
                    (swap! captured conj s)
                    (record-calls (memory-fs/provider tree) calls (count (swap! generations conj (sdk/session-id s)))))
          opts {:working-directory "/" :model "fixture/chat" :tools {:available-tools []}
                :inference {:providers [{:name "fixture" :provider-type :openai :base-url base-url}]
                            :models [{:provider "fixture" :id "chat" :model-id "gpt-4o"}]}
                :github {:capi {:enabled? false}} :state {:create-session-fs-provider factory}}
          client-opts {:mode :empty :github {:use-logged-in-user? false}
                       :session-fs {:initial-cwd "/" :session-state-path state-root :conventions "posix"}}
          created (sdk/with-client [client client-opts]
                    (sdk/with-session [s client (assoc opts :session-id id :name "in-memory session")]
                      {:id (sdk/session-id s) :bound? (identical? (peek @captured) s)
                       :workspace (get @tree (str state-root "/workspace.yaml"))}))
          journal (str state-root "/events.jsonl")
          persisted (mapv json/read-str (str/split-lines (get @tree journal)))
          resumed (sdk/with-client [client client-opts]
                    (let [without-registry (dissoc opts :model :inference)
                          rejected (try
                                     (with-open [_ (sdk/resume-session!! client id without-registry)] nil)
                                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
                      ;; Restore registry data, not a new selection: the saved session owns the model ID.
                      (with-open [s (sdk/resume-session!! client id (dissoc opts :model))]
                        {:id (sdk/session-id s) :bound? (identical? (peek @captured) s)
                         :without-registry rejected :calls @calls
                         :history (mapv #(select-keys % ["type" "id"]) (sdk/await!! (sdk/history s :raw)))})))]
      (spit "result.edn" (pr-str {:id id :journal journal :created created :resumed resumed
                                  :persisted (mapv #(select-keys % ["type" "id"]) persisted)
                                  :host-state-root? (.exists (io/file state-root))
                                  :generations @generations})))
    (finally (shutdown-agents))))

(deftest ^:native named-session-resumes-from-provider-without-a-turn
  (let [{:keys [event requests]} (fixture/exercise-turn!
                                  ["-m" "github.copilot-sdk.native-fs-integration-test"]
                                  (fn [_] (throw (ex-info "Filesystem resume must not call a model" {}))))
        {:keys [id journal created resumed persisted generations host-state-root?]} event]
    (is (= requests []))
    (is (false? host-state-root?))
    (is (= generations [id id id]))
    (is (re-find #"fixture/chat.*no longer registered" (or (:without-registry resumed) "")))
    (is (= [(:id created) (:id resumed)] [id id]))
    (is (and (:bound? created) (:bound? resumed)))
    (is (str/includes? (:workspace created) id))
    (is (some #(= % [1 :append-file journal]) (:calls resumed)))
    (is (some #(= % [3 :read-file journal]) (:calls resumed)))
    (let [start (first (filter #(= "session.start" (get % "type")) persisted))]
      (is (some? start))
      (is (some #{start} (:history resumed)))
      (is (some #(= "session.resume" (get % "type")) (:history resumed))))))
