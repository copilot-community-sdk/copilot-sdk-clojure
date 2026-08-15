(ns github.copilot-sdk.client-buffer-test
  (:require [clojure.test :refer [deftest is]]
            [github.copilot-sdk :as sdk]
            [github.copilot-sdk.client :as client]
            [github.copilot-sdk.process :as process]
            [github.copilot-sdk.protocol :as protocol])
  (:import [java.io BufferedInputStream ByteArrayInputStream
            ByteArrayOutputStream]
           [java.net Socket]))

(deftest tcp-connections-buffer-protocol-input
  (let [input (ByteArrayInputStream. (byte-array 0))
        output (ByteArrayOutputStream.)
        socket (proxy [Socket] []
                 (getInputStream [] input)
                 (getOutputStream [] output))
        captured-input (atom nil)
        copilot-client (sdk/client {:auto-start? false
                                    :use-stdio? false
                                    :port 12345})]
    (swap! (:state copilot-client) assoc :actual-port 12345)
    (with-redefs [process/connect-tcp
                  (fn [_host ^long _port ^long _timeout-ms] socket)
                  protocol/connect
                  (fn [protocol-input _protocol-output _state]
                    (reset! captured-input protocol-input)
                    ::connection)]
      (#'client/connect-tcp! copilot-client))
    (is (instance? BufferedInputStream @captured-input))
    (is (= ::connection (:connection-io @(:state copilot-client))))
    (is (identical? socket (:socket @(:state copilot-client))))))

(deftest tcp-connection-initialization-failure-retains-socket-for-cleanup
  (let [closed? (atom false)
        input (ByteArrayInputStream. (byte-array 0))
        output (ByteArrayOutputStream.)
        socket (proxy [Socket] []
                 (getInputStream [] input)
                 (getOutputStream [] output)
                 (close [] (reset! closed? true)))
        copilot-client (sdk/client {:auto-start? false
                                    :use-stdio? false
                                    :port 12345})]
    (swap! (:state copilot-client) assoc :actual-port 12345)
    (with-redefs [process/connect-tcp
                  (fn [_host ^long _port ^long _timeout-ms] socket)
                  protocol/connect
                  (fn [_protocol-input _protocol-output _state]
                    (throw (ex-info "protocol initialization failed" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"protocol initialization failed"
                            (#'client/connect-tcp! copilot-client))))
    (is (identical? socket (:socket @(:state copilot-client))))
    (is (false? @closed?))
    (is (empty? (#'client/release-transport!
                 copilot-client {:process :none})))
    (is (true? @closed?))
    (is (nil? (:socket @(:state copilot-client))))))
