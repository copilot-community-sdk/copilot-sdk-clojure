# Azure Managed Identity with BYOK

The Copilot SDK's [BYOK mode](./byok.md) accepts static API keys, but Azure deployments often use **Managed Identity** (Entra ID) instead of long-lived keys. Since the SDK does not natively support Entra ID authentication, you can obtain a short-lived bearer token and pass it via the `:bearer-token` provider config field.

This guide shows how to use `DefaultAzureCredential` from the [Azure Identity SDK](https://learn.microsoft.com/java/api/overview/azure/identity-readme) to authenticate with Azure AI Foundry models through the Copilot SDK.

## How It Works

Azure AI Foundry's OpenAI-compatible endpoint accepts bearer tokens from Entra ID in place of static API keys. The pattern is:

1. Use `DefaultAzureCredential` to obtain a token for the `https://cognitiveservices.azure.com/.default` scope
2. Pass the token as `:bearer-token` in the BYOK provider config
3. Refresh the token before it expires (tokens are typically valid for ~1 hour)

## Clojure Example

### Prerequisites

Add the Azure Identity SDK to your `deps.edn`:

```clojure
;; deps.edn
{:deps {com.azure/azure-identity {:mvn/version "1.15.4"}
        io.github.niclasgustafsson/copilot-sdk-clojure {:mvn/version "RELEASE"}}}
```

### Basic Usage

<!-- docs-validate: skip -->
```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(import '[com.azure.identity DefaultAzureCredentialBuilder]
        '[com.azure.core.credential TokenRequestContext])

(def cognitive-services-scope "https://cognitiveservices.azure.com/.default")

(defn get-azure-token
  "Obtain a short-lived bearer token string from Entra ID."
  []
  (let [credential (.build (DefaultAzureCredentialBuilder.))
        context    (doto (TokenRequestContext.)
                     (.addScopes (into-array String [cognitive-services-scope])))]
    (-> (.getToken credential context)
        (.block)
        (.getToken))))

(def foundry-url (System/getenv "AZURE_AI_FOUNDRY_RESOURCE_URL"))

(copilot/with-client-session [session
                              {:model "gpt-4.1"
                               :provider {:provider-type :openai
                                          :base-url (str foundry-url "/openai/v1/")
                                          :bearer-token (get-azure-token)
                                          :wire-api :responses}}]
  (println (h/query "Hello from Managed Identity!" :session session)))
```

### Token Refresh for Long-Running Applications

Bearer tokens expire (typically after ~1 hour). For servers or long-running agents, refresh the token before creating each session:

<!-- docs-validate: skip -->
```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(import '[com.azure.identity DefaultAzureCredentialBuilder]
        '[com.azure.core.credential TokenRequestContext])

(def cognitive-services-scope "https://cognitiveservices.azure.com/.default")
(def credential (.build (DefaultAzureCredentialBuilder.)))
(def context (doto (TokenRequestContext.)
               (.addScopes (into-array String [cognitive-services-scope]))))

(defn fresh-provider-config
  "Build a provider config with a freshly obtained bearer token."
  [foundry-url]
  (let [token (-> (.getToken credential context)
                  (.block)
                  (.getToken))]
    {:provider-type :openai
     :base-url (str foundry-url "/openai/v1/")
     :bearer-token token
     :wire-api :responses}))

(def foundry-url (System/getenv "AZURE_AI_FOUNDRY_RESOURCE_URL"))

;; Each session gets a fresh token
(copilot/with-client [client {}]
  (dotimes [_ 3]
    (copilot/with-session [session client
                           {:model "gpt-4.1"
                            :provider (fresh-provider-config foundry-url)}]
      (println (h/query "Hello!" :session session)))))
```

## Environment Configuration

| Variable | Description | Example |
|----------|-------------|---------|
| `AZURE_AI_FOUNDRY_RESOURCE_URL` | Your Azure AI Foundry resource URL | `https://myresource.openai.azure.com` |

No API key environment variable is needed — authentication is handled by `DefaultAzureCredential`, which automatically supports:

- **Managed Identity** (system-assigned or user-assigned) — for Azure-hosted apps
- **Azure CLI** (`az login`) — for local development
- **Environment variables** (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_CLIENT_SECRET`) — for service principals
- **Workload Identity** — for Kubernetes

See the [DefaultAzureCredential documentation](https://learn.microsoft.com/java/api/com.azure.identity.defaultazurecredential) for the full credential chain.

## When to Use This Pattern

| Scenario | Recommendation |
|----------|----------------|
| Azure-hosted app with Managed Identity | ✅ Use this pattern |
| App with existing Azure AD service principal | ✅ Use this pattern |
| Local development with `az login` | ✅ Use this pattern |
| Non-Azure environment with static API key | Use [standard BYOK](./byok.md) |
| GitHub Copilot subscription available | Use [GitHub auth](./index.md#github-signed-in-user) |

## See Also

- [BYOK Setup Guide](./byok.md) — Static API key configuration
- [Authentication Overview](./index.md) — All authentication methods
- [Azure Identity documentation](https://learn.microsoft.com/java/api/overview/azure/identity-readme)
