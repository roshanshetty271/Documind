package com.northeastern.csye7374.finalproject;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.SupervisorStrategy;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.typed.Cluster;
import java.time.Duration;
import com.northeastern.csye7374.finalproject.actors.LLMActor;
import com.northeastern.csye7374.finalproject.actors.LoggingActor;
import com.northeastern.csye7374.finalproject.actors.OrchestratorActor;
import com.northeastern.csye7374.finalproject.actors.SearchWorkerActor;
import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.LLMService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;


/**
 * ClusterNode - Main entry point for Akka cluster
 * 
 * Spawns per node:
 * - 4x SearchWorkerActor
 * - 1x LLMActor
 * - 1x LoggingActor
 * - 1x OrchestratorActor
 * 
 * Usage: mvn exec:java -Dexec.args="2551"
 */
public class ClusterNode {
    
    private static final Logger log = LoggerFactory.getLogger(ClusterNode.class);
    
    // Store the last question for display in response
    private static volatile String lastQuestion = "";
    
    /**
     * Main entry point
     * 
     * @param args Command line arguments (port number)
     */
    public static void main(String[] args) {
        // Get port from args or default to 2551
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 2551;
        
        log.info("Starting ClusterNode on port {}", port);
        
        // Start the cluster node
        startNode(port);
    }
    
    /**
     * Root behavior that spawns all actors and provides reference for CLI
     * 
     * SPAWNS PER NODE:
     * - 4x SearchWorkerActor (with supervision)
     * - 1x LLMActor (with supervision)
     * - 1x LoggingActor
     * - 1x OrchestratorActor (with supervision)
     */
    static class RootBehavior {
        public interface Command {}
        
        public static final class GetOrchestrator implements Command {
            public final ActorRef<ActorRef<OrchestratorActor.Command>> replyTo;
            
            public GetOrchestrator(ActorRef<ActorRef<OrchestratorActor.Command>> replyTo) {
                this.replyTo = replyTo;
            }
        }
        
        public static final class ProcessUserQuery implements Command {
            public final String query;
            
            public ProcessUserQuery(String query) {
                this.query = query;
            }
        }
        
        public static Behavior<Command> create(int port) {
            return Behaviors.setup(context -> {
                // Get cluster reference
                Cluster cluster = Cluster.get(context.getSystem());
                String nodeId = "Node-" + port;
                
                log.info("---");
                log.info("[CLUSTER] Starting node: {}", nodeId);
                log.info("[CLUSTER] Address: {}", cluster.selfMember().address());
                log.info("[CLUSTER] Roles: {}", cluster.selfMember().getRoles());
                log.info("---");
                
                System.out.println();
                System.out.println("---");
                System.out.println("[CLUSTER] Starting " + nodeId);
                System.out.println("---");
                
                // ---═══
                // Initialize Services
                // ---═══
                
                // Initialize LLM Service
                String apiKey = System.getenv("OPENAI_API_KEY");
                if (apiKey == null || apiKey.isEmpty()) {
                    apiKey = "your-api-key-here"; // TODO: Set real API key
                    log.warn("OPENAI_API_KEY not set! Using placeholder.");
                }
                LLMService llmService = new LLMService(apiKey);
                log.info("🔧 [CLUSTER] LLM Service initialized");
                System.out.println("🔧 [CLUSTER] LLM Service initialized");
                
                // Initialize shared EmbeddingService (Word2Vec model is ~3.5GB, share across workers!)
                log.info("🔧 [CLUSTER] Loading Word2Vec embedding model (this may take 30-60 seconds)...");
                System.out.println("🔧 [CLUSTER] Loading Word2Vec model (30-60 seconds)...");
                EmbeddingService embeddingService = new EmbeddingService();
                log.info("🔧 [CLUSTER] EmbeddingService initialized - Vocabulary size: {}", 
                    embeddingService.getVocabularySize());
                System.out.println("🔧 [CLUSTER] EmbeddingService ready - vocab: " + embeddingService.getVocabularySize());
                
                // ---═══
                // SPAWN ACTORS - This demonstrates 2-3 service-specific actors per node
                // ---═══
                
                System.out.println();
                System.out.println("---");
                System.out.println("🎭 [CLUSTER] Spawning actors for " + nodeId + "...");
                System.out.println("---");
                
                // ─────────────────────────────────────────────────────────────────
                // 1. LOGGING ACTOR (1 per node)
                // Purpose: Demonstrates TELL pattern (fire-and-forget)
                // All other actors send logs here, LoggingActor NEVER replies
                // ─────────────────────────────────────────────────────────────────
                ActorRef<LoggingActor.Command> loggingActor = context.spawn(
                    LoggingActor.create(nodeId),
                    "logging-actor"
                );
                log.info("[CLUSTER] [{}] Spawned LoggingActor (TELL pattern demo)", nodeId);
                System.out.println("[CLUSTER] Spawned LoggingActor (TELL pattern - fire-and-forget)");
                
                // ─────────────────────────────────────────────────────────────────
                // 2. LLM ACTOR (1 per node)
                // Purpose: Wraps LLMService, demonstrates ASK/FORWARD patterns
                // Receives from OrchestratorActor, replies to original sender
                // ─────────────────────────────────────────────────────────────────
                Behavior<LLMActor.Command> supervisedLLMActor = Behaviors.supervise(
                    LLMActor.create(llmService, nodeId)
                ).onFailure(Exception.class, SupervisorStrategy.restart());
                
                ActorRef<LLMActor.Command> llmActor = context.spawn(
                    supervisedLLMActor,
                    "llm-actor"
                );
                log.info("[CLUSTER] [{}] Spawned LLMActor with SUPERVISION (FORWARD pattern demo)", nodeId);
                System.out.println("[CLUSTER] Spawned LLMActor (FORWARD pattern - handles LLM calls)");
                
                // ─────────────────────────────────────────────────────────────────
                // 3. SEARCH WORKER ACTORS (4 per node)
                // Purpose: Vector search workers, demonstrates round-robin load balancing
                // Registered with Receptionist for cluster-wide discovery
                // ─────────────────────────────────────────────────────────────────
                int numWorkers = 4;
                
                for (int i = 0; i < numWorkers; i++) {
                    // Supervised behavior: restart worker on any exception with backoff
                    Behavior<SearchWorkerActor.Command> supervisedWorker = Behaviors.supervise(
                        SearchWorkerActor.create("course_documents", embeddingService)
                    ).onFailure(
                        Exception.class, 
                        SupervisorStrategy.restartWithBackoff(
                            Duration.ofMillis(200),   // min backoff
                            Duration.ofSeconds(5),    // max backoff
                            0.2                       // random factor
                        )
                    );
                    
                    context.spawn(supervisedWorker, "searchWorker-" + i);
                    log.info("🔍 [CLUSTER] [{}] Spawned SearchWorker-{} with SUPERVISION", nodeId, i);
                }
                System.out.println("🔍 [CLUSTER] Spawned " + numWorkers + " SearchWorkerActors (TELL pattern - search)");
                
                // ─────────────────────────────────────────────────────────────────
                // 4. ORCHESTRATOR ACTOR (1 per node)
                // Purpose: Main coordinator, demonstrates ALL THREE patterns:
                //   - TELL: to LoggingActor (fire-and-forget)
                //   - ASK: receives from CLI/REST (request-response via adapter)
                //   - FORWARD: to LLMActor (preserves replyTo)
                // ─────────────────────────────────────────────────────────────────
                Behavior<OrchestratorActor.Command> supervisedOrchestrator = Behaviors.supervise(
                    OrchestratorActor.create(nodeId)
                ).onFailure(Exception.class, SupervisorStrategy.restart());
                
                ActorRef<OrchestratorActor.Command> orchestrator = context.spawn(
                    supervisedOrchestrator,
                    "orchestrator"
                );
                log.info("📨 [CLUSTER] [{}] Spawned OrchestratorActor with SUPERVISION", nodeId);
                System.out.println("📨 [CLUSTER] Spawned OrchestratorActor (coordinates all patterns)");
                
                // ---═══
                // Summary
                // ---═══
                System.out.println();
                System.out.println("---");
                System.out.println("✅ [CLUSTER] " + nodeId + " READY!");
                System.out.println("---");
                System.out.println("   Actors spawned on this node:");
                System.out.println("   • 1x OrchestratorActor (coordinates RAG pipeline)");
                System.out.println("   • " + numWorkers + "x SearchWorkerActor (vector search)");
                System.out.println("   • 1x LLMActor (LLM communication)");
                System.out.println("   • 1x LoggingActor (centralized logging)");
                System.out.println();
                System.out.println("   Communication patterns demonstrated:");
                System.out.println("   • TELL (fire-and-forget): → LoggingActor");
                System.out.println("   • ASK (request-response): CLI/REST → OrchestratorActor");
                System.out.println("   • FORWARD (preserve sender): → LLMActor (replyTo chain)");
                System.out.println("---");
                System.out.println();
                
                log.info("---");
                log.info("✅ [CLUSTER] {} Ready on port {}", nodeId, port);
                log.info("---");
                
                // Return behavior that handles messages
                return Behaviors.receiveMessage(msg -> {
                    if (msg instanceof GetOrchestrator) {
                        GetOrchestrator getOrch = (GetOrchestrator) msg;
                        getOrch.replyTo.tell(orchestrator);
                    } else if (msg instanceof ProcessUserQuery) {
                        ProcessUserQuery query = (ProcessUserQuery) msg;
                        
                        // Create response handler that prints beautiful output
                        ActorRef<OrchestratorActor.QueryResult> responseHandler = 
                            context.spawn(
                                Behaviors.receiveMessage((OrchestratorActor.QueryResult result) -> {
                                    printBeautifulResponse(result);
                                    return Behaviors.stopped();
                                }),
                                "responseHandler-" + System.currentTimeMillis()
                            );
                        
                        // Send query to orchestrator
                        orchestrator.tell(new OrchestratorActor.ProcessQuery(query.query, responseHandler));
                    }
                    
                    return Behaviors.same();
                });
            });
        }
    }
    
    /**
     * Print beautiful formatted response with question
     */
    private static void printBeautifulResponse(OrchestratorActor.QueryResult result) {
        // Clear some space
        System.out.println("\n");
        
        if (result.success) {
            // Extract node info from worker path
            String nodeInfo = extractNodeInfo(result.workerPath);
            
            // Show the question
            System.out.println("╔---══╗");
            System.out.println("║  QUESTION                                                    ║");
            System.out.println("╠---══╣");
            printWrapped(lastQuestion, 60);
            System.out.println();
            
            // Show the answer
            System.out.println("╠---══╣");
            System.out.println("║  ANSWER                                                      ║");
            System.out.println("╠---══╣");
            System.out.println();
            
            // Word wrap the answer for nice display
            printWrapped(result.answer, 60);
            
            System.out.println();
            System.out.println("╠---══╣");
            System.out.printf("║  Search handled by: %-41s ║%n", nodeInfo);
            System.out.printf("║  LLM handled by: %-44s ║%n", result.llmNodeId != null ? result.llmNodeId : "N/A");
            System.out.printf("║  Chunks: %-3d | Workers: %-3d | Response time: %5.1f sec      ║%n", 
                result.chunksFound, result.totalWorkers, result.responseTimeMs / 1000.0);
            System.out.println("╠---══╣");
            System.out.println("║  PATTERNS DEMONSTRATED:                                      ║");
            System.out.println("║    • TELL: OrchestratorActor → LoggingActor                  ║");
            System.out.println("║    • ASK:  CLI → OrchestratorActor → Response                ║");
            System.out.println("║    • FORWARD: OrchestratorActor → LLMActor (replyTo kept)    ║");
            System.out.println("╚---══╝");
        } else {
            System.out.println("╔---══╗");
            System.out.println("║  ERROR                                                       ║");
            System.out.println("╠---══╣");
            System.out.println("  " + result.errorMessage);
            System.out.println("╚---══╝");
        }
        System.out.println();
    }
    
    /**
     * Extract readable node info from worker path
     */
    private static String extractNodeInfo(String workerPath) {
        if (workerPath == null) return "Unknown";
        
        // Path format: akka://DistributedRAGSystem@127.0.0.1:2552/user/searchWorker-2
        try {
            String port = "local";
            String worker = "worker";
            
            if (workerPath.contains("@")) {
                // Remote worker - extract port
                int atIndex = workerPath.indexOf("@");
                int slashIndex = workerPath.indexOf("/user/");
                if (slashIndex > atIndex) {
                    String address = workerPath.substring(atIndex + 1, slashIndex);
                    if (address.contains(":")) {
                        port = address.split(":")[1];
                    }
                }
            }
            
            if (workerPath.contains("searchWorker-")) {
                int workerIndex = workerPath.indexOf("searchWorker-");
                worker = workerPath.substring(workerIndex);
            }
            
            return "Node:" + port + " / " + worker;
        } catch (Exception e) {
            return workerPath.length() > 45 ? workerPath.substring(workerPath.length() - 45) : workerPath;
        }
    }
    
    /**
     * Print text with word wrapping
     */
    private static void printWrapped(String text, int width) {
        if (text == null) {
            System.out.println("  (no text)");
            return;
        }
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        
        for (String word : words) {
            if (line.length() + word.length() + 1 > width) {
                System.out.println("  " + line.toString().trim());
                line = new StringBuilder();
            }
            line.append(word).append(" ");
        }
        
        if (line.length() > 0) {
            System.out.println("  " + line.toString().trim());
        }
    }
    
    /**
     * Start cluster node on given port
     * 
     * @param port Port number for this node
     */
    private static void startNode(int port) {
        // Override port in configuration
        Config config = ConfigFactory.parseString(
            "akka.remote.artery.canonical.port=" + port
        ).withFallback(ConfigFactory.load());
        
        // Start actor system with root behavior
        ActorSystem<RootBehavior.Command> system = ActorSystem.create(
            RootBehavior.create(port), 
            "DistributedRAGSystem", 
            config
        );
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down cluster node...");
            system.terminate();
        }));
        
        // If this is the main seed node (2551), start CLI
        if (port == 2551) {
            startCLI(system);
        }
    }
    
    /**
     * Start beautiful CLI for testing queries
     * Only runs on seed node (port 2551)
     * 
     * @param system Actor system
     */
    private static void startCLI(ActorSystem<RootBehavior.Command> system) {
        new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait for cluster to stabilize and all actors to register
                
                // Print beautiful startup banner
                printStartupBanner();
                
                Scanner scanner = new Scanner(System.in);
                
                while (true) {
                    System.out.print("\nEnter your question (or 'quit' to exit): ");
                    String query = scanner.nextLine().trim();
                    
                    if (query.equalsIgnoreCase("quit") || query.equalsIgnoreCase("exit")) {
                        printShutdownMessage();
                        system.terminate();
                        break;
                    }
                    
                    if (query.isEmpty()) {
                        continue;
                    }
                    
                    // Store the question for display in response
                    lastQuestion = query;
                    
                    // Show processing status
                    System.out.println();
                    System.out.println("---");
                    System.out.println("  📤 Sending to Akka Cluster via ASK pattern...");
                    System.out.println("---");
                    System.out.println("  [1/4] ASK: CLI → OrchestratorActor");
                    System.out.println("  [2/4] TELL: OrchestratorActor → SearchWorkerActor");
                    System.out.println("  [3/4] FORWARD: OrchestratorActor → LLMActor (replyTo preserved)");
                    System.out.println("  [4/4] TELL: LLMActor → LoggingActor (fire-and-forget)");
                    System.out.println("---");
                    System.out.println("  Please wait...");
                    
                    // Send query to root actor
                    system.tell(new RootBehavior.ProcessUserQuery(query));
                    
                    // Wait for response to print
                    Thread.sleep(500);
                }
                
                scanner.close();
                
            } catch (Exception e) {
                log.error("CLI error: {}", e.getMessage(), e);
            }
        }, "CLI-Thread").start();
    }
    
    /**
     * Print beautiful startup banner
     */
    private static void printStartupBanner() {
        System.out.println();
        System.out.println("╔---══╗");
        System.out.println("║                                                              ║");
        System.out.println("║       DISTRIBUTED COURSE Q&A SYSTEM                          ║");
        System.out.println("║       CSYE 7374 - Advanced Design Patterns                   ║");
        System.out.println("║                                                              ║");
        System.out.println("╠---══╣");
        System.out.println("║                                                              ║");
        System.out.println("║  Architecture:                                               ║");
        System.out.println("║    - Akka Cluster with Receptionist Service Discovery        ║");
        System.out.println("║    - Word2Vec SLIM Embeddings (300-dim, 300k vocab)          ║");
        System.out.println("║    - Qdrant Vector Database                                  ║");
        System.out.println("║    - OpenAI GPT for Answer Generation                        ║");
        System.out.println("║                                                              ║");
        System.out.println("║  Actors per Node:                                            ║");
        System.out.println("║    - 1x OrchestratorActor (RAG pipeline coordinator)         ║");
        System.out.println("║    - 4x SearchWorkerActor (vector search workers)            ║");
        System.out.println("║    - 1x LLMActor (LLM communication)                         ║");
        System.out.println("║    - 1x LoggingActor (centralized logging)                   ║");
        System.out.println("║                                                              ║");
        System.out.println("║  Communication Patterns:                                     ║");
        System.out.println("║    - TELL: Fire-and-forget to LoggingActor                   ║");
        System.out.println("║    - ASK: Request-response from CLI/REST                     ║");
        System.out.println("║    - FORWARD: OrchestratorActor → LLMActor (replyTo kept)    ║");
        System.out.println("║                                                              ║");
        System.out.println("╠---══╣");
        System.out.println("║  Team: Roshan Shetty & Rithwik                               ║");
        System.out.println("╠---══╣");
        System.out.println("║                                                              ║");
        System.out.println("║  Commands:                                                   ║");
        System.out.println("║    - Type your question and press Enter                      ║");
        System.out.println("║    - Type 'quit' or 'exit' to shutdown                       ║");
        System.out.println("║                                                              ║");
        System.out.println("║  STATUS: READY                                               ║");
        System.out.println("║                                                              ║");
        System.out.println("╚---══╝");
        System.out.println();
    }
    
    /**
     * Print shutdown message
     */
    private static void printShutdownMessage() {
        System.out.println();
        System.out.println("╔---══╗");
        System.out.println("║                   SHUTTING DOWN...                           ║");
        System.out.println("║           Thank you for using the RAG System!                ║");
        System.out.println("╚---══╝");
        System.out.println();
    }
}
