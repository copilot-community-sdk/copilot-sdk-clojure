(ns offline-examples
  "Run the examples with the repository's local model and isolated application JVM."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [copilot.sdk :as copilot]
            [github.copilot-sdk.command-example-fixture :as command-host]
            [github.copilot-sdk.config-example-fixture :as config-fixture]
            [github.copilot-sdk.interaction-example-fixture :as interaction]
            [github.copilot-sdk.lifecycle-example-fixture :as lifecycle]
            [github.copilot-sdk.native-offline-fixture :as fixture]
            [live-examples :as live]
            [session-filesystem]
            [structured-output]))

(defn- reply [start & fragments]
  [(into [{:role "assistant" :content start}] (map #(hash-map :content %) fragments)) "stop"])

(def ^:private rainbow "Sunlight bends and reflects inside raindrops. The light spreads into different colors.")
(def ^:private rainbow-follow-up "Look for sunlit raindrops in front of you with the sun behind you.")

(defn- chat-model [requests]
  (case (count requests)
    1 (reply rainbow)
    2 (let [messages (:messages (peek requests))]
        (when-not (and (some #(and (= "assistant" (:role %)) (= rainbow (:content %))) messages)
                       (str/includes? (:content (peek messages)) (last (get live/demos "basic-chat"))))
          (throw (ex-info "The chat follow-up lost its conversation history" {})))
        (reply rainbow-follow-up))))

(defn- independent-model [requests]
  (let [messages (:messages (peek requests))
        question (last (str/split-lines (:content (peek messages))))]
    (when (some #(= "assistant" (:role %)) messages)
      (throw (ex-info "Independent queries shared a conversation" {})))
    (reply (case question
             "What is 2+2?" "4."
             ("What is the capital of Japan?" "What is the capital of Japan? Answer in one sentence.") "Tokyo."
             "Who wrote Hamlet?" "William Shakespeare."))))

(defn- recall-model [requests]
  (let [messages (:messages (peek requests))]
    (case (count requests)
      1 (when-not (str/includes? (:content (peek messages)) "PINEAPPLE")
          (throw (ex-info "Missing original code word" {})))
      2 (when-not (and (some #(and (= "assistant" (:role %)) (= "PINEAPPLE" (:content %))) messages)
                       (not (str/includes? (:content (peek messages)) "PINEAPPLE")))
          (throw (ex-info "Resume did not restore the first turn" {}))))
    [[{:role "assistant" :content "PINEAPPLE"}] "stop"]))

(def ^:private extracted-note {"summary" "The beta opens Friday."
                               "actions" ["Kim will send invitations." "Lee will update the guide."]})

(defn- note-model [requests]
  (let [{:keys [type json_schema]} (:response_format (peek requests))]
    (when-not (and (= type "json_schema") (true? (:strict json_schema))
                   (= (:schema json_schema) structured-output/note-schema))
      (throw (ex-info "The model did not receive the note schema" {})))
    [[{:role "assistant" :content (json/write-str extracted-note)}] "stop"]))

(defn- answer-model [requests]
  (let [schema (get-in (peek requests) [:response_format :json_schema :schema])
        confidence (get-in schema [:properties :confidence])]
    (when-not (and (= (:required schema) ["answer" "confidence"])
                   (= (:type confidence) "number")
                   (not-any? #(contains? confidence %) [:minimum :maximum]))
      (throw (ex-info "Expected numeric confidence without provider-side minimum or maximum" {})))
    [[{:role "assistant" :content "{\"answer\":\"Paris\",\"confidence\":0.95}"}] "stop"]))

(defn- attachment-model [requests]
  (let [messages (json/write-str (:messages (peek requests)))]
    (when-not (and (str/includes? messages "The community garden grows lemon thyme.")
                   (not (str/includes? messages "The next workday is Saturday.")))
      (throw (ex-info "Expected only the selected line of the attachment" {}))))
  (reply "Lemon thyme."))

(defn- manifest-model [requests]
  (let [messages (:messages (peek requests))]
    (case (count requests)
      1 (do (when-not (str/includes? (:content (peek messages)) "sample-deps-")
              (throw (ex-info "The model did not receive the file path" {})))
            [[{:role "assistant" :tool_calls [{:index 0 :id "read-manifest" :type "function"
                                               :function {:name "read_attachment" :arguments "{}"}}]}] "tool_calls"])
      2 (do (when-not (some #(and (= "tool" (:role %)) (= "read-manifest" (:tool_call_id %))
                                  (str/includes? (:content %) "org.clojure/data.json")) messages)
              (throw (ex-info "The tool did not return the attached manifest" {})))
            [[{:role "assistant" :content "org.clojure/data.json"}] "stop"]))))

(defn- switched-model [requests]
  (when-not (= (mapv :model requests) (subvec ["probe-deployment" "second-deployment"] 0 (count requests)))
    (throw (ex-info "The runtime did not switch wire models" {})))
  (when (and (= 2 (count requests))
             (not (some #(and (= "assistant" (:role %)) (= "Maple Lantern" (:content %)))
                        (:messages (peek requests)))))
    (throw (ex-info "Switching models lost the first turn" {})))
  [[{:role "assistant" :content "Maple Lantern"}] "stop"])

(defn- reasoning-model [requests]
  (when-not (= "low" (:reasoning_effort (peek requests)))
    (throw (ex-info "The model did not receive the requested reasoning effort"
                    {:effort (:reasoning_effort (peek requests))})))
  (reply "30. The differences are 4, 6, 8, then 10."))

(defn- lookup-model [languages requests]
  (let [n (count requests)
        language (nth languages (quot (dec n) 2))
        id (str "lookup-" language)
        message (peek (:messages (peek requests)))]
    (if (odd? n)
      (do (when-not (str/includes? (:content message) language)
            (throw (ex-info "The model did not receive the lookup request" {:language language})))
          [[{:role "assistant" :tool_calls [{:index 0 :id id :type "function"
                                             :function {:name "lookup_language"
                                                        :arguments (json/write-str {:language (if (= "private" language)
                                                                                                "PRIVATE" language)})}}]}]
           "tool_calls"])
      (let [expected ({"clojure" "Lisp" "python" "general-purpose" "rust" "ownership"
                       "unlisted" "No entry" "private" "private catalog is not available"} language)]
        (when-not (and (= "tool" (:role message)) (= (:tool_call_id message) id) (str/includes? (:content message) expected))
          (throw (ex-info "Unexpected model-facing tool result" {:language language :content (:content message)})))
        (reply (if (= language "private") "The private catalog is not available." (:content message)))))))

(defn- stream-recipe [& fragments]
  (let [text (apply str fragments)]
    [(constantly (apply reply fragments)) 1 {:streamed text :final text}]))

(defn- no-model [_] (throw (ex-info "This example does not need a model" {})))

(def ^:private recipes
  (merge
   (sorted-map "basic-chat" [chat-model 2 rainbow-follow-up]
               "file-attachments" [manifest-model 2 "org.clojure/data.json"]
               "file-selection" [attachment-model 1 "Lemon thyme."]
               "lifecycle-hooks" lifecycle/recipe
               "metadata-api" [no-model 0 :found-and-deleted]
               "model-switch" [switched-model 2 "Maple Lantern"]
               "permission-policy" [(partial lookup-model ["private"]) 2 "The private catalog is not available."]
               "query" [independent-model 1 "Tokyo."]
               "query-multi" [independent-model 3 ["4." "Tokyo." "William Shakespeare."]]
               "query-streaming" (stream-recipe "An immutable value never changes. " "An update gives you a new value.")
               "query-async" (stream-recipe "Why do programmers prefer dark mode? " "Because light attracts bugs.")
               "reasoning-effort" [reasoning-model 1 "30. The differences are 4, 6, 8, then 10."]
               "session-events" (stream-recipe "A compass needle is a small magnet. " "It aligns with Earth's magnetic field. "
                                               "Its north end points toward magnetic north.")
               "session-filesystem" [no-model 0 {:restored? true :cleaned? true}]
               "session-resume" [recall-model 2 "PINEAPPLE"]
               "structured-answer" [answer-model 1 {"answer" "Paris" "confidence" 0.95M}]
               "structured-output" [note-model 1 extracted-note]
               "structured-output-primitive" [note-model 1 extracted-note]
               "tool-integration" [(partial lookup-model ["clojure" "python" "rust" "unlisted"]) 8
                                   ["Clojure is a Lisp that runs on the JVM." "Python is a general-purpose language."
                                    "Rust checks ownership at compile time." "No entry in the example catalog."]])
   interaction/recipes config-fixture/recipes))

(defn- exercise! [recipe]
  (println "\nOffline smoke:" recipe)
  (case recipe
    "commands" (command-host/run)
    "elicitation-url" (command-host/run-url)
    (let [[model request-count expected] (get recipes recipe)
          {:keys [event requests]}
          (fixture/exercise-turn! ["-m" "offline-examples" "--fixture-child" recipe] model)]
      (when-not (and (= event expected) (= request-count (count requests)))
        (throw (ex-info "Unexpected offline example result"
                        {:recipe recipe :expected expected :actual event :requests (count requests)})))))
  (println "PASS:" recipe))

(defn- run-child! [recipe base-url _]
  (let [options {:model "fixture/chat" :tools {:available-tools []} :events {:streaming? true}
                 :working-directory (System/getenv "HOME")
                 :github {:capi {:enabled? false}}
                 :inference {:providers [{:name "fixture" :provider-type :openai :base-url base-url}]
                             :models [{:provider "fixture" :id "chat" :model-id "gpt-4o"
                                       :wire-model "probe-deployment"}]}}
        options (cond-> options
                  (= recipe "model-switch") (update-in [:inference :models] conj {:provider "fixture" :id "other"
                                                                                  :model-id "gpt-4o" :wire-model "second-deployment"}))
        client-options (cond-> {:mode :empty :base-directory (System/getenv "HOME") :github {:use-logged-in-user? false}}
                         (#{"reasoning-effort" "model-switch"} recipe)
                         (assoc :models {:on-list (fn [] [{:id "fixture/chat" :name "Fixture" :capabilities {}
                                                           :supported-reasoning-efforts ["low"]}
                                                          {:id "fixture/other" :name "Second" :capabilities {}}])}))
        [run & prompts] (or (get live/demos recipe) (get live/named-demos recipe))
        result (case recipe
                 "session-filesystem" (session-filesystem/run options)
                 ("manual-tool-resume" "query") (apply run client-options options prompts)
                 (copilot/with-client [client client-options]
                   (apply (case recipe
                            "user-input-stdin" interaction/run-stdin
                            "multi-agent" config-fixture/parallel-research
                            run) client options prompts)))]
    (spit "result.edn" (pr-str result))))

(defn -main [& args]
  (try
    (let [names (concat (map first (live/selected nil nil))
                        ["session-filesystem" "commands" "elicitation-url" "user-input-stdin" "structured-output-primitive"])]
      (cond
        (empty? args) (run! exercise! names)
        (and (= 1 (count args)) (some #{(first args)} names)) (exercise! (first args))
        (and (= 4 (count args)) (= "--fixture-child" (first args))) (apply run-child! (rest args))
        :else (throw (ex-info "Expected an example name or no arguments" {:recipes names}))))
    (finally (shutdown-agents))))
