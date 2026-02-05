import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.ClientOptions;
import krukow.copilot_sdk.ClientOptionsBuilder;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;
import krukow.copilot_sdk.ICopilotClient;
import krukow.copilot_sdk.ICopilotSession;

/**
 * Session persistence and resume example.
 * Creates a session, captures its ID, destroys it, then resumes with new options.
 * See examples/java/README.md for build and run instructions.
 */
public class ResumeSessionExample {

    public static void main(String[] args) {
        System.out.println("=== Session Resume Example ===\n");

        ClientOptionsBuilder clientBuilder = new ClientOptionsBuilder();
        clientBuilder.logLevel("info");
        ClientOptions clientOpts = (ClientOptions) clientBuilder.build();

        ICopilotClient client = Copilot.createClient(clientOpts);
        client.start();

        try {
            // Step 1: Create initial session
            SessionOptionsBuilder sb = new SessionOptionsBuilder();
            sb.model("gpt-5.2");
            ICopilotSession session = client.createSession((SessionOptions) sb.build());

            String sessionId = session.getSessionId();
            System.out.println("Created session: " + sessionId);

            // Ask a question to establish context
            String q1 = "My favorite color is blue. Remember this.";
            System.out.println("Q1: " + q1);
            String a1 = session.sendAndWait(q1, 60000);
            System.out.println("A1: " + a1);

            // Destroy local session object (session persists on server)
            session.destroy();
            System.out.println("\nSession destroyed locally.\n");

            // Step 2: Resume with different model
            SessionOptionsBuilder rb = new SessionOptionsBuilder();
            rb.model("claude-sonnet-4");
            rb.systemMessage("append", "Be extremely concise.");
            ICopilotSession resumed = client.resumeSession(sessionId, (SessionOptions) rb.build());

            System.out.println("Resumed session: " + resumed.getSessionId());

            String q2 = "What is my favorite color?";
            System.out.println("Q2: " + q2);
            String a2 = resumed.sendAndWait(q2, 60000);
            System.out.println("A2: " + a2);

            resumed.destroy();

        } finally {
            client.stop();
        }

        System.out.println("\n=== Done ===");
        System.exit(0);
    }
}
