(ns github.copilot-sdk.historical-evidence-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security MessageDigest]
           [java.util HexFormat]))

(deftest v1-inventories-retain-their-certified-bytes
  (let [dir (io/file "test/resources")
        {:keys [v1-inventory-sha256]} (edn/read-string (slurp (io/file dir "v1_provenance.edn")))
        digest (MessageDigest/getInstance "SHA-256")]
    (doseq [file (sort-by #(.getName %) (.listFiles dir))
            :when (re-matches #"(?:optional_wire_contracts|stable_upstream_delta_[a-f0-9]+)\.edn"
                              (.getName file))]
      (.update digest (.getBytes (.getName file) StandardCharsets/UTF_8))
      (.update digest (byte 0))
      (.update digest (Files/readAllBytes (.toPath file)))
      (.update digest (byte 0)))
    (is (= (.formatHex (HexFormat/of) (.digest digest)) v1-inventory-sha256))))
