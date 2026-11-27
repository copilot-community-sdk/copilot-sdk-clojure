(ns tool-integration
  "Implement a small catalog tool and distinguish lookup failure from permission denial."
  (:require [basic-chat]
            [clojure.string :as str]
            [copilot.sdk :as copilot]))

(def ^:private catalog
  {"clojure" "Clojure is a Lisp that runs on the JVM."
   "python" "Python is a general-purpose language."
   "rust" "Rust checks ownership at compile time."
   "javascript" "JavaScript runs in browsers and other hosts."
   "haskell" "Haskell is a purely functional language."})

(defn- lookup [language]
  (if-let [description (get catalog (str/lower-case language))]
    {:result-type :success :text-result-for-llm description}
    {:result-type :failure :text-result-for-llm "No entry in the example catalog."}))

(defn- run-lookup [client options queries denied?]
  (let [calls (atom [])
        refusals (atom [])
        tool {:name "lookup_language" :description "Look up a language in the demo catalog."
              :parameters {:type "object" :properties {:language {:type "string"}} :required ["language"]
                           :additionalProperties false}
              :handler (fn [{:keys [language]} _]
                         (let [result (lookup language)]
                           (swap! calls conj language)
                           (println "lookup_language:" language "=>" (:result-type result))
                           result))}
        permit (fn [{:keys [kind tool-name args]} _]
                 (if (and (= kind "custom-tool") (= tool-name (:name tool)) (= #{:language} (set (keys args)))
                          (string? (:language args)) (not= "private" (:language args)))
                   {:kind :approve-once}
                   (do (swap! refusals conj args)
                       (println "Permission denied:" tool-name)
                       {:kind :reject :feedback "The private catalog is not available."})))]
    (copilot/with-session [session client (assoc options :tools {:available-tools ["custom:lookup_language"] :definitions [tool]}
                                                 :permissions {:on-request permit})]
      (mapv (fn [[language prompt]]
              (let [before (count @calls)
                    answer (basic-chat/ask session prompt)]
                (when-not (if denied?
                            (and (some #{{:language "private"}} @refusals) (= before (count @calls)))
                            (some #(= (str/lower-case language) (str/lower-case %)) (subvec @calls before)))
                  (throw (ex-info "The requested lookup or permission decision did not occur" {:language language})))
                answer))
            queries))))

(defn run [client options & languages]
  (run-lookup client options
              (mapv (fn [language] [language (str "Call lookup_language for " language
                                                  ". Report its description, or say it was not found. Do not retry.")])
                    languages)
              false))

(defn run-denied [client options prompt]
  (first (run-lookup client options [["private" prompt]] true)))
