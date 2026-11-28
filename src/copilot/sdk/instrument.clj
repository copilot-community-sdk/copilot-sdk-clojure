(ns copilot.sdk.instrument
  "Opt-in spec instrumentation of the native Clojure facade."
  (:require [clojure.core.async.impl.protocols :as async]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [copilot.sdk :as sdk]
            [copilot.sdk.helpers :as h]
            [copilot.sdk.specs :as specs]
            [copilot.sdk.tool-set :as tool-set]
            [github.copilot-sdk.native-client :as client]))

(s/def ::client client/client?)
(s/def ::session client/session?)
(s/def ::session-id ::specs/session-id)
(s/def ::result-channel #(satisfies? async/ReadPort %))

(def ^:private targets (atom #{}))

(defmacro ^:private register-fdef! [sym & spec]
  `(do (s/fdef ~sym ~@spec)
       (swap! targets conj '~(symbol (resolve sym)))))

;; These calls validate their sensitive arguments in the implementation.
;; A failing stest argument spec would retain the whole options map or message.
(register-fdef! sdk/open-client!! :args (s/cat :options any?) :ret ::client)
(register-fdef! sdk/open-session!! :args (s/cat :client any? :options any?) :ret ::session)
(register-fdef! sdk/resume-session!! :args (s/cat :client any? :id any? :options any?) :ret ::session)
(register-fdef! sdk/close!! :args (s/cat :resource (s/or :client ::client :session ::session)) :ret nil?)
(register-fdef! sdk/force-close!! :args (s/cat :client ::client) :ret nil?)
(register-fdef! sdk/session-id :args (s/cat :session ::session) :ret ::session-id)
(register-fdef! sdk/session-info :args (s/cat :session ::session) :ret (s/nilable map?))
(register-fdef! sdk/list-models :args (s/cat :client ::client) :ret ::result-channel)
(register-fdef! sdk/lifecycle-events :args (s/cat :client ::client) :ret ::result-channel)
(register-fdef! sdk/respond!! :args (s/cat :session ::session :kind #{:tool :permission} :answer map?) :ret any?)
(register-fdef! sdk/mcp-tool-result :args (s/cat :result ::specs/mcp-call-tool-result) :ret ::specs/tool-result-object)
(register-fdef! sdk/approve-all :args (s/cat :request map? :invocation map?) :ret map?)
(register-fdef! sdk/status :args (s/cat :client ::client) :ret ::result-channel)
(register-fdef! sdk/ping :args (s/cat :client ::client :message (s/? string?)) :ret ::result-channel)
(register-fdef! sdk/last-session-id :args (s/cat :client ::client) :ret ::result-channel)
(register-fdef! sdk/auth-status :args (s/cat :client ::client) :ret ::result-channel)
(register-fdef! sdk/session-metadata :args (s/cat :client ::client :id ::session-id) :ret ::result-channel)
(register-fdef! sdk/list-sessions :args (s/cat :client ::client :filters (s/? map?)) :ret ::result-channel)
(register-fdef! sdk/delete-session!! :args (s/cat :client ::client :id ::session-id) :ret nil?)
(register-fdef! sdk/history :args (s/cat :session ::session :view (s/? #{:raw :idiom})) :ret ::result-channel)
(register-fdef! sdk/send! :args (s/cat :session any? :message any?) :ret ::result-channel)
(register-fdef! sdk/start-turn! :args (s/cat :session any? :message any?) :ret ::result-channel)
(register-fdef! sdk/send-and-wait!! :args (s/cat :session any? :message any?) :ret ::specs/turn-reply)
(register-fdef! sdk/abort! :args (s/cat :session ::session) :ret ::result-channel)
(register-fdef! sdk/set-model! :args (s/cat :session ::session :model string? :tuning (s/? map?)) :ret ::result-channel)
(register-fdef! sdk/subscribe :args (s/cat :session ::session) :ret ::result-channel)
(register-fdef! sdk/log! :args (s/cat :session ::session :message string? :opts (s/? map?)) :ret ::result-channel)
(register-fdef! sdk/elicit! :args (s/cat :session ::session :form map?) :ret ::result-channel)
(register-fdef! sdk/await!! :args (s/cat :operation ::result-channel) :ret any?)

;; stest retains rejected arguments in exception data. Leave validation of
;; credential-bearing configuration, headers and model text to these functions.
(register-fdef! h/query!! :args (s/cat :session any? :message any?) :ret (s/nilable string?))
(register-fdef! h/query-once!! :args (s/cat :config any? :message any?) :ret (s/nilable string?))
(register-fdef! h/decode-json :args (s/cat :text any?) :ret ::specs/json-value)
(register-fdef! h/query-json!! :args (s/cat :session any? :message any?) :ret ::specs/json-value)

(defn instrument-all! [] (stest/instrument @targets))
(defn unstrument-all! [] (stest/unstrument @targets))

(register-fdef! tool-set/valid-name? :args (s/cat :name any?) :ret boolean?)
(register-fdef! tool-set/builtin :args (s/cat :name string?) :ret string?)
(register-fdef! tool-set/mcp :args (s/cat :name string?) :ret string?)
(register-fdef! tool-set/custom :args (s/cat :name string?) :ret string?)
(register-fdef! tool-set/builtins :args (s/cat :names (s/coll-of string?)) :ret vector?)
(s/fdef instrument-all! :args (s/cat) :ret (s/nilable sequential?))
(s/fdef unstrument-all! :args (s/cat) :ret (s/nilable sequential?))

(instrument-all!)
