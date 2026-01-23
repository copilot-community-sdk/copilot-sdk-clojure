# Java Async API Design

This document describes the async API extensions for Java users.

## Overview

The Java API provides two async patterns:

1. **`CompletableFuture<String> sendAsync(prompt)`** - For async single-response queries
2. **`EventSubscription subscribeEvents()`** - For streaming event access

## API Reference

### ICopilotSession Async Methods

```java
// Send prompt and get response asynchronously
CompletableFuture<String> sendAsync(String prompt);

// Subscribe to raw session events
EventSubscription subscribeEvents();
```

### EventSubscription

An `AutoCloseable` handle for receiving session events:

```java
public class EventSubscription implements AutoCloseable {
    // Block until next event (null if session destroyed)
    Event take() throws InterruptedException;
    
    // Non-blocking poll (null if no event available)
    Event poll();
    
    // Poll with timeout (null if timeout expires)
    Event poll(long timeout, TimeUnit unit) throws InterruptedException;
    
    // Unsubscribe and release resources
    void close();
}
```

## Usage Examples

### Async Query with CompletableFuture

```java
ICopilotClient client = Copilot.createClient();
client.start();

try {
    ICopilotSession session = client.createSession(opts);
    
    // Fire off async request
    CompletableFuture<String> future = session.sendAsync("What is 2+2?");
    
    // Do other work while waiting...
    
    // Get result (blocks if not ready)
    String answer = future.get(60, TimeUnit.SECONDS);
    System.out.println(answer);
    
    session.destroy();
} finally {
    client.stop();
}
```

### Composing Multiple Async Queries

```java
CompletableFuture<String> q1 = session1.sendAsync("Question 1");
CompletableFuture<String> q2 = session2.sendAsync("Question 2");

// Wait for both
CompletableFuture.allOf(q1, q2).join();
System.out.println("A1: " + q1.get());
System.out.println("A2: " + q2.get());
```

### Async with Callbacks

```java
session.sendAsync("Tell me a joke")
    .thenAccept(joke -> System.out.println("Got joke: " + joke))
    .exceptionally(ex -> {
        System.err.println("Error: " + ex.getMessage());
        return null;
    });
```

### Streaming with EventSubscription

```java
try (EventSubscription events = session.subscribeEvents()) {
    // Send prompt (non-blocking)
    session.send("Write a haiku about Java");
    
    // Process events as they arrive
    Event event;
    while ((event = events.take()) != null) {
        if (event.isMessageDelta()) {
            System.out.print(event.getDeltaContent());
        }
        if (event.isIdle() || event.isError()) {
            break;
        }
    }
    System.out.println();
}
```

### Polling Pattern (Non-blocking)

```java
try (EventSubscription events = session.subscribeEvents()) {
    session.send("Complex question");
    
    while (true) {
        // Poll with 100ms timeout
        Event event = events.poll(100, TimeUnit.MILLISECONDS);
        
        if (event == null) {
            // No event yet, do other work
            continue;
        }
        
        handleEvent(event);
        
        if (event.isIdle() || event.isError()) {
            break;
        }
    }
}
```

## Design Rationale

### Why CompletableFuture?

- Standard Java async primitive (since Java 8)
- Rich composition API (`thenApply`, `thenCompose`, `allOf`, etc.)
- Works with virtual threads (Java 21+)
- No external dependencies

### Why EventSubscription over Flow.Publisher?

- Simpler mental model for most use cases
- No need to understand reactive streams backpressure
- Natural fit with try-with-resources
- Easier to debug and reason about

### Why AutoCloseable?

- Automatic cleanup with try-with-resources
- Prevents resource leaks
- Familiar Java pattern

## Java 21+ Virtual Threads

With virtual threads, you can also use blocking APIs in a scalable way:

```java
// Each virtual thread is lightweight
Thread.startVirtualThread(() -> {
    String response = session.sendAndWait("Question", 60000);
    System.out.println(response);
});
```

This is a valid alternative to `CompletableFuture` for simple async scenarios.
