import krukow.copilot_sdk.Copilot;
import krukow.copilot_sdk.SessionOptions;
import krukow.copilot_sdk.SessionOptionsBuilder;
import krukow.copilot_sdk.ICopilotClient;
import krukow.copilot_sdk.ICopilotSession;
import krukow.copilot_sdk.Tool;
import krukow.copilot_sdk.IToolHandler;
import java.util.*;

/**
 * Tool integration example demonstrating custom tools that the LLM can invoke.
 * 
 * This example creates a "lookup_language" tool that returns information about
 * programming languages from a knowledge base. The LLM will use this tool
 * when asked about programming languages.
 * 
 * See examples/java/README.md for build and run instructions.
 */
public class ToolIntegrationExample {
    
    // Knowledge base for our lookup tool
    private static final Map<String, String> KNOWLEDGE_BASE = Map.of(
        "clojure", "Clojure is a dynamic, functional programming language that runs on the JVM. Created by Rich Hickey in 2007. It emphasizes immutability and functional programming.",
        "rust", "Rust is a systems programming language focused on safety, speed, and concurrency. Created by Mozilla. Known for its ownership model and zero-cost abstractions.",
        "python", "Python is a high-level, interpreted programming language known for its readability. Created by Guido van Rossum in 1991. Popular for data science and web development.",
        "javascript", "JavaScript is a dynamic scripting language primarily used for web development. Created by Brendan Eich in 1995. The language of the web browser.",
        "haskell", "Haskell is a purely functional programming language. Named after Haskell Curry. Known for its strong static typing and lazy evaluation."
    );
    
    public static void main(String[] args) {
        System.out.println("=== Tool Integration Example ===\n");
        
        ICopilotClient client = Copilot.createClient(null);
        client.start();
        
        try {
            // Define the lookup tool with a handler
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", Map.of(
                "language", Map.of(
                    "type", "string",
                    "description", "The programming language to look up (e.g., 'clojure', 'rust', 'python')"
                )
            ));
            parameters.put("required", List.of("language"));
            
            Tool lookupTool = new Tool(
                "lookup_language",
                "Look up information about a programming language from our knowledge base. " +
                "Available languages: clojure, rust, python, javascript, haskell.",
                parameters,
                (toolArgs, invocation) -> {
                    String language = ((String) toolArgs.get("language")).toLowerCase();
                    String info = KNOWLEDGE_BASE.get(language);
                    
                    System.out.println("  [Tool called: lookup_language(\"" + language + "\")]");
                    
                    if (info != null) {
                        return Tool.success(info);
                    } else {
                        return Tool.failure(
                            "No information found for language: " + language + 
                            ". Available: clojure, rust, python, javascript, haskell",
                            "language not in knowledge base"
                        );
                    }
                }
            );
            
            // Create session with the tool
            SessionOptionsBuilder builder = new SessionOptionsBuilder();
            builder.model("gpt-5.2");
            builder.tool(lookupTool);
            
            ICopilotSession session = client.createSession((SessionOptions) builder.build());
            
            try {
                // Ask about several programming languages
                String[] languages = {"Clojure", "Python", "Rust"};
                
                for (String lang : languages) {
                    System.out.println("Looking up: " + lang);
                    String prompt = "What is " + lang + "? Use the lookup_language tool to find out.";
                    String response = session.sendAndWait(prompt, 60000);
                    System.out.println("Response: " + response);
                    System.out.println();
                }
                
            } finally {
                session.destroy();
            }
            
        } finally {
            client.stop();
        }
        
        System.out.println("=== Done ===");
        System.exit(0);
    }
}
