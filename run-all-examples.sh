#!/bin/bash
# Run all examples using -X invocation

set -e

echo "=== basic-chat ==="
clojure -A:examples -X basic-chat/run

echo ""
echo "=== helpers-query ==="
clojure -A:examples -X helpers-query/run

echo ""
echo "=== helpers-query/run-multi ==="
clojure -A:examples -X helpers-query/run-multi

echo ""
echo "=== tool-integration ==="
clojure -A:examples -X tool-integration/run

echo ""
echo "=== multi-agent ==="
clojure -A:examples -X multi-agent/run

echo ""
echo "=== config-skill-output ==="
clojure -A:examples -X config-skill-output/run

echo ""
echo "=== metadata-api ==="
clojure -A:examples -X metadata-api/run

echo ""
echo "=== permission-bash ==="
clojure -A:examples -X permission-bash/run

echo ""
echo "=== session-events ==="
clojure -A:examples -X session-events/run

echo ""
echo "=== user-input ==="
clojure -A:examples -X user-input/run-simple

echo ""
echo "=== file-attachments ==="
clojure -A:examples -X file-attachments/run

echo ""
echo "=== session-resume ==="
clojure -A:examples -X session-resume/run

echo ""
echo "=== infinite-sessions ==="
clojure -A:examples -X infinite-sessions/run

echo ""
echo "=== lifecycle-hooks ==="
clojure -A:examples -X lifecycle-hooks/run

echo ""
echo "=== reasoning-effort ==="
clojure -A:examples -X reasoning-effort/run

echo ""
echo "=== elicitation-provider ==="
clojure -A:examples -X elicitation-provider/run

echo ""
echo "=== commands ==="
clojure -A:examples -X commands/run

echo ""
echo "=== ask-user-failure ==="
clojure -A:examples -X ask-user-failure/run

echo ""
echo "=== manual-tool-resume ==="
clojure -A:examples -X manual-tool-resume/run

# Intentionally excluded — these require external credentials or network setup
# and cannot run unattended in CI:
#   byok_provider     needs a provider API key (OPENAI_API_KEY / ANTHROPIC_API_KEY / ...)
#   empty_mode        :empty mode disables the local keychain, so it also needs a provider key
#   mcp_local_server  needs npx (Node.js) + network to download @modelcontextprotocol/server-filesystem
# Run them manually after providing the relevant key / tooling. See examples/README.md.
