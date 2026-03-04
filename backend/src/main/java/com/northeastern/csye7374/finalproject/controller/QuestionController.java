package com.northeastern.csye7374.finalproject.controller;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.AskPattern;
import com.northeastern.csye7374.finalproject.actors.ClusterClientActor;
import com.northeastern.csye7374.finalproject.actors.OrchestratorActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * QuestionController - REST API for Q&A System
 * 
 * Uses ASK pattern to communicate with Akka cluster
 * 
 * @author Roshan Shetty & Rithwik
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class QuestionController {

    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);

    private final ActorSystem<ClusterClientActor.Command> clusterClient;
    private static final Duration GET_ORCHESTRATOR_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(60);
    @Autowired
    public QuestionController(ActorSystem<ClusterClientActor.Command> clusterClient) {
        this.clusterClient = clusterClient;
        
        log.info("---");
        log.info("[REST] QuestionController initialized");
        log.info("[REST] Mode: CLUSTER (via Akka actors)");
        log.info("[REST] Patterns: ASK → ClusterClientActor → OrchestratorActor");
        log.info("---");
        
        System.out.println("---");
        System.out.println("[REST] QuestionController initialized");
        System.out.println("[REST] Mode: CLUSTER (via Akka actors)");
        System.out.println("---");
    }

    // Main endpoint - POST /api/ask
    @PostMapping("/ask")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> askQuestion(
            @RequestBody Map<String, String> request) {
        
        String question = request.get("question");
        long startTime = System.currentTimeMillis();
        
        log.info("---");
        log.info("[REST] Received: \"{}\"", question);
        log.info("[REST] Mode: CLUSTER (via Akka actors)");
        log.info("[REST] Step 1: ASK ClusterClientActor for orchestrator...");
        
        System.out.println();
        System.out.println("---");
        System.out.println("[REST] Received: \"" + question + "\"");
        System.out.println("[REST] Mode: CLUSTER (via Akka actors)");
        System.out.println("[REST] Step 1: ASK ClusterClientActor for orchestrator...");
        System.out.println("---");
        
        // Step 1: ASK ClusterClientActor for an available OrchestratorActor
        CompletionStage<ClusterClientActor.OrchestratorResponse> orchestratorFuture =
            AskPattern.ask(
                clusterClient,
                replyTo -> new ClusterClientActor.GetOrchestrator(replyTo),
                GET_ORCHESTRATOR_TIMEOUT,
                clusterClient.scheduler()
            );
        
        return orchestratorFuture.thenCompose(orchResponse -> {
            // Check if any orchestrators are available
            if (!orchResponse.available) {
                log.warn("[REST] ❌ No orchestrators available!");
                System.out.println("[REST] ❌ No orchestrators available!");
                System.out.println("[REST] Make sure cluster nodes (2551, 2552) are running");
                System.out.println("---");
                
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", "No orchestrators available. Start cluster nodes first.");
                errorResponse.put("orchestratorCount", 0);
                errorResponse.put("hint", "Run: mvn exec:java \"-Dexec.args=2551\" in the backend folder");
                
                return CompletableFuture.completedFuture(
                    ResponseEntity.status(503).body(errorResponse)
                );
            }
            
            ActorRef<OrchestratorActor.Command> orchestrator = orchResponse.orchestrator;
            
            log.info("[REST] ✅ Got orchestrator: {}", orchestrator.path());
            log.info("[REST] Total orchestrators: {}", orchResponse.totalOrchestrators);
            log.info("[REST] Step 2: ASK OrchestratorActor to process query...");
            
            System.out.println("[REST] ✅ Got orchestrator: " + orchestrator.path());
            System.out.println("[REST] Total orchestrators: " + orchResponse.totalOrchestrators);
            System.out.println("[REST] Step 2: ASK OrchestratorActor to process query...");
            System.out.println("---");
            
            // Step 2: ASK OrchestratorActor to process the query
            // This demonstrates the ASK pattern with request-response!
            return AskPattern.<OrchestratorActor.Command, OrchestratorActor.QueryResult>ask(
                orchestrator,
                replyTo -> new OrchestratorActor.ProcessQuery(question, replyTo),
                QUERY_TIMEOUT,
                clusterClient.scheduler()
            ).thenApply(result -> {
                double responseTime = (System.currentTimeMillis() - startTime) / 1000.0;
                
                log.info("---");
                log.info("[REST] ✅ Response received from cluster!");
                log.info("[REST] Search worker: {}", result.workerPath);
                log.info("[REST] LLM node: {}", result.llmNodeId);
                log.info("[REST] Total time: {}s", responseTime);
                log.info("---");
                
                System.out.println();
                System.out.println("---");
                System.out.println("[REST] ✅ Response received from cluster!");
                System.out.println("[REST] Search worker: " + result.workerPath);
                System.out.println("[REST] LLM node: " + result.llmNodeId);
                System.out.println("[REST] Total time: " + String.format("%.2f", responseTime) + "s");
                System.out.println("---");
            System.out.println();
            
                Map<String, Object> response = new HashMap<>();
            
                if (result.success) {
                    response.put("answer", result.answer);
                    response.put("success", true);
            response.put("responseTime", responseTime);
                    
                    // Processing info
                    Map<String, Object> processedBy = new HashMap<>();
                    processedBy.put("orchestrator", orchestrator.path().toString());
                    processedBy.put("searchWorker", result.workerPath != null ? result.workerPath : "unknown");
                    processedBy.put("llmNode", result.llmNodeId != null ? result.llmNodeId : "unknown");
                    response.put("processedBy", processedBy);
            
                    // Cluster info
                    Map<String, Object> clusterInfo = new HashMap<>();
                    clusterInfo.put("orchestratorCount", orchResponse.totalOrchestrators);
                    clusterInfo.put("chunksFound", result.chunksFound);
                    clusterInfo.put("totalWorkers", result.totalWorkers);
                    response.put("clusterInfo", clusterInfo);
                    
                    // Communication patterns demonstrated
                    Map<String, String> patterns = new HashMap<>();
                    patterns.put("ASK", "REST → ClusterClientActor → OrchestratorActor (request-response)");
                    patterns.put("TELL", "OrchestratorActor → LoggingActor (fire-and-forget)");
                    patterns.put("FORWARD", "OrchestratorActor → LLMActor (replyTo preserved)");
                    response.put("patterns", patterns);
                    
                    return ResponseEntity.ok(response);
                    
                } else {
            response.put("success", false);
                    response.put("error", result.errorMessage != null ? result.errorMessage : "Unknown error");
                    return ResponseEntity.status(500).body(response);
        }
        
            }).exceptionally(ex -> {
                log.error("[REST] ❌ Error from cluster: {}", ex.getMessage(), ex);
                System.out.println("[REST] ❌ Error: " + ex.getMessage());
                System.out.println("---");
                
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", ex.getMessage());
                return ResponseEntity.status(500).body(errorResponse);
                
            }).toCompletableFuture();
            
        }).toCompletableFuture();
    }

    /**
     * Health check endpoint
     * GET /api/health
     * 
     * Checks cluster connectivity by asking for orchestrators
     */
    @GetMapping("/health")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> health() {
        return AskPattern.<ClusterClientActor.Command, ClusterClientActor.OrchestratorResponse>ask(
            clusterClient,
            (ActorRef<ClusterClientActor.OrchestratorResponse> replyTo) -> new ClusterClientActor.GetOrchestrator(replyTo),
            GET_ORCHESTRATOR_TIMEOUT,
            clusterClient.scheduler()
        ).thenApply(response -> {
            Map<String, Object> health = new HashMap<>();
            health.put("status", response.available ? "UP" : "DEGRADED");
            health.put("mode", "CLUSTER");
            health.put("orchestratorsAvailable", response.totalOrchestrators);
            health.put("clusterReady", response.available);
            
            if (!response.available) {
                health.put("message", "Waiting for cluster nodes. Start: mvn exec:java \"-Dexec.args=2551\"");
            }
            
            return ResponseEntity.ok(health);
        }).exceptionally(ex -> {
        Map<String, Object> health = new HashMap<>();
            health.put("status", "DOWN");
            health.put("mode", "CLUSTER");
            health.put("error", ex.getMessage());
            return ResponseEntity.status(503).body(health);
        }).toCompletableFuture();
    }
    
    /**
     * Cluster status endpoint
     * GET /api/cluster-status
     * 
     * Returns detailed cluster connectivity information
     */
    @GetMapping("/cluster-status")
    public CompletableFuture<ResponseEntity<Map<String, Object>>> clusterStatus() {
        return AskPattern.<ClusterClientActor.Command, ClusterClientActor.OrchestratorResponse>ask(
            clusterClient,
            (ActorRef<ClusterClientActor.OrchestratorResponse> replyTo) -> new ClusterClientActor.GetOrchestrator(replyTo),
            GET_ORCHESTRATOR_TIMEOUT,
            clusterClient.scheduler()
        ).thenApply(response -> {
            Map<String, Object> status = new HashMap<>();
            status.put("mode", "CLUSTER");
            status.put("orchestratorCount", response.totalOrchestrators);
            status.put("ready", response.available);
            status.put("clusterClientNode", "127.0.0.1:2553");
            status.put("seedNodes", new String[]{"127.0.0.1:2551", "127.0.0.1:2552"});
            
            if (response.available) {
                status.put("message", "✅ Cluster connected with " + response.totalOrchestrators + " orchestrator(s)!");
            } else {
                status.put("message", "⚠️ Waiting for orchestrators... Start cluster nodes (2551, 2552) first.");
                status.put("startCommand", "cd backend; mvn exec:java \"-Dexec.args=2551\"");
            }
            
            return ResponseEntity.ok(status);
        }).exceptionally(ex -> {
            Map<String, Object> status = new HashMap<>();
            status.put("mode", "CLUSTER");
            status.put("ready", false);
            status.put("error", ex.getMessage());
            return ResponseEntity.status(503).body(status);
        }).toCompletableFuture();
    }

    /**
     * System info endpoint
     * GET /api/info
     */
    @GetMapping("/info")
    public Map<String, Object> getSystemInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("system", "Distributed Course Q&A System");
        info.put("version", "2.0.0");
        info.put("mode", "CLUSTER");
        
        info.put("technologies", new String[]{
            "Akka Cluster (Typed Actors)",
            "Word2Vec SLIM (300-dim)",
            "Qdrant Vector Database",
            "OpenAI GPT-3.5",
            "Spring Boot"
        });
        
        info.put("actors", new String[]{
            "ClusterClientActor - REST API gateway (port 2553)",
            "OrchestratorActor - RAG pipeline coordinator",
            "SearchWorkerActor - Vector search (4 per node)",
            "LLMActor - LLM communication wrapper",
            "LoggingActor - Centralized logging"
        });
        
        info.put("patterns", new String[]{
            "ASK - REST → ClusterClientActor → OrchestratorActor",
            "TELL - OrchestratorActor → LoggingActor (fire-and-forget)",
            "FORWARD - OrchestratorActor → LLMActor (replyTo preserved)"
        });
        
        info.put("clusterPorts", new String[]{
            "2551 - Seed node 1",
            "2552 - Seed node 2",
            "2553 - REST API client"
        });
        
        info.put("authors", new String[]{"Roshan Shetty", "Rithwik"});
        info.put("course", "CSYE 7374 - AI Agent Infrastructure");
        
        return info;
    }
}
