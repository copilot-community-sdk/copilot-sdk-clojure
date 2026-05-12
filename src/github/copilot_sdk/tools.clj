(ns github.copilot-sdk.tools
  "Helper functions for defining tools."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(defn define-tool
  "Define a tool with a handler function.
   
   Arguments:
   - name        - Tool name (string)
   - opts map:
     - :description             - Tool description
     - :parameters              - JSON schema for parameters (or nil)
     - :handler                 - Function (fn [args invocation] -> result)
     - :overrides-built-in-tool - When true, explicitly overrides a built-in tool of the same name.
                                  Without this flag, name clashes with built-in tools cause an error.
   
   The handler receives:
   - args       - The parsed arguments from the LLM (no key conversion)
   - invocation - Map with :session-id, :tool-call-id, :tool-name, :arguments
   
   The handler should return one of:
   - A string (treated as success)
   - A map with :text-result-for-llm and :result-type
   - Any other value (JSON-encoded as success)
   - A core.async channel that will yield one of the above
   
   Example:
   ```clojure
   (define-tool \"get_weather\"
     {:description \"Get weather for a location\"
      :parameters {:type \"object\"
                   :properties {:location {:type \"string\"}}
                   :required [\"location\"]}
      :handler (fn [args _]
                 (str \"Weather in \" (:location args) \": Sunny, 72°F\"))})
   ```"
  [name {:keys [description parameters handler overrides-built-in-tool]}]
  (cond-> {:tool-name name
           :tool-description description
           :tool-parameters parameters
           :tool-handler handler}
    (some? overrides-built-in-tool)
    (assoc :overrides-built-in-tool overrides-built-in-tool)))

(defn define-tool-from-spec
  "Define a tool using a clojure.spec for parameter validation.
    
   Parameters are validated against the spec at invocation time.
   Note: the spec is NOT auto-converted to JSON schema, so this tool
   has no parameter schema advertised to the model. For tools that need
   a parameter schema, use define-tool with an explicit JSON schema.
   
   Arguments:
   - name        - Tool name (string)
   - opts map:
     - :description             - Tool description
     - :spec                    - A clojure.spec for the arguments
     - :handler                 - Function (fn [args invocation] -> result)
     - :overrides-built-in-tool - When true, overrides a built-in tool of the same name
   
   Example:
   ```clojure
   (s/def ::location string?)
   (s/def ::get-weather-args (s/keys :req-un [::location]))
   
   (define-tool-from-spec \"get_weather\"
     {:description \"Get weather for a location\"
      :spec ::get-weather-args
      :handler (fn [args _]
                 (if (s/valid? ::get-weather-args args)
                   (str \"Weather: Sunny\")
                   {:text-result-for-llm (str \"Invalid args: \" (s/explain-str ::get-weather-args args))
                    :result-type \"failure\"}))})
   ```"
  [name {:keys [description spec handler overrides-built-in-tool]}]
  ;; For now, we don't auto-convert spec to JSON schema
  ;; The handler should validate using the spec
  (cond-> {:tool-name name
           :tool-description description
           :tool-parameters nil  ; User should provide JSON schema if needed
           :tool-handler (fn [args invocation]
                           (if (and spec (not (s/valid? spec args)))
                             {:text-result-for-llm (str "Invalid arguments: " (s/explain-str spec args))
                              :result-type "failure"
                              :error "spec validation failed"}
                             (handler args invocation)))}
    (some? overrides-built-in-tool)
    (assoc :overrides-built-in-tool overrides-built-in-tool)))

(defn result-success
  "Create a successful tool result."
  ([text]
   (result-success text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "success"
    :tool-telemetry telemetry}))

(defn result-failure
  "Create a failed tool result."
  ([text]
   (result-failure text nil))
  ([text error]
   (result-failure text error {}))
  ([text error telemetry]
   {:text-result-for-llm text
    :result-type "failure"
    :error error
    :tool-telemetry telemetry}))

(defn result-denied
  "Create a denied tool result (permission denied)."
  ([text]
   (result-denied text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "denied"
    :tool-telemetry telemetry}))

(defn result-rejected
  "Create a rejected tool result (user rejected)."
  ([text]
   (result-rejected text {}))
  ([text telemetry]
   {:text-result-for-llm text
    :result-type "rejected"
    :tool-telemetry telemetry}))

(defn convert-mcp-call-tool-result
  "Convert an MCP CallToolResult into the SDK's ToolResultObject format.

   The input map should have Clojure-idiomatic keys:
   - :content    - vector of content blocks, each with :type and type-specific fields
   - :is-error   - optional boolean, when true the result-type is \"failure\"

   Content block types:
   - {:type \"text\" :text \"...\"}
   - {:type \"image\" :data \"base64...\" :mime-type \"image/png\"}
   - {:type \"resource\" :resource {:uri \"...\" :text \"...\" :blob \"...\" :mime-type \"...\"}}

   Returns a ToolResultObject map with :text-result-for-llm, :result-type, and
   optionally :binary-results-for-llm."
  [{:keys [content is-error]}]
  (let [text-parts (transient [])
        binary-results (transient [])]
    (doseq [block content]
      (case (:type block)
        "text"
        (when (string? (:text block))
          (conj! text-parts (:text block)))

        "image"
        (when (and (string? (:data block))
                   (seq (:data block))
                   (string? (:mime-type block)))
          (conj! binary-results {:data (:data block)
                                 :mime-type (:mime-type block)
                                 :type "image"}))

        "resource"
        (let [resource (:resource block)]
          (when (:text resource)
            (conj! text-parts (:text resource)))
          (when (:blob resource)
            (let [mt (:mime-type resource)]
              (conj! binary-results {:data (:blob resource)
                                     :mime-type (if (and (string? mt) (seq mt))
                                                  mt
                                                  "application/octet-stream")
                                     :type "resource"
                                     :description (:uri resource)}))))

        ;; Unknown content type — skip
        nil))
    (let [binaries (persistent! binary-results)]
      (cond-> {:text-result-for-llm (str/join "\n" (persistent! text-parts))
               :result-type (if is-error "failure" "success")}
        (seq binaries) (assoc :binary-results-for-llm binaries)))))
