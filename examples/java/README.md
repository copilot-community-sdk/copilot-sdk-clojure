# Java Examples

Examples demonstrating the Java API. See [README-java.md](../../README-java.md) for full API documentation.

## Setup

```bash
# From project root
clj -T:build aot-jar
clj -T:build install

# Compile examples
cd examples/java
mvn compile
```

## Running Examples

```bash
mvn exec:java              # Basic query
mvn exec:java -Pstreaming  # Streaming output
mvn exec:java -Pconversation # Multi-turn conversation
mvn exec:java -Pasync      # Async API (CompletableFuture + EventSubscription)
mvn exec:java -Ptools      # Custom tools
mvn exec:java -Ppermission # Permission handling
mvn exec:java -Pmulti-agent # Multi-agent collaboration
mvn exec:java -Pparallel   # Concurrent queries
mvn exec:java -Pevents     # Event handling patterns
mvn exec:java -Pchat       # Interactive chat
```

## Examples

| Example | Description |
|---------|-------------|
| `JavaExample` | Basic query API |
| `StreamingJavaExample` | Real-time streaming |
| `ConversationJavaExample` | Multi-turn conversations |
| `AsyncApiExample` | CompletableFuture and EventSubscription |
| `ToolIntegrationExample` | Custom tools |
| `PermissionBashExample` | Permission handling |
| `MultiAgentExample` | Multiple agents |
| `ParallelQueriesExample` | Concurrent queries |
| `EventHandlingExample` | Event processing patterns |
| `InteractiveChatExample` | Interactive chat |
