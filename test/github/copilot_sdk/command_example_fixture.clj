(ns github.copilot-sdk.command-example-fixture
  "A framed host for slash commands and incoming URL requests. No UI or model."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [commands]
            [elicitation-provider]
            [copilot.sdk :as copilot]
            [github.copilot-sdk.framed-peer :as peer]
            [github.copilot-sdk.native-host :as host]
            [github.copilot-sdk.protocol :as rpc]))

(defn- with-host [f]
  (let [{:keys [conn ->client <-client]} (peer/open-connection {})
        handled (a/chan 1)
        emit!! (fn [session type data method answer]
                 (let [id (copilot/session-id session)
                       request-id (str (random-uuid))]
                   (rpc/send-notification conn "session.event"
                                          {:session-id id :event {:type type :id (str (random-uuid))
                                                                  :data (assoc data :request-id request-id)}})
                   (let [[ack port] (a/alts!! [handled (a/timeout 5000)])]
                     (when-not (and (= port handled) (= ack [method (assoc answer :session-id id :request-id request-id)]))
                       (throw (ex-info "Missing matching host response" {:type type :received ack}))))))]
    (try
      (rpc/set-request-handler!
       conn
       (fn [method params]
         (let [result
               (case method
                 "connect" {:protocol-version 4}
                 "session.create" {:session-id (:session-id params)}
                 ("session.commands.handlePendingCommand" "session.ui.handlePendingElicitation")
                 (do (when-not (a/offer! handled [method params]) (throw (ex-info "Unexpected extra response" {})))
                     {:success true})
                 "session.detach" {:success true}
                 "runtime.shutdown" {})]
           (a/go {:result result}))))
      (with-redefs [host/open! (constantly {:in <-client :out ->client
                                            :configure-ui! (constantly nil) :close! (constantly nil)})]
        (copilot/with-client [client {:mode :coding-agent}]
          (f client emit!!)))
      (finally (a/close! handled) (rpc/disconnect conn) (.close <-client) (.close ->client)))))

(defn run []
  (with-host
    (fn [client emit!!]
      (commands/run client {}
                    (fn [session command]
                      (let [[name args] (str/split (subs command 1) #" " 2)]
                        (emit!! session "command.execute" {:command-name name :command command :args (or args "")}
                                "session.commands.handlePendingCommand" {})))))))

(defn run-url []
  (with-host
    (fn [client emit!!]
      (copilot/with-session [session client {:tools {:available-tools []}
                                             :interaction {:on-elicitation-request elicitation-provider/handle-request}}]
        (emit!! session "elicitation.requested"
                {:mode "url" :url "https://example.invalid/consent" :elicitation-source "demo"
                 :message "Open a consent page."}
                "session.ui.handlePendingElicitation" {:result {:action "decline"}})))))

(defn -main [& [name]]
  (try
    (case name
      nil (run)
      "commands" (run)
      "elicitation-url" (run-url))
    (finally (shutdown-agents))))
