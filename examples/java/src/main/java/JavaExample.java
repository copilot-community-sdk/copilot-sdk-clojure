import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;

/**
 * Basic Java example demonstrating the Copilot SDK.
 * See examples/java/README.md for build and run instructions.
 */
public class JavaExample {

    public static void main(String[] args) {
        System.out.println("=== Simple Query ===\n");

        // Basic query - returns just the answer string
        String answer = Copilot.query("What is 2+2? Just the number.");
        System.out.println("Q: What is 2+2?");
        System.out.println("A: " + answer);

        // Query with session options using builder
        SessionOptionsBuilder builder = new SessionOptionsBuilder();
        builder.model("gpt-5.2");
        SessionOptions opts = (SessionOptions) builder.build();

        String answer2 = Copilot.query("What is the capital of Japan? One word.", opts);
        System.out.println("\nQ: What is the capital of Japan?");
        System.out.println("A: " + answer2);

        // Query with timeout
        String answer3 = Copilot.query(
            "Explain immutability in one sentence.",
            opts,
            30000  // 30 second timeout
        );
        System.out.println("\nQ: Explain immutability in one sentence.");
        System.out.println("A: " + answer3);

        System.out.println("\n=== Done ===");
        // System.exit triggers shutdown hook cleanly (needed for Maven exec:java)
        System.exit(0);
    }
}
