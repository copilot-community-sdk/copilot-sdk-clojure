import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;
import java.util.concurrent.*;

/**
 * Parallel queries example demonstrating concurrent API usage.
 * 
 * This example shows how to run multiple independent queries in parallel
 * using Java's ExecutorService for improved throughput.
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class ParallelQueriesExample {
    
    public static void main(String[] args) {
        System.out.println("=== Parallel Queries ===\n");
        
        Object client = Copilot.createClient(null);
        Copilot.startClient(client);
        
        ExecutorService executor = Executors.newFixedThreadPool(3);
        
        try {
            // Define independent questions to ask in parallel
            String[] questions = {
                "What is the capital of France? One sentence.",
                "What is the largest planet in our solar system? One sentence.",
                "Who wrote Romeo and Juliet? One sentence.",
                "What is the speed of light in km/s? Just the number.",
                "What year did World War II end? Just the year."
            };
            
            System.out.println("Sending " + questions.length + " queries in parallel...\n");
            long startTime = System.currentTimeMillis();
            
            // Submit all queries as parallel tasks
            @SuppressWarnings("unchecked")
            Future<String>[] futures = new Future[questions.length];
            
            for (int i = 0; i < questions.length; i++) {
                final String question = questions[i];
                final int index = i;
                
                futures[i] = executor.submit(() -> {
                    // Each task creates its own session for true parallelism
                    SessionOptionsBuilder builder = new SessionOptionsBuilder();
                    builder.model("gpt-5.2");
                    Object session = Copilot.createSession(client, (SessionOptions) builder.build());
                    
                    try {
                        String answer = Copilot.sendAndWait(session, question, 30000);
                        return "Q" + (index + 1) + ": " + question + "\n   A: " + answer.trim();
                    } finally {
                        Copilot.destroySession(session);
                    }
                });
            }
            
            // Collect all results
            System.out.println("Results:");
            System.out.println("-".repeat(60));
            
            for (int i = 0; i < futures.length; i++) {
                try {
                    String result = futures[i].get(60, TimeUnit.SECONDS);
                    System.out.println(result);
                    System.out.println();
                } catch (TimeoutException e) {
                    System.out.println("Q" + (i + 1) + ": TIMEOUT");
                } catch (Exception e) {
                    System.out.println("Q" + (i + 1) + ": ERROR - " + e.getMessage());
                }
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("-".repeat(60));
            System.out.println("Completed " + questions.length + " queries in " + elapsed + "ms");
            System.out.println("(Sequential would take ~" + (questions.length * 3000) + "ms)");
            
        } finally {
            executor.shutdown();
            Copilot.stopClient(client);
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
}
