# BYOK (Bring Your Own Key)

BYOK allows you to use the Copilot SDK with your own API keys from model providers, bypassing GitHub Copilot authentication. This is useful for enterprise deployments, custom model hosting, or when you want direct billing with your model provider.

## Supported Providers

| Provider | `:provider-type` | Notes |
|----------|------------------|-------|
| OpenAI | `:openai` | OpenAI API and OpenAI-compatible endpoints |
| Azure OpenAI / Azure AI Foundry | `:azure` | Azure-hosted models |
| Anthropic | `:anthropic` | Claude models |
| Ollama | `:openai` | Local models via OpenAI-compatible API |
| Microsoft Foundry Local | `:openai` | Run AI models locally on your device via OpenAI-compatible API |
| Other OpenAI-compatible | `:openai` | vLLM, LiteLLM, etc. |

## Quick Start: Azure AI Foundry

```clojure
(require '[github.copilot-sdk :as copilot])
(require '[github.copilot-sdk.helpers :as h])

(copilot/with-client-session [session
                              {:model "gpt-5.2-codex"
                               :provider {:provider-type :openai
                                          :base-url "https://your-resource.openai.azure.com/openai/v1/"
                                          :wire-api :responses
                                          :api-key (System/getenv "FOUNDRY_API_KEY")}}]
  (println (h/query "What is 2+2?" :session session)))
```

## Quick Start: OpenAI Direct

```clojure
(copilot/with-client-session [session
                              {:model "gpt-5.2"
                               :provider {:provider-type :openai
                                          :base-url "https://api.openai.com/v1"
                                          :api-key (System/getenv "OPENAI_API_KEY")}}]
  (println (h/query "Hello!" :session session)))
```

## Quick Start: Ollama (Local)

```clojure
;; No API key needed for local Ollama
(copilot/with-client-session [session
                              {:model "llama3"
                               :provider {:provider-type :openai
                                          :base-url "http://localhost:11434/v1"}}]
  (println (h/query "Hello!" :session session)))
```

## Quick Start: Microsoft Foundry Local

[Microsoft Foundry Local](https://foundrylocal.ai) lets you run AI models locally on your own device with an OpenAI-compatible API. No API key is needed.

> **Note:** Foundry Local starts on a **dynamic port** — the port is not fixed. Use `foundry service status` to confirm the port the service is currently listening on, then use that port in your `:base-url`.

```clojure
;; No API key needed for local Foundry Local
;; Replace <PORT> with the port from: foundry service status
(copilot/with-client-session [session
                              {:model "phi-4-mini"
                               :provider {:provider-type :openai
                                          :base-url "http://localhost:<PORT>/v1"}}]
  (println (h/query "Hello!" :session session)))
```

To get started with Foundry Local:

```bash
# Windows: Install Foundry Local CLI (requires winget)
winget install Microsoft.FoundryLocal

# macOS / Linux: see https://foundrylocal.ai for installation instructions

# Run a model (starts the local server automatically)
foundry model run phi-4-mini

# Check the port the service is running on
foundry service status
```

## Quick Start: Anthropic

```clojure
(copilot/with-client-session [session
                              {:model "claude-sonnet-4"
                               :provider {:provider-type :anthropic
                                          :base-url "https://api.anthropic.com"
                                          :api-key (System/getenv "ANTHROPIC_API_KEY")}}]
  (println (h/query "Hello!" :session session)))
```

## Provider Configuration Reference

### `:provider` Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `:base-url` | string | **Yes** | API endpoint URL |
| `:provider-type` | keyword | No | `:openai`, `:azure`, or `:anthropic` (default: `:openai`) |
| `:wire-api` | keyword | No | `:completions` or `:responses` (default: `:completions`) |
| `:api-key` | string | No | API key (optional for local providers like Ollama) |
| `:bearer-token` | string | No | Bearer token auth (takes precedence over `:api-key`) |
| `:azure-options` | map | No | Azure-specific options (see below) |

### Azure Options

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `:azure-api-version` | string | `"2024-10-21"` | Azure API version |

### Wire API Format

The `:wire-api` setting determines which OpenAI API format to use:

- **`:completions`** (default) — Chat Completions API (`/chat/completions`). Use for most models.
- **`:responses`** — Responses API. Use for GPT-5 series models that support the newer responses format.

### Provider-Type Notes

**`:openai`** — Works with OpenAI API and any OpenAI-compatible endpoint. `:base-url` should include the full path (e.g., `"https://api.openai.com/v1"`).

**`:azure`** — For native Azure OpenAI endpoints. `:base-url` should be just the host (e.g., `"https://my-resource.openai.azure.com"`). Do NOT include `/openai/v1/` in the URL — the SDK handles path construction.

**`:anthropic`** — For direct Anthropic API access. Uses Claude-specific API format.

## Example Configurations

### Azure OpenAI (Native Azure Endpoint)

Use `:azure` type for endpoints at `*.openai.azure.com`:

```clojure
{:model "gpt-5.2"
 :provider {:provider-type :azure
            :base-url "https://my-resource.openai.azure.com"
            :api-key (System/getenv "AZURE_OPENAI_KEY")
            :azure-options {:azure-api-version "2024-10-21"}}}
```

### Azure AI Foundry (OpenAI-Compatible Endpoint)

For Azure AI Foundry deployments with `/openai/v1/` endpoints, use `:openai`:

```clojure
{:model "gpt-5.2-codex"
 :provider {:provider-type :openai
            :base-url "https://your-resource.openai.azure.com/openai/v1/"
            :api-key (System/getenv "FOUNDRY_API_KEY")
            :wire-api :responses}}
```

### Bearer Token Authentication

Some providers require bearer token authentication instead of API keys:

```clojure
{:model "my-model"
 :provider {:provider-type :openai
            :base-url "https://my-custom-endpoint.example.com/v1"
            :bearer-token (System/getenv "MY_BEARER_TOKEN")}}
```

## Limitations

### Identity Limitations

BYOK authentication uses **static credentials that you supply** (API keys or bearer tokens); it does not natively perform Entra ID, OIDC, or managed identity flows. However, you can use `DefaultAzureCredential` to obtain a short-lived bearer token and pass it via `:bearer-token`. See the [Azure Managed Identity workaround](./azure-managed-identity.md) for details.

The following identity flows are NOT natively supported (you must handle them yourself and pass the resulting credential to BYOK):

- ❌ Microsoft Entra ID (Azure AD) managed identities or service principals
- ❌ Third-party identity providers (OIDC, SAML, etc.)

You must provide and manage the API key or bearer token that BYOK uses.

### Feature Limitations

- **Model availability** — Only models supported by your provider
- **Rate limiting** — Subject to your provider's rate limits, not Copilot's
- **Usage tracking** — Tracked by your provider, not GitHub Copilot
- **Premium requests** — Do not count against Copilot premium request quotas

## Custom Model Listing

When using BYOK, the CLI server may not know which models your provider supports. Use `:on-list-models` in your client options to supply a custom model list:

```clojure
(require '[github.copilot-sdk :as copilot])

(def my-models
  [{:id "my-gpt-4o"
    :name "My GPT-4o"
    :vendor "openai"
    :family "gpt-4o"
    :version ""
    :max-input-tokens 128000
    :max-output-tokens 16384
    :preview? false
    :default-temperature 1
    :model-picker-priority 1
    :model-capabilities {:model-supports {} :model-limits {}}}])

(def client
  (copilot/client {:on-list-models (fn [] my-models)}))

;; list-models returns my-models (no CLI connection required)
(copilot/list-models client)
;; => [{:id "my-gpt-4o" ...}]
```

The handler is a zero-arg function returning a seq of model info maps in the same format that `list-models` returns. Results are cached after the first call.

## Troubleshooting

### "Model not specified" Error

When using BYOK, the `:model` parameter is **required**:

```clojure
;; ❌ Error: Model required with custom provider
{:provider {:provider-type :openai :base-url "..."}}

;; ✅ Correct: Model specified
{:model "gpt-5.2"
 :provider {:provider-type :openai :base-url "..."}}
```

### Azure Endpoint Type Confusion

For Azure OpenAI endpoints (`*.openai.azure.com`), use the correct type:

```clojure
;; ❌ Wrong: Using :openai type with native Azure endpoint
{:provider {:provider-type :openai
            :base-url "https://my-resource.openai.azure.com"}}

;; ✅ Correct: Using :azure type
{:provider {:provider-type :azure
            :base-url "https://my-resource.openai.azure.com"}}
```

However, if your Azure AI Foundry deployment provides an OpenAI-compatible endpoint path (e.g., `/openai/v1/`), use `:openai`:

```clojure
;; ✅ Correct: OpenAI-compatible Azure AI Foundry endpoint
{:provider {:provider-type :openai
            :base-url "https://your-resource.openai.azure.com/openai/v1/"}}
```

### Connection Refused (Foundry Local)

Foundry Local uses a dynamic port that may change between restarts. Confirm the active port:

```bash
# Check the service status and port
foundry service status
```

Update your `:base-url` to match the port shown in the output. If the service is not running, start a model to launch it:

```bash
foundry model run phi-4-mini
```

### Connection Refused (Ollama)

Ensure Ollama is running and accessible:

```bash
# Check Ollama is running
curl http://localhost:11434/v1/models

# Start Ollama if not running
ollama serve
```

### Authentication Failed

1. Verify your API key is correct and not expired
2. Check the `:base-url` matches your provider's expected format
3. For bearer tokens, ensure the full token is provided (not just a prefix)

## Next Steps

- [Authentication Overview](./index.md) — All authentication methods
- [Getting Started Guide](../getting-started.md) — Build your first Copilot-powered app
