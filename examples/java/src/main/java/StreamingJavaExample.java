import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;

/**
 * Streaming Java example demonstrating real-time token output.
 * See examples/java/README.md for build and run instructions.
 */
public class StreamingJavaExample {
    
    public static void main(String[] args) {
        System.out.println("=== Streaming Query ===\n");
        
        SessionOptionsBuilder builder = new SessionOptionsBuilder();
        builder.model("gpt-5.2");
        builder.streaming(true);
        SessionOptions opts = (SessionOptions) builder.build();
        
        System.out.println("Q: Tell me a short joke.\n");
        System.out.print("A: ");
        
        // Stream tokens as they arrive
        Copilot.queryStreaming("Tell me a short joke.", opts, event -> {
            if (event.isMessageDelta()) {
                // Print each token as it arrives
                System.out.print(event.getDeltaContent());
                System.out.flush();
            } else if (event.isMessage()) {
                // Final newline after complete message
                System.out.println();
            }
        });
        
        System.out.println("\n=== Done ===");
        // System.exit triggers shutdown hook cleanly (needed for Maven exec:java)
        System.exit(0);
    }
}
