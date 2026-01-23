import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;
import krukow.copilot_sdk.Event;
import java.util.*;

/**
 * Event handling example demonstrating how to process different event types.
 * 
 * This example shows how to handle the full event stream including:
 * - Message deltas (streaming tokens)
 * - Complete messages
 * - Tool calls (when the model wants to use tools)
 * - Session state changes
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class EventHandlingExample {
    
    public static void main(String[] args) {
        System.out.println("=== Event Handling Example ===\n");
        
        Object client = Copilot.createClient(null);
        Copilot.startClient(client);
        
        try {
            SessionOptionsBuilder builder = new SessionOptionsBuilder();
            builder.model("gpt-5.2");
            builder.streaming(true);
            
            Object session = Copilot.createSession(client, (SessionOptions) builder.build());
            
            try {
                System.out.println("Sending query and processing events...\n");
                
                // Track statistics
                final int[] stats = {0, 0, 0}; // deltas, messages, other
                final StringBuilder content = new StringBuilder();
                
                Copilot.sendStreaming(session, 
                    "Write a haiku about programming. Just the haiku, nothing else.",
                    event -> {
                        String type = event.getType();
                        
                        // Log all event types for educational purposes
                        if (type.equals("assistant.message_delta")) {
                            stats[0]++;
                            String delta = event.getDeltaContent();
                            if (delta != null) {
                                content.append(delta);
                                // Show each token as it arrives
                                System.out.print(delta);
                                System.out.flush();
                            }
                        } else if (type.equals("assistant.message")) {
                            stats[1]++;
                            System.out.println(); // newline after streaming
                        } else if (type.equals("session.idle")) {
                            // Session is idle, ready for next message
                            stats[2]++;
                        } else if (type.equals("session.error")) {
                            System.err.println("\n⚠️ Error: " + event.get("error"));
                            stats[2]++;
                        } else if (type.startsWith("tool.")) {
                            // Tool-related events
                            System.out.println("\n🔧 Tool event: " + type);
                            stats[2]++;
                        } else {
                            // Other events
                            stats[2]++;
                        }
                    });
                
                // Print statistics
                System.out.println("\n" + "-".repeat(40));
                System.out.println("Event Statistics:");
                System.out.println("  • Message deltas received: " + stats[0]);
                System.out.println("  • Complete messages: " + stats[1]);
                System.out.println("  • Other events: " + stats[2]);
                System.out.println("  • Total content length: " + content.length() + " chars");
                
            } finally {
                Copilot.destroySession(session);
            }
            
        } finally {
            Copilot.stopClient(client);
        }
        
        System.out.println("\n=== Done ===");
        System.exit(0);
    }
}
