(ns github.copilot-sdk.tool-set
  "Helpers for building source-qualified tool filter patterns used by
   `:available-tools` and `:excluded-tools` in session configs.

   The runtime matches each tool reference against patterns of the form
   `\"<source>:<name>\"` where source is one of `builtin`, `mcp`, or
   `custom` and name is either a literal tool name or the wildcard
   `\"*\"`. The bare wildcard `\"*\"` (no source) is intentionally
   rejected by the SDK — apps must explicitly opt into a source so an
   absent source can never silently grant access to unexpected tools.

   Mirrors upstream `ToolSet` and `BuiltInTools` from
   `nodejs/src/toolSet.ts` (upstream PR #1428).

   Examples:

   ```clojure
   (tool-set/builtin \"ask_user\")  ;; => \"builtin:ask_user\"
   (tool-set/builtin \"*\")          ;; => \"builtin:*\"
   (tool-set/mcp \"*\")              ;; => \"mcp:*\"
   (tool-set/builtins [\"task\" \"skill\"]) ;; => [\"builtin:task\" \"builtin:skill\"]

   ;; Ready-to-use: every built-in that is safely session-bounded.
   tool-set/isolated  ;; => [\"builtin:ask_user\" \"builtin:task_complete\" ...]
   ```")

(def ^:private valid-name-regex
  "Allowed characters in a tool name segment (mirrors upstream `nameRe`)."
  #"^[a-zA-Z0-9_-]+$")

(defn valid-name?
  "True when `name` is a valid tool-name segment — alphanumeric plus
   `_` and `-`, or the literal wildcard `\"*\"`. Returns false for any
   non-string input."
  [name]
  (and (string? name)
       (or (= "*" name)
           (some? (re-matches valid-name-regex name)))))

(defn- validate! [source name]
  (when-not (valid-name? name)
    (throw (ex-info (format "Invalid %s tool name: %s" source (pr-str name))
                    {:source source :name name}))))

(defn builtin
  "Returns the filter pattern `\"builtin:<name>\"`. `name` may be the
   wildcard `\"*\"`. Throws on invalid names."
  [name]
  (validate! "builtin" name)
  (str "builtin:" name))

(defn mcp
  "Returns the filter pattern `\"mcp:<name>\"`. `name` may be the
   wildcard `\"*\"`. Throws on invalid names."
  [name]
  (validate! "mcp" name)
  (str "mcp:" name))

(defn custom
  "Returns the filter pattern `\"custom:<name>\"`. `name` may be the
   wildcard `\"*\"`. Throws on invalid names."
  [name]
  (validate! "custom" name)
  (str "custom:" name))

(defn builtins
  "Returns a vector of `\"builtin:<name>\"` patterns, one per entry in
   `names`. Throws on the first invalid name."
  [names]
  (mapv builtin names))

(def isolated-builtins
  "Names of the built-in tools that are safely session-bounded (no host
   I/O). Mirrors upstream `BuiltInTools.Isolated`. Use [[isolated]] for
   the source-qualified form ready to drop into `:available-tools`."
  ["ask_user"
   "task_complete"
   "exit_plan_mode"
   "task"
   "read_agent"
   "write_agent"
   "list_agents"
   "send_inbox"
   "context_board"
   "skill"])

(def isolated
  "Source-qualified `\"builtin:<name>\"` patterns for every tool in
   [[isolated-builtins]]. Drop directly into `:available-tools`:

     (copilot/create-session client
       {:available-tools tool-set/isolated})"
  (builtins isolated-builtins))
