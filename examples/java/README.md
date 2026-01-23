# Java Examples for Copilot SDK

This directory contains Java examples demonstrating how to use the Copilot SDK from Java.

## Quick Start

### Option 1: Using Maven Central (Recommended)

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.krukow</groupId>
    <artifactId>copilot-sdk</artifactId>
    <version>0.1.0</version>
</dependency>

<!-- Required Clojure runtime dependencies -->
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>clojure</artifactId>
    <version>1.12.4</version>
</dependency>
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>core.async</artifactId>
    <version>1.8.741</version>
</dependency>
<dependency>
    <groupId>cheshire</groupId>
    <artifactId>cheshire</artifactId>
    <version>6.1.0</version>
</dependency>
```

You'll also need the Clojars repository for cheshire:

```xml
<repositories>
    <repository>
        <id>clojars</id>
        <url>https://repo.clojars.org</url>
    </repository>
</repositories>
```

See the complete `pom.xml` in this directory for all dependencies.

### Option 2: Local Development

Build and install from source:

```bash
# From project root
clj -T:build aot-jar
clj -T:build install

# Then use in your Maven project
cd examples/java
mvn compile
```

## Prerequisites

- **Java 17+**
- **Maven 3.6+**
- **GitHub Copilot CLI** installed and authenticated

## Running Examples

```bash
cd examples/java

# Basic query example
mvn exec:java

# Streaming example  
mvn exec:java -Pstreaming

# Multi-turn conversation
mvn exec:java -Pconversation

# Multi-agent collaboration
mvn exec:java -Pmulti-agent

# Parallel queries
mvn exec:java -Pparallel

# Event handling
mvn exec:java -Pevents

# Interactive chat
mvn exec:java -Pchat
```

## Example Overview

| Example | Description |
|---------|-------------|
| `JavaExample.java` | Basic query API - simple question/answer |
| `StreamingJavaExample.java` | Streaming API - real-time token output |
| `ConversationJavaExample.java` | Full client/session API - multi-turn conversations |
| `MultiAgentExample.java` | Multi-agent collaboration with different system prompts |
| `ParallelQueriesExample.java` | Concurrent queries using Java ExecutorService |
| `EventHandlingExample.java` | Processing different event types from streaming |
| `InteractiveChatExample.java` | Multi-turn streaming chat conversation |

## API Usage

### Simple Query (Recommended)

```java
import krukow.copilot_sdk.Copilot;

// One-liner query
String answer = Copilot.query("What is 2+2?");
System.out.println(answer); // "4"
```

### Query with Options

```java
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;

SessionOptionsBuilder builder = new SessionOptionsBuilder();
builder.model("gpt-5.2");
SessionOptions opts = (SessionOptions) builder.build();

String answer = Copilot.query("Explain monads", opts);
```

### Streaming Query

```java
SessionOptionsBuilder builder = new SessionOptionsBuilder();
builder.streaming(true);
SessionOptions opts = (SessionOptions) builder.build();

Copilot.queryStreaming("Tell me a story", opts, event -> {
    if (event.isMessageDelta()) {
        System.out.print(event.getDeltaContent());
    }
});
```

### Full Client/Session API

For multi-turn conversations with context:

```java
import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.ClientOptionsBuilder;
import krukow.copilot_sdk.SessionOptionsBuilder;

// Create client
Object client = Copilot.createClient(null);
Copilot.startClient(client);

try {
    // Create session
    SessionOptionsBuilder sb = new SessionOptionsBuilder();
    sb.model("gpt-5.2");
    Object session = Copilot.createSession(client, (SessionOptions) sb.build());
    
    try {
        // Context preserved between calls
        String a1 = Copilot.sendAndWait(session, "What is the capital of France?", 60000);
        String a2 = Copilot.sendAndWait(session, "What is its population?", 60000);
    } finally {
        Copilot.destroySession(session);
    }
} finally {
    Copilot.stopClient(client);
}
```

## Alternative: Standalone Uberjar

For deployment without Maven dependency management:

```bash
# From project root - build uberjar with all dependencies
clj -T:build uber

# Compile your Java code
javac -cp target/io.github.krukow/copilot-sdk-0.1.0-SNAPSHOT-standalone.jar \
      MyApp.java

# Run
java -cp "target/io.github.krukow/copilot-sdk-0.1.0-SNAPSHOT-standalone.jar:." \
     MyApp
```

## Notes

- JVM shutdown hook automatically cleans up resources
- Builder methods return `Object`; use separate statements instead of chaining
- The SDK requires Clojure runtime (included transitively via Maven)
