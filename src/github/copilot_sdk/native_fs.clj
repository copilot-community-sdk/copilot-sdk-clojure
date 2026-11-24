(ns ^:no-doc github.copilot-sdk.native-fs
  "SessionFs provider validation and wire operations. Protocol 4, runtime 1.0.114:
  microsoft/copilot-sdk@3ce61458503b6b27ebd1f674a980f4d9596d9b22,
  nodejs/src/sessionFsProvider.ts and client.ts. No operation uses host files."
  (:require [clojure.spec.alpha :as s]
            [github.copilot-sdk.specs :as specs]
            [github.copilot-sdk.util :as util])
  (:import [java.nio.file NoSuchFileException]
           [java.time Instant]
           [java.util Base64]))

(def ^:private capability-names {:binary "binary" :read-file-range "readFileRange" :sqlite "sqlite"})

(defn provider-params
  "Validate client :session-fs and return the exact params for sessionFs.setProvider."
  [config]
  (when-not (s/valid? ::specs/session-fs config)
    (throw (ex-info "Invalid client :session-fs configuration" {:path [:session-fs]})))
  (cond-> {"initialCwd" (:initial-cwd config) "sessionStatePath" (:session-state-path config)
           "conventions" (:conventions config)}
    (contains? config :capabilities)
    (assoc "capabilities" (update-keys (:capabilities config) capability-names))))

(def method->op
  {"sessionFs.readFile" :read-file "sessionFs.writeFile" :write-file "sessionFs.appendFile" :append-file
   "sessionFs.exists" :exists "sessionFs.stat" :stat "sessionFs.mkdir" :mkdir
   "sessionFs.readdir" :readdir "sessionFs.readdirWithTypes" :readdir-with-types
   "sessionFs.rm" :rm "sessionFs.rename" :rename "sessionFs.readFileBytes" :read-file-bytes
   "sessionFs.writeFileBytes" :write-file-bytes "sessionFs.readFileRange" :read-file-range
   "sessionFs.sqliteQuery" :sqlite-query "sessionFs.sqliteExists" :sqlite-exists
   "sessionFs.sqliteTransaction" :sqlite-transaction})

(defn validate-provider [caps p]
  (when-not (s/valid? ::specs/session-fs-provider p)
    (throw (ex-info "Expected a SessionFs provider with positional operation functions" {})))
  (doseq [[cap ops] {:binary [:read-file-bytes :write-file-bytes] :read-file-range [:read-file-range] :sqlite [:sqlite]}
          :when (get caps cap) op ops :when (not (get p op))]
    (throw (ex-info "Provider does not implement the declared SessionFs capability" {:capability cap :missing op})))
  p)

(defn- expect [pred value message]
  (if (pred value) value (throw (ex-info message {}))))

(defn- safe-size? [x] (and (integer? x) (<= 0 x 9007199254740991)))

(def ^:private max-base64 (- (* 64 1024 1024) 1024))
(def ^:private max-bytes (* 3 (quot max-base64 4)))

(defn- encode-bytes [content]
  (.encodeToString (Base64/getEncoder) ^bytes (expect bytes? content "Expected a byte array")))

(defn- decode-bytes [text]
  (expect #(and (string? %) (<= (count %) max-base64)) text "Invalid or oversized SessionFs binary content")
  (let [bytes (.decode (Base64/getDecoder) ^String text)]
    (when-not (and (<= (alength bytes) max-bytes) (= text (encode-bytes bytes)))
      (throw (ex-info "Invalid sessionFs.writeFileBytes base64 content" {})))
    bytes))

(defn- read-range [provider-call f path params]
  (let [offset (expect safe-size? (:offset params) "Invalid SessionFs byte offset")
        length (when (contains? params :length) (expect safe-size? (:length params) "Invalid SessionFs byte length"))
        {:keys [content byte-length]} (provider-call f path offset length)
        total (expect safe-size? byte-length "Invalid provider file byte length")
        available (max 0 (- total offset))]
    (when-not (and (bytes? content) (= (alength ^bytes content) (min (or length available) available)))
      (throw (ex-info "Provider returned an incorrect SessionFs byte range" {})))
    {:content (encode-bytes content) :byte-length total}))

(defn- file-info [m]
  (when-not (and (map? m) (boolean? (:is-file m)) (boolean? (:is-directory m))
                 (safe-size? (:size m)) (string? (:mtime m)) (string? (:birthtime m)))
    (throw (ex-info "Invalid SessionFs file info" {})))
  (select-keys m [:is-file :is-directory :size :mtime :birthtime]))

(defn- entry [{:keys [name type]}]
  {:name (expect string? name "Expected a directory entry name")
   :type (clojure.core/name (expect #{:file :directory} type "Invalid directory entry type"))})

(defn- statement [{:keys [query-type query] :as params}]
  (let [qtype (get {"exec" :exec "run" :run "query" :query} query-type)]
    (when-not qtype (throw (ex-info "Invalid SessionFs SQLite query type" {})))
    (cond-> {:query-type qtype :query (expect string? query "Expected SQL text")}
      (contains? params :params)
      (assoc :params (expect #(and (map? %) (every? (fn [v] (or (nil? v) (string? v) (number? v))) (vals %)))
                             (:params params) "Invalid SessionFs SQLite bindings")))))

(defn- sql-result [{:keys [rows columns rows-affected] :as result}]
  (let [rows (util/opaque-json->wire rows)]
    (when-not (and (vector? rows) (every? #(s/valid? ::specs/json-object %) rows)
                   (vector? columns) (every? string? columns) (safe-size? rows-affected))
      (throw (ex-info "Invalid SessionFs SQLite query result" {})))
    (cond-> {:rows rows :columns columns :rows-affected rows-affected}
      (contains? result :last-insert-rowid)
      (assoc :last-insert-rowid (expect integer? (:last-insert-rowid result) "Invalid SQLite row ID")))))

(defn- capability [caps key f]
  (or (and (true? (get caps key)) f)
      (throw (ex-info (str "SessionFs capability is not available: " (name key)) {}))))

(defn- perform [provider-call p caps op params]
  (let [{:keys [path mode content recursive force src dest]} params
        path (when-not (#{:rename :sqlite-query :sqlite-exists :sqlite-transaction} op)
               (expect string? path "Expected a SessionFs path"))
        f (get p op)
        mode (when (contains? params :mode) (expect safe-size? mode "Invalid SessionFs file mode"))
        flag (fn [key value] (if (contains? params key) (expect boolean? value "Invalid SessionFs flag") false))]
    (case op
      :read-file {:content (expect string? (provider-call f path) "Expected SessionFs text")}
      (:write-file :append-file) (do (provider-call f path (expect string? content "Expected SessionFs text") mode) nil)
      :exists {:exists (expect boolean? (provider-call f path) "Expected a boolean")}
      :stat (file-info (provider-call f path))
      :mkdir (do (provider-call f path (flag :recursive recursive) mode) nil)
      :rm (do (provider-call f path (flag :recursive recursive) (flag :force force)) nil)
      :rename (do (provider-call f (expect string? src "Expected source path") (expect string? dest "Expected destination path")) nil)
      :readdir {:entries (expect #(and (vector? %) (every? string? %)) (provider-call f path) "Expected a vector of entry names")}
      :readdir-with-types {:entries (mapv entry (expect vector? (provider-call f path) "Expected a vector of directory entries"))}
      :read-file-bytes (let [bs (provider-call (capability caps :binary f) path)]
                         (expect #(and (bytes? %) (<= (alength ^bytes %) max-bytes)) bs "Invalid or oversized SessionFs binary result")
                         {:content (encode-bytes bs)})
      :write-file-bytes (do (provider-call (capability caps :binary f) path (decode-bytes content) mode) nil)
      :read-file-range (read-range provider-call (capability caps :read-file-range f) path params)
      :sqlite-query (let [f (capability caps :sqlite (get-in p [:sqlite :query]))
                          {:keys [query-type query params]} (statement params)
                          result (provider-call f query-type query params)]
                      (if (nil? result) {:rows [] :columns [] :rows-affected 0} (sql-result result)))
      :sqlite-exists {:exists (expect boolean? (provider-call (capability caps :sqlite (get-in p [:sqlite :exists]))) "Expected a boolean")}
      :sqlite-transaction
      (let [f (capability caps :sqlite (get-in p [:sqlite :transaction]))
            statements (mapv statement (expect vector? (:statements params) "Expected SQLite statements"))
            results (provider-call f statements)]
        (expect #(and (vector? %) (= (count %) (count statements))) results "Expected one result per SQLite statement")
        {:results (mapv sql-result results)}))))

(defn- failure [op e]
  (let [data (ex-data e)
        msg (or (ex-message e) (.getName (class e)))
        err {:code (if (or (= "ENOENT" (:code data)) (instance? NoSuchFileException e)) "ENOENT" "UNKNOWN")
             :message msg}]
    (case op
      (:sqlite-query :sqlite-exists) {:error {:code -32603 :message msg}}
      {:result (case op
                 (:read-file :read-file-bytes) {:content "" :error err}
                 :read-file-range {:content "" :byte-length 0 :error err}
                 :exists {:exists false}
                 :stat (let [now (str (Instant/now))]
                         {:is-file false :is-directory false :size 0 :mtime now :birthtime now :error err})
                 (:readdir :readdir-with-types) {:entries [] :error err}
                 :sqlite-transaction
                 {:results [] :error {:message msg
                                      :error-class (get (when (= :session-fs-sqlite-transaction-failure (:type data))
                                                          {:busy-or-locked "busyOrLocked" :post-commit-ambiguous "postCommitAmbiguous"})
                                                        (:error-class data) "fatal")}}
                 (cond-> err
                   (and (= op :write-file) (= :session-fs-write-failure (:type data)))
                   (assoc :write-changed true)))})))

(defn handle [provider-call provider caps op params]
  (try
    {:result (perform provider-call provider caps op params)}
    (catch InterruptedException e (throw e))
    (catch Exception e (failure op e))))

(s/fdef provider-params :args (s/cat :config ::specs/session-fs) :ret map?)
