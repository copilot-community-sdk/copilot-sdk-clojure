#!/usr/bin/env bb
;; Run the native gates from the built JAR and its own POM, not src or user deps.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])
(import '[java.util.jar JarFile])

(let [[classifier & flags] *command-line-args*
      authenticated? (= ["--authenticated"] flags)]
  (when-not (and (or (empty? flags) authenticated?) (#{"native-darwin-arm64" "native-linux-x64"} classifier))
    (throw (ex-info "Select a native classifier and optionally --authenticated" {})))
  (when (and authenticated? (str/blank? (System/getenv "COPILOT_NATIVE_SMOKE_TOKEN")))
    (throw (ex-info "The protected release smoke token is not configured" {})))
  (let [root (fs/canonicalize ".")
        target (fs/path root "target" "io.github.copilot-community-sdk")
        [jar & other] (fs/glob target "copilot-sdk-clojure-*.jar")]
    (when (or (nil? jar) (seq other))
      (throw (ex-info "Build a single candidate JAR with bb jar first" {:directory (str target)})))
    (with-open [archive (JarFile. (fs/file jar))]
      (doseq [name ["github/copilot_sdk.clj" "github/copilot_sdk/native_host.clj"
                    "github/copilot_sdk/api_surface.edn"]]
        (when-not (.getEntry archive name) (throw (ex-info "Native artifact is incomplete" {:path name}))))
      (when (.getEntry archive "github/copilot_sdk/process.clj")
        (throw (ex-info "Native artifact contains the CLI process transport" {}))))
    (let [config (edn/read-string (slurp (fs/file root "deps.edn")))
          deps (merge {'io.github.copilot-community-sdk/copilot-sdk-clojure {:local/root (str jar)}}
                      (get-in config [:aliases (keyword classifier) :extra-deps]))
          namespaces '[github.copilot-sdk.native-offline-test github.copilot-sdk.native-fs-integration-test]
          form `(do
                  (require 'clojure.test 'clojure.java.io 'github.copilot-sdk.native-host-test
                           ~@(map #(list 'quote %) namespaces))
                  (assert (= "jar" (.getProtocol (clojure.java.io/resource "github/copilot_sdk.clj"))))
                  (let [turns# (clojure.test/run-tests ~@(map #(list 'quote %) namespaces))
                        hosts# (clojure.test/run-test-var #'github.copilot-sdk.native-host-test/isolated-real-native-hosts)]
                    (shutdown-agents)
                    (System/exit (if (every? clojure.test/successful? [turns# hosts#]) 0 1))))]
      (fs/with-temp-dir [consumer {:dir target :prefix "copilot-sdk-consumer-"}]
        (doseq [file (if authenticated?
                       ["authenticated_smoke"]
                       ["native_offline_test" "native_offline_fixture" "native_fs_integration_test"
                        "memory_fs_fixture" "native_host_test"])]
          (let [path (str "github/copilot_sdk/" file ".clj")
                dest (fs/path consumer "test" path)]
            (fs/create-dirs (fs/parent dest))
            (fs/copy (fs/path root "test" path) dest)))
        (spit (fs/file consumer "deps.edn") (pr-str {:paths ["test"] :deps deps}))
        (let [classpath (:out (p/shell {:dir (str consumer) :out :string} "clojure" "-Srepro" "-Spath"))]
          (when (str/includes? (str/replace classpath "\\" "/") "/com/microsoft/copilot-sdk/")
            (throw (ex-info "Standalone consumer resolved the Copilot Java SDK" {}))))
        (if authenticated?
          (p/shell {:dir (str consumer)} "clojure" "-Srepro" "-M" "-m"
                   "github.copilot-sdk.authenticated-smoke" (str (fs/path consumer "runtime-home")))
          (p/shell {:dir (str consumer)} "clojure" "-Srepro" "-M" "-e" (pr-str form)))))))
