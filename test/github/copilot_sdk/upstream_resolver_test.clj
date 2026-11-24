(ns github.copilot-sdk.upstream-resolver-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest resolves-only-the-official-repository
  (let [repo (.toFile (Files/createTempDirectory "copilot-upstream-" (make-array FileAttribute 0)))
        git (fn [& args] (apply sh "git" "-C" (.getPath repo) args))
        env (assoc (into {} (System/getenv)) "COPILOT_SDK_UPSTREAM" (.getPath repo))]
    (try
      (is (zero? (:exit (git "init" "-q"))))
      (is (zero? (:exit (git "remote" "add" "origin" "https://github.com/microsoft/copilot-sdk"))))
      (doseq [[url official?] [["https://github.com/microsoft/copilot-sdk" true]
                               ["ssh://git@github.com/microsoft/copilot-sdk.git" true]
                               ["git@github.com:microsoft/copilot-sdk.git" true]
                               ["https://github.com/github/copilot-sdk.git" true]
                               ["https://github.com/microsoft/copilot-sdk-fork" false]
                               ["https://github.example/microsoft/copilot-sdk" false]]]
        (testing url
          (is (zero? (:exit (git "remote" "set-url" "origin" url))))
          (let [{:keys [exit out]} (sh "bash" ".github/skills/update-upstream/scripts/resolve-upstream.sh"
                                       :env env)]
            (is (= (zero? exit) official?))
            (if official?
              (is (= (.getCanonicalFile (io/file (str/trim out))) (.getCanonicalFile repo)))
              (is (str/blank? out))))))
      (finally
        (doseq [file (reverse (file-seq repo))]
          (Files/delete (.toPath file)))))))
