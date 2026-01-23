(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; Library coordinates
;; For Clojars: net.clojars.krukow/copilot-sdk
;; For Maven Central: io.github.krukow/copilot-sdk (requires domain verification)
(def lib 'io.github.krukow/copilot-sdk)
(def version "0.1.1-SNAPSHOT")
#_ ; alternatively, use MAJOR.MINOR.COMMITS:
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def readme-path "README.md")

;; Namespaces to AOT compile for Java interop
(def aot-namespaces ['krukow.copilot-sdk.java-api])

(defn- pom-template [version]
  [[:description "Clojure SDK for GitHub Copilot CLI with Java interop support."]
   [:url "https://github.com/krukow/copilot-sdk-clojure"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:developers
    [:developer
     [:id "krukow"]
     [:name "Karl Krukow"]
     [:email "karl@krukow.com"]]]
   [:scm
    [:url "https://github.com/krukow/copilot-sdk-clojure"]
    [:connection "scm:git:https://github.com/krukow/copilot-sdk-clojure.git"]
    [:developerConnection "scm:git:ssh:git@github.com:krukow/copilot-sdk-clojure.git"]
    [:tag (str "v" version)]]])

(defn- jar-opts [opts]
  (assoc opts
          :lib lib   :version version
          :jar-file  (format "target/%s-%s.jar" lib version)
          :basis     (b/create-basis {})
          :class-dir class-dir
          :target    "target"
          :src-dirs  ["src"]
          :pom-data  (pom-template version)))

(defn jar "Build the JAR (source only, no AOT)." [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn aot-jar
  "Build the JAR with AOT compilation for Java interop.

   This compiles the Java API class so it can be used directly from Java:

   ```java
   import krukow.copilot_sdk.Copilot;
   String answer = Copilot.query(\"What is 2+2?\");
   ```

   Uses direct linking for faster invocation and smaller class sizes."
  [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)
        basis (b/create-basis {})]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying Clojure source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nAOT compiling with direct linking:" aot-namespaces)
    (b/compile-clj {:basis basis
                    :ns-compile aot-namespaces
                    :class-dir class-dir
                    :compiler-options {:direct-linking true}})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
  opts)

(defn uber
  "Build an uberjar with all dependencies and AOT compilation.

   Creates a standalone JAR that can be used from Java without
   additional Clojure dependencies on the classpath.

   Uses direct linking for faster invocation and smaller class sizes."
  [opts]
  (b/delete {:path "target"})
  (let [opts (assoc (jar-opts opts)
                    :uber-file (format "target/%s-%s-standalone.jar" lib version))
        basis (b/create-basis {})]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying Clojure source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nAOT compiling with direct linking:" aot-namespaces)
    (b/compile-clj {:basis basis
                    :ns-compile aot-namespaces
                    :class-dir class-dir
                    :compiler-options {:direct-linking true}})
    (println "\nBuilding uberjar..." (:uber-file opts))
    (b/uber opts))
  opts)

(defn install "Install the JAR locally." [opts]
  (let [opts (jar-opts opts)]
    (b/install opts))
  opts)

(defn deploy "Deploy the JAR to Clojars." [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (dd/deploy {:installer :remote :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  opts)

(defn bundle
  "Create a bundle for manual upload to Maven Central.

   The new Sonatype Central Portal requires uploading a bundle zip.

   Usage:
     clj -T:build bundle :version '\"0.1.0\"'

   Then upload at https://central.sonatype.com/publishing"
  [{release-version :version :as opts}]
  (when-not release-version
    (throw (ex-info "Version required. Usage: clj -T:build bundle :version '\"0.1.0\"'" {})))
  (when (str/ends-with? release-version "-SNAPSHOT")
    (throw (ex-info "SNAPSHOT versions not supported by Maven Central" {:version release-version})))
  ;; Override version
  (alter-var-root #'build/version (constantly release-version))
  ;; Build the AOT jar
  (aot-jar opts)
  (let [jar-file (format "target/%s-%s.jar" lib release-version)
        pom-file (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")
        bundle-dir "target/bundle"
        artifact-dir (str bundle-dir "/io/github/krukow/copilot-sdk/" release-version)
        jar-name (format "copilot-sdk-%s.jar" release-version)
        sources-jar-name (format "copilot-sdk-%s-sources.jar" release-version)
        javadoc-jar-name (format "copilot-sdk-%s-javadoc.jar" release-version)
        pom-name (format "copilot-sdk-%s.pom" release-version)]
    ;; Create bundle directory structure
    (b/delete {:path bundle-dir})
    (shell/sh "mkdir" "-p" artifact-dir)

    ;; Create sources JAR
    (println "\nCreating sources JAR...")
    (let [sources-jar (str artifact-dir "/" sources-jar-name)]
      (b/jar {:class-dir "src"
              :jar-file sources-jar}))

    ;; Create javadoc JAR (placeholder with README for Clojure projects)
    (println "Creating javadoc JAR...")
    (let [javadoc-dir "target/javadoc"
          javadoc-jar (str artifact-dir "/" javadoc-jar-name)]
      (b/delete {:path javadoc-dir})
      (shell/sh "mkdir" "-p" javadoc-dir)
      (spit (str javadoc-dir "/README.md")
            (str "# Copilot SDK for Clojure\n\n"
                 "This is a Clojure library. API documentation is available in the source code.\n\n"
                 "See https://github.com/krukow/copilot-sdk-clojure for documentation.\n"))
      (b/jar {:class-dir javadoc-dir
              :jar-file javadoc-jar}))

    ;; Copy main artifacts
    (println "\nCopying artifacts to bundle...")
    (println "  JAR:" jar-file)
    (println "  POM:" pom-file)
    (let [cp-jar (shell/sh "cp" jar-file (str artifact-dir "/" jar-name))
          cp-pom (shell/sh "cp" pom-file (str artifact-dir "/" pom-name))]
      (when-not (zero? (:exit cp-jar))
        (throw (ex-info "Failed to copy JAR" {:jar-file jar-file :result cp-jar})))
      (when-not (zero? (:exit cp-pom))
        (throw (ex-info "Failed to copy POM" {:pom-file pom-file :result cp-pom}))))

    ;; Sign all artifacts
    (println "\nSigning artifacts with GPG...")
    (let [jar-path (str artifact-dir "/" jar-name)
          sources-path (str artifact-dir "/" sources-jar-name)
          javadoc-path (str artifact-dir "/" javadoc-jar-name)
          pom-path (str artifact-dir "/" pom-name)]
      (doseq [path [jar-path sources-path javadoc-path pom-path]]
        (let [result (shell/sh "gpg" "-ab" path)]
          (when-not (zero? (:exit result))
            (throw (ex-info (str "Failed to sign " path) result)))))

      ;; Generate checksums for all artifacts
      (println "Generating checksums...")
      (doseq [path [jar-path sources-path javadoc-path pom-path]]
        (spit (str path ".md5") (str/trim (:out (shell/sh "md5" "-q" path))))
        (spit (str path ".sha1") (first (str/split (:out (shell/sh "shasum" "-a" "1" path)) #"\s+")))))

    ;; Create bundle zip
    (println "Creating bundle zip...")
    (let [bundle-zip (str "target/copilot-sdk-" release-version "-bundle.zip")
          zip-result (shell/sh "sh" "-c" (str "cd " bundle-dir " && zip -r ../copilot-sdk-" release-version "-bundle.zip ."))]
      (when-not (zero? (:exit zip-result))
        (throw (ex-info "Failed to create zip" zip-result)))
      (println "\n✅ Bundle created:" bundle-zip)
      (println "\n📦 Bundle contents:")
      (println (:out (shell/sh "unzip" "-l" bundle-zip)))
      (println "Next steps:")
      (println "1. Go to https://central.sonatype.com/publishing")
      (println "2. Click 'Publish Component'")
      (println "3. Upload:" bundle-zip)
      (println "4. Wait for validation and click 'Publish'")))
  opts)

(defn release
  "Prepare and deploy a release version to Maven Central.

   Usage:
     clj -T:build release :version '\"0.1.0\"'

   This will:
   1. Build the AOT-compiled JAR
   2. Sign with GPG
   3. Create bundle for upload

   Then manually upload at https://central.sonatype.com/publishing"
  [{:keys [version] :as opts}]
  (bundle opts))

(defn update-readme-sha
  "Update README.md git dependency SHA to the current HEAD."
  [_opts]
  (let [{:keys [exit out err]} (shell/sh "git" "rev-parse" "HEAD")
        _ (when-not (zero? exit)
            (throw (ex-info "Failed to read git SHA" {:exit exit :err err})))
        sha (str/trim out)
        contents (slurp readme-path)
        updated (str/replace contents #":git/sha \"[^\"]+\"" (str ":git/sha \"" sha "\""))]
    (when (= contents updated)
      (throw (ex-info "README.md git SHA not updated (pattern not found)." {})))
    (spit readme-path updated)
    (println "Updated README.md git SHA to" sha)
    {:sha sha}))
