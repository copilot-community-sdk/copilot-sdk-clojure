(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.krukow/copilot-sdk)
(def version "0.1.0-SNAPSHOT")
#_ ; alternatively, use MAJOR.MINOR.COMMITS:
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def readme-path "README.md")

(defn- pom-template [version]
  [[:description "Clojure SDK for GitHub Copilot CLI."]
   [:url "https://github.com/krukow/copilot-sdk"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:developers
    [:developer
     [:name "Krukow"]]]
   [:scm
    [:url "https://github.com/krukow/copilot-sdk"]
    [:connection "scm:git:https://github.com/krukow/copilot-sdk.git"]
    [:developerConnection "scm:git:ssh:git@github.com:krukow/copilot-sdk.git"]
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

(defn jar "Build the JAR." [opts]
  (b/delete {:path "target"})
  (let [opts (jar-opts opts)]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "\nCopying source...")
    (b/copy-dir {:src-dirs ["resources" "src"] :target-dir class-dir})
    (println "\nBuilding JAR..." (:jar-file opts))
    (b/jar opts))
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

(defn update-readme-sha
  "Update README.md git dependency SHA to the current HEAD."
  [_opts]
  (let [sha (-> (b/process {:command-args ["git" "rev-parse" "HEAD"]})
                :out
                str/trim)
        contents (slurp readme-path)
        updated (str/replace contents #":git/sha \"[^\"]+\"" (str ":git/sha \"" sha "\""))]
    (when (= contents updated)
      (throw (ex-info "README.md git SHA not updated (pattern not found)." {})))
    (spit readme-path updated)
    (println "Updated README.md git SHA to" sha)
    {:sha sha}))
