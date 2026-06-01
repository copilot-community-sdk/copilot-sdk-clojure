(ns empty-mode
  "Demonstrates `:mode :empty` — the multitenancy posture for SaaS hosts
   that run sessions on behalf of multiple users and must keep each
   session isolated from the local machine.

   Compared to the default `:copilot-cli` mode, `:empty` mode:

   - Requires a tenant-scoped storage root (here we use a temp dir as
     `:copilot-home` plus an in-memory `:session-fs`).
   - Forces `COPILOT_DISABLE_KEYTAR=1` on the spawned CLI so the host
     keychain is never touched.
   - Spreads 9 safe defaults under each session's config (telemetry off,
     host-git operations off, embeddings in-memory, skills off, ...).
   - Strips the `environment_context` section from the system message.
   - Sends a follow-up `session.options.update` RPC turning off
     coauthor / manage-schedule and forcing `installedPlugins []`.
   - Requires `:available-tools` on every session (use `[]` to opt into
     no tools, or `tool-set/isolated` for the safely session-bounded
     built-in preset).

   The session itself needs auth — since the local keychain is disabled,
   this example uses a BYOK provider (OpenAI or Anthropic). Set ONE of:

     OPENAI_API_KEY    — for OpenAI
     ANTHROPIC_API_KEY — for Anthropic

   before running. The example is intentionally NOT in
   `run-all-examples.sh` for this reason."
  (:require [clojure.java.io :as io]
            [github.copilot-sdk :as copilot]
            [github.copilot-sdk.helpers :as h]
            [github.copilot-sdk.tool-set :as tool-set]))

(def defaults
  {:prompt "What is 2+2? Answer in one sentence."})

(defn- temp-dir
  "Create a fresh temp directory under java.io.tmpdir."
  [prefix]
  (let [path (java.nio.file.Files/createTempDirectory
              prefix
              (into-array java.nio.file.attribute.FileAttribute []))]
    (.toString path)))

(defn- in-memory-fs-provider
  "Build a minimal in-memory filesystem provider for a single session.
   The runtime routes session-scoped file I/O (event logs, large outputs,
   checkpoints) through these callbacks."
  []
  (let [store (atom {})]
    {:read-file (fn [path]
                  (or (get @store path)
                      (throw (ex-info "missing file" {:code "ENOENT"}))))
     :write-file (fn [path content _mode]
                   (swap! store assoc path content))
     :append-file (fn [path content _mode]
                    (swap! store update path (fnil str "") content))
     :exists (fn [path] (contains? @store path))
     :stat (fn [path]
             {:is-file true
              :is-directory false
              :size (count (get @store path ""))
              :mtime "2026-01-01T00:00:00Z"
              :birthtime "2026-01-01T00:00:00Z"})
     :mkdir (fn [_path _recursive _mode] nil)
     :readdir (fn [_path] [])
     :readdir-with-types (fn [_path] [])
     :rm (fn [path _recursive _force] (swap! store dissoc path))
     :rename (fn [src dest]
               (swap! store (fn [s] (-> s
                                        (assoc dest (get s src ""))
                                        (dissoc src)))))}))

(defn- byok-config
  "Pick a BYOK provider config based on which API key is set."
  []
  (let [openai (System/getenv "OPENAI_API_KEY")
        anthropic (System/getenv "ANTHROPIC_API_KEY")]
    (cond
      openai    {:model "gpt-5.4"
                 :provider {:provider-type :openai
                            :base-url "https://api.openai.com/v1"
                            :api-key openai}}
      anthropic {:model "claude-sonnet-4"
                 :provider {:provider-type :anthropic
                            :base-url "https://api.anthropic.com"
                            :api-key anthropic}}
      :else
      (throw (ex-info
              (str "Empty mode disables the local keychain — set "
                   "OPENAI_API_KEY or ANTHROPIC_API_KEY before running.")
              {})))))

(defn run
  "Issue a single query against a session created in `:empty` mode.

  Usage:
    OPENAI_API_KEY=sk-xxx    clojure -A:examples -X empty-mode/run
    ANTHROPIC_API_KEY=sk-xxx clojure -A:examples -X empty-mode/run :prompt '\"What is Clojure?\"'"
  [{:keys [prompt] :or {prompt (:prompt defaults)}}]
  (let [{:keys [model provider]} (byok-config)
        copilot-home (temp-dir "copilot-home-")
        cwd (temp-dir "tenant-cwd-")
        state (temp-dir "tenant-state-")]
    (println "Tenant storage:")
    (println "  copilot-home:" copilot-home)
    (println "  cwd:        " cwd)
    (println "  state:      " state)
    (println "Provider:    " (:provider-type provider))
    (println "Model:       " model)
    (println)
    (try
      (copilot/with-client
        [client {:mode :empty
                 :copilot-home copilot-home
                 :session-fs {:initial-cwd cwd
                              :session-state-path state
                              :conventions "posix"}}]
        (copilot/with-session
          [session client
           {:on-permission-request copilot/approve-all
            :model model
            :provider provider
            ;; Required in :empty mode. Use tool-set/isolated for the
            ;; safely session-bounded built-in preset, or [] for none.
            :available-tools tool-set/isolated
            :create-session-fs-handler
            (fn [_session] (in-memory-fs-provider))}]
          (println "Q:" prompt)
          (println "🤖:" (h/query prompt :session session))))
      (finally
        (doseq [d [state cwd copilot-home]]
          (try (.delete (io/file d)) (catch Exception _)))))))
