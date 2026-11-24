(ns github.copilot-sdk.native-offline-fixture
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [github.copilot-sdk.teardown :as teardown])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io File]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent CompletableFuture TimeUnit]))

(def prompt "Read the offline fixture.")
(def answer "Native turn complete.")

(defn- completion-stream [deltas finish]
  (let [chunk (fn [delta finish]
                (str "data: "
                     (json/write-str {:id "fixture-1" :object "chat.completion.chunk"
                                      :created 1 :model "probe-deployment"
                                      :choices [{:index 0 :delta delta :finish_reason finish}]})
                     "\n\n"))]
    (.getBytes (str (apply str (map #(chunk % nil) deltas))
                    (chunk {} finish)
                    "data: [DONE]\n\n")
               StandardCharsets/UTF_8)))

(defn- model-handler [requests reply ^CompletableFuture failure]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (with-open [^HttpExchange exchange exchange]
          (let [method (.getRequestMethod exchange)
                path (.getPath (.getRequestURI exchange))]
            (when-not (= [method path] ["POST" "/v1/chat/completions"])
              (throw (ex-info "Unexpected model endpoint" {:method method :path path})))
            (when (.containsKey (.getRequestHeaders exchange) "Authorization")
              (throw (ex-info "Offline model received an Authorization header" {})))
            (let [request (json/read (io/reader (.getRequestBody exchange) :encoding "UTF-8")
                                     :key-fn keyword)
                  history (swap! requests conj request)
                  response (apply completion-stream (reply history))]
              (.set (.getResponseHeaders exchange) "Content-Type" "text/event-stream")
              (.sendResponseHeaders exchange 200 (alength response))
              (.write (.getResponseBody exchange) response))))
        (catch Throwable error
          (.completeExceptionally failure error))))))

(defn- darwin-network-guard [port]
  (when (= "Mac OS X" (System/getProperty "os.name"))
    ;; The model is in the parent JVM. The SDK needs neither a listener nor a child process.
    ["/usr/bin/sandbox-exec" "-p"
     (str "(version 1)(allow default)(deny process-fork)(deny network*)"
          "(allow network-outbound (remote ip \"localhost:" port "\"))")]))

(defn- run-application! [^Path home port main-args ^CompletableFuture failure]
  (let [classpath (->> (str/split (System/getProperty "java.class.path")
                                  (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))
                       (map #(.getAbsolutePath (io/file %)))
                       (str/join File/pathSeparator))
        command (concat (darwin-network-guard port)
                        [(str (io/file (System/getProperty "java.home") "bin" "java"))
                         "--enable-native-access=ALL-UNNAMED" (str "-Duser.home=" home)
                         (str "-Djava.io.tmpdir=" home) "-Dclojure.main.report=stderr"
                         "-cp" classpath "clojure.main"]
                        main-args [(str "http://127.0.0.1:" port "/v1") prompt])
        builder (doto (ProcessBuilder. ^java.util.List (vec command))
                  (.directory (.toFile home))
                  (.inheritIO))]
    (doto (.environment builder)
      (.clear)
      (.putAll (merge (select-keys (into {} (System/getenv)) ["SystemRoot"])
                      {"HOME" (str home) "USERPROFILE" (str home) "TMPDIR" (str home)
                       "TEMP" (str home) "TMP" (str home)
                       "PATH" (str (.resolve home "no-programs"))})))
    (let [process (.start builder)]
      (teardown/with-cleanup
        #(when (.isAlive process)
           (.destroyForcibly process)
           (when-not (.waitFor process 5 TimeUnit/SECONDS)
             (throw (ex-info "Offline application did not stop" {}))))
        (.get (CompletableFuture/anyOf
               (into-array CompletableFuture [failure (.onExit process)]))
              90 TimeUnit/SECONDS)
        (when-not (zero? (.exitValue process))
          (throw (ex-info "Offline application failed" {:exit (.exitValue process)})))
        (edn/read-string (slurp (.toFile (.resolve home "result.edn"))))))))

(defn exercise-turn!
  "Run an application with an empty home. The model maps requests so far to [deltas finish-reason]."
  [main-args reply]
  (let [home (Files/createTempDirectory "copilot-native-turn-" (make-array FileAttribute 0))]
    (teardown/with-cleanup
      #(with-open [paths (Files/walk home (make-array java.nio.file.FileVisitOption 0))]
         (doseq [path (reverse (iterator-seq (.iterator paths)))]
           (Files/delete path)))
      (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
            requests (atom [])
            failure (CompletableFuture.)]
        (teardown/with-cleanup #(.stop server 0)
          (.createContext server "/" (model-handler requests reply failure))
          (.start server)
          (let [event (run-application! home (.getPort (.getAddress server)) main-args failure)]
            {:event event :requests @requests}))))))
