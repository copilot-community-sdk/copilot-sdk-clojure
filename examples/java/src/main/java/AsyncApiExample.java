import krukow.copilot_sdk.*;
import java.util.concurrent.*;

/**
 * Demonstrates the async Java API for Copilot SDK.
 * 
 * Shows two patterns:
 * 1. CompletableFuture for async single responses
 * 2. EventSubscription for streaming event access
 */
public class AsyncApiExample {

    public static void main(String[] args) throws Exception {
        ICopilotClient client = Copilot.createClient();
        client.start();

        try {
            SessionOptionsBuilder sb = new SessionOptionsBuilder();
            sb.model("gpt-5.2");
            ICopilotSession session = client.createSession((SessionOptions) sb.build());

            try {
                // Demo 1: CompletableFuture for async queries
                System.out.println("=== Async Query with CompletableFuture ===\n");
                demoCompletableFuture(session);

                // Demo 2: EventSubscription for streaming
                System.out.println("\n=== Streaming with EventSubscription ===\n");
                demoEventSubscription(session);

                // Demo 3: Multiple concurrent queries
                System.out.println("\n=== Concurrent Queries ===\n");
                demoConcurrentQueries(client);

            } finally {
                session.destroy();
            }
        } finally {
            client.stop();
        }
        // System.exit triggers shutdown hook cleanly (needed for Maven exec:java)
        System.exit(0);
    }

    /**
     * Demo 1: Use CompletableFuture for non-blocking async queries.
     */
    @SuppressWarnings("unchecked")
    static void demoCompletableFuture(ICopilotSession session) throws Exception {
        // Fire async request (cast needed due to Java generics erasure in interface)
        CompletableFuture<String> future = (CompletableFuture<String>) session.sendAsync("What is 7 * 8? Just the number.");

        System.out.println("Request sent, doing other work...");
        Thread.sleep(100); // Simulate other work

        // Wait for result with timeout
        String answer = future.get(60, TimeUnit.SECONDS);
        System.out.println("Answer: " + answer);
    }

    /**
     * Demo 2: Use EventSubscription for streaming token-by-token output.
     */
    static void demoEventSubscription(ICopilotSession session) {
        System.out.print("Streaming: ");

        // EventSubscription is AutoCloseable - use try-with-resources
        try (EventSubscription events = session.subscribeEvents()) {
            // Send prompt (returns immediately)
            session.send("Write a haiku about programming. Just the haiku, nothing else.");

            // Process events as they arrive
            Event event;
            while ((event = events.take()) != null) {
                if (event.isMessageDelta()) {
                    // Print each token as it arrives
                    System.out.print(event.getDeltaContent());
                }
                if (event.isIdle() || event.isError()) {
                    break;
                }
            }
            System.out.println();
        }
    }

    /**
     * Demo 3: Run multiple queries concurrently using CompletableFuture.
     */
    @SuppressWarnings("unchecked")
    static void demoConcurrentQueries(ICopilotClient client) throws Exception {
        // Create separate sessions for parallel queries
        SessionOptionsBuilder sb = new SessionOptionsBuilder();
        sb.model("gpt-5.2");
        SessionOptions opts = (SessionOptions) sb.build();

        ICopilotSession session1 = client.createSession(opts);
        ICopilotSession session2 = client.createSession(opts);
        ICopilotSession session3 = client.createSession(opts);

        try {
            // Fire all queries concurrently
            CompletableFuture<String> f1 = (CompletableFuture<String>) session1.sendAsync("Capital of France? One word.");
            CompletableFuture<String> f2 = (CompletableFuture<String>) session2.sendAsync("Capital of Japan? One word.");
            CompletableFuture<String> f3 = (CompletableFuture<String>) session3.sendAsync("Capital of Brazil? One word.");

            // Wait for all to complete
            CompletableFuture.allOf(f1, f2, f3).join();

            // Get results
            System.out.println("France: " + f1.get());
            System.out.println("Japan: " + f2.get());
            System.out.println("Brazil: " + f3.get());

        } finally {
            session1.destroy();
            session2.destroy();
            session3.destroy();
        }
    }
}
