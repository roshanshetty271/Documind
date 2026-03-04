package com.northeastern.csye7374.finalproject;

import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.QdrantService;
import com.northeastern.csye7374.finalproject.services.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Scanner;

/**
 * SimpleRAGTest - Standalone RAG pipeline test
 * 
 * Tests RAG without actors
 * Usage: mvn exec:java -Dexec.mainClass="com.northeastern.csye7374.finalproject.SimpleRAGTest"
 */
public class SimpleRAGTest {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleRAGTest.class);
    
    // Services (shared, loaded once)
    private static EmbeddingService embeddingService;
    private static QdrantService qdrantService;
    private static LLMService llmService;
    
    // Configuration
    private static final String COLLECTION_NAME = "course_documents";
    private static final int TOP_K = 3; // Only get 3 results (saves memory)
    
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       SIMPLE RAG TEST - No Actors, Just RAG Pipeline         ║");
        System.out.println("║       Perfect for 8GB RAM systems!                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        try {
            // Step 1: Initialize services
            initializeServices();
            
            // Step 2: Interactive Q&A loop
            runInteractiveQA();
            
        } catch (Exception e) {
            log.error("Error in SimpleRAGTest: {}", e.getMessage(), e);
            System.err.println("\n❌ Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup
            cleanup();
        }
    }
    
    /**
     * Initialize all services ONCE
     */
    private static void initializeServices() throws Exception {
        System.out.println("\n📦 STEP 1: Loading Services...\n");
        
        // 1a. Load Word2Vec (the big one - 3.5GB)
        System.out.println("🧠 Loading Word2Vec model (3.5GB, this takes ~2-3 minutes)...");
        long startTime = System.currentTimeMillis();
        embeddingService = new EmbeddingService();
        long loadTime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("✅ Word2Vec loaded in " + loadTime + " seconds");
        System.out.println("   Vocabulary size: " + embeddingService.getVocabularySize() + " words");
        
        // 1b. Connect to Qdrant (lightweight)
        System.out.println("\n🔌 Connecting to Qdrant (localhost:6334)...");
        qdrantService = new QdrantService();
        System.out.println("✅ Qdrant connected!");
        
        // 1c. Initialize LLM Service
        System.out.println("\n🤖 Initializing LLM Service...");
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            // Try from application.properties
            apiKey = getApiKeyFromProperties();
        }
        if (apiKey == null || apiKey.isEmpty()) {
            throw new RuntimeException("OPENAI_API_KEY not set! Set it as environment variable or in application.properties");
        }
        llmService = new LLMService(apiKey);
        System.out.println("✅ LLM Service ready!");
        
        System.out.println("\n✅ All services initialized successfully!\n");
    }
    
    /**
     * Interactive Q&A loop
     */
    private static void runInteractiveQA() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    SIMPLE RAG Q&A SYSTEM                     ║");
        System.out.println("║                                                              ║");
        System.out.println("║  Ask questions about CSYE 7374 course materials!             ║");
        System.out.println("║  Type 'exit' to quit.                                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        while (true) {
            System.out.print("\n❓ Your Question: ");
            String question = scanner.nextLine().trim();
            
            if (question.equalsIgnoreCase("exit") || question.equalsIgnoreCase("quit")) {
                System.out.println("\n👋 Goodbye!");
                break;
            }
            
            if (question.isEmpty()) {
                System.out.println("Please enter a question.");
                continue;
            }
            
            try {
                processQuestion(question);
            } catch (Exception e) {
                System.err.println("\n❌ Error processing question: " + e.getMessage());
                log.error("Error processing question: {}", e.getMessage(), e);
            }
        }
        
        scanner.close();
    }
    
    /**
     * Process a single question through the RAG pipeline
     */
    private static void processQuestion(String question) throws Exception {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("Processing: \"" + question + "\"");
        System.out.println("═".repeat(60));
        
        // Step 2: Vectorize the question
        System.out.println("\n📊 Step 2: Vectorizing question...");
        long startTime = System.currentTimeMillis();
        float[] queryVector = embeddingService.vectorize(question, null);
        long vectorTime = System.currentTimeMillis() - startTime;
        System.out.println("✅ Vectorized in " + vectorTime + "ms (dimension: " + queryVector.length + ")");
        
        // Step 3: Search Qdrant
        System.out.println("\n🔍 Step 3: Searching Qdrant for top " + TOP_K + " chunks...");
        startTime = System.currentTimeMillis();
        List<QdrantService.SearchResult> results = qdrantService.searchWithScores(COLLECTION_NAME, queryVector, TOP_K);
        long searchTime = System.currentTimeMillis() - startTime;
        System.out.println("✅ Search completed in " + searchTime + "ms, found " + results.size() + " chunks");
        
        // Display search results
        if (results.isEmpty()) {
            System.out.println("\n⚠️ No relevant chunks found! The collection may be empty.");
            System.out.println("   Run IndexingScript first to populate the database.");
            return;
        }
        
        System.out.println("\n📚 Retrieved Chunks:");
        List<String> contextChunks = new java.util.ArrayList<>();
        int i = 1;
        for (QdrantService.SearchResult result : results) {
            String snippet = result.getText();
            contextChunks.add(result.getText()); // Add to list for LLM
            if (snippet.length() > 150) {
                snippet = snippet.substring(0, 150) + "...";
            }
            System.out.printf("   [%d] Score: %.4f | %s%n", i, result.getScore(), snippet);
            i++;
        }
        
        // Step 4: Generate answer with LLM
        System.out.println("\n🤖 Step 4: Generating answer with LLM...");
        startTime = System.currentTimeMillis();
        String answer = llmService.generateAnswer(question, contextChunks); // Pass List<String>
        long llmTime = System.currentTimeMillis() - startTime;
        System.out.println("✅ Answer generated in " + llmTime + "ms");
        
        // Display the answer
        System.out.println("\n" + "─".repeat(60));
        System.out.println("💡 ANSWER:");
        System.out.println("─".repeat(60));
        System.out.println(answer);
        System.out.println("─".repeat(60));
        
        // Summary
        System.out.println("\n⏱️ Total time: " + (vectorTime + searchTime + llmTime) + "ms");
    }
    
    /**
     * Get API key from application.properties
     */
    private static String getApiKeyFromProperties() {
        try {
            java.util.Properties props = new java.util.Properties();
            java.io.InputStream is = SimpleRAGTest.class.getClassLoader()
                .getResourceAsStream("application.properties");
            if (is != null) {
                props.load(is);
                return props.getProperty("spring.ai.openai.api-key");
            }
        } catch (Exception e) {
            log.warn("Could not read application.properties: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Cleanup resources
     */
    private static void cleanup() {
        System.out.println("\n🧹 Cleaning up...");
        if (qdrantService != null) {
            qdrantService.close();
        }
        System.out.println("✅ Cleanup complete!");
    }
}

