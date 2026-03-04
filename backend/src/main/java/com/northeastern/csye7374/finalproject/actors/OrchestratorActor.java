package com.northeastern.csye7374.finalproject.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.northeastern.csye7374.finalproject.messages.*;
import com.northeastern.csye7374.finalproject.services.LLMService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OrchestratorActor - Coordinates the RAG pipeline across the cluster
 * 
 * Demonstrates three communication patterns:
 * - TELL: Fire-and-forget to workers and logger
 * - ASK: Request-response with REST API
 * - FORWARD: Preserve original sender through LLMActor
 * 
 * Discovers actors via Receptionist for cluster-wide load balancing
 */
public class OrchestratorActor extends AbstractBehavior<OrchestratorActor.Command> {
    
    private static final Logger log = LoggerFactory.getLogger(OrchestratorActor.class);
    
    // Register with Receptionist so REST API can find us
    public static final ServiceKey<Command> ORCHESTRATOR_KEY = 
        ServiceKey.create(Command.class, "orchestrator-actor");
    
    // All messages must be serializable for cluster messaging
    public interface Command extends Serializable {}
    
    // ProcessQuery - User query to process through RAG pipeline
    public static final class ProcessQuery implements Command {
        private static final long serialVersionUID = 1L;
        
        public final String query;
        public final ActorRef<QueryResult> replyTo;
        
        public ProcessQuery(String query, ActorRef<QueryResult> replyTo) {
            this.query = query;
            this.replyTo = replyTo;
        }
    }
    
    // Single adapter for all Receptionist listings (avoids "Wrong key" error)
    private static final class ListingReceived implements Command {
        public final Receptionist.Listing listing;
        
        public ListingReceived(Receptionist.Listing listing) {
            this.listing = listing;
        }
    }
    
    // Internal message when search completes
    private static final class SearchCompleted implements Command {
        public final SearchResponse searchResponse;
        public final String originalQuery;
            public final ActorRef<QueryResult> replyTo;
            public final String workerPath;
        public final long startTimeMs;
        
        public SearchCompleted(SearchResponse searchResponse, String originalQuery, 
                              ActorRef<QueryResult> replyTo, String workerPath, long startTimeMs) {
            this.searchResponse = searchResponse;
            this.originalQuery = originalQuery;
            this.replyTo = replyTo;
            this.workerPath = workerPath;
            this.startTimeMs = startTimeMs;
        }
    }
    
    // Internal message when LLM finishes
    private static final class LLMCompleted implements Command {
        public final LLMActor.LLMResponse llmResponse;
            public final ActorRef<QueryResult> originalReplyTo;
        public final String workerPath;
        public final int chunksFound;
        public final long startTimeMs;
        
        public LLMCompleted(LLMActor.LLMResponse llmResponse, ActorRef<QueryResult> originalReplyTo,
                          String workerPath, int chunksFound, long startTimeMs) {
            this.llmResponse = llmResponse;
            this.originalReplyTo = originalReplyTo;
            this.workerPath = workerPath;
            this.chunksFound = chunksFound;
            this.startTimeMs = startTimeMs;
        }
    }
    
    // Final result sent back to REST API
    public static final class QueryResult implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String answer;
        public final boolean success;
        public final String errorMessage;
        public final String workerPath;
        public final String llmNodeId;
        public final int chunksFound;
        public final long responseTimeMs;
        public final int totalWorkers;
        
        // Success constructor
        public QueryResult(String answer, String workerPath, String llmNodeId, 
                          int chunksFound, long responseTimeMs, int totalWorkers) {
            this.answer = answer;
            this.success = true;
            this.errorMessage = null;
            this.workerPath = workerPath;
            this.llmNodeId = llmNodeId;
            this.chunksFound = chunksFound;
            this.responseTimeMs = responseTimeMs;
            this.totalWorkers = totalWorkers;
        }
        
        // Error constructor
        public QueryResult(String answer, String errorMessage) {
            this.answer = answer;
            this.success = false;
            this.errorMessage = errorMessage;
            this.workerPath = null;
            this.llmNodeId = null;
            this.chunksFound = 0;
            this.responseTimeMs = 0;
            this.totalWorkers = 0;
        }
        
        public static QueryResult error(String errorMessage) {
            return new QueryResult(null, errorMessage);
        }
    }
    
    private final String nodeId;
    
    // Discovered actors from all nodes
    private List<ActorRef<SearchWorkerActor.Command>> searchWorkers = new ArrayList<>();
    private List<ActorRef<LLMActor.Command>> llmActors = new ArrayList<>();
    private List<ActorRef<LoggingActor.Command>> loggingActors = new ArrayList<>();
    
    // Round-robin load balancing
    private int searchWorkerIndex = 0;
    private int llmActorIndex = 0;
    
    // Initialize and subscribe to Receptionist
    private OrchestratorActor(ActorContext<Command> context, String nodeId) {
        super(context);
        this.nodeId = nodeId;
        
        // Register with Receptionist
        context.getSystem().receptionist().tell(
            Receptionist.register(ORCHESTRATOR_KEY, context.getSelf())
        );
        log.info("[ORCHESTRATOR] Registered with Receptionist (key: orchestrator-actor)");
        System.out.println("[ORCHESTRATOR] Registered with Receptionist (key: orchestrator-actor)");
        
        // Single adapter for all listings
        ActorRef<Receptionist.Listing> listingAdapter = context.messageAdapter(
            Receptionist.Listing.class, ListingReceived::new
        );
        
        // Subscribe to all service keys
        context.getSystem().receptionist().tell(
            Receptionist.subscribe(SearchWorkerActor.SEARCH_WORKER_KEY, listingAdapter)
        );
        context.getSystem().receptionist().tell(
            Receptionist.subscribe(LLMActor.SERVICE_KEY, listingAdapter)
        );
        context.getSystem().receptionist().tell(
            Receptionist.subscribe(LoggingActor.SERVICE_KEY, listingAdapter)
        );
        
        log.info("═══════════════════════════════════════════════════════════");
        log.info("[ORCHESTRATOR] [{}] Started", nodeId);
        log.info("[ORCHESTRATOR] Subscribed to Receptionist for:");
        log.info("[ORCHESTRATOR]   - SearchWorkerActor discovery");
        log.info("[ORCHESTRATOR]   - LLMActor discovery");
        log.info("[ORCHESTRATOR]   - LoggingActor discovery");
        log.info("═══════════════════════════════════════════════════════════");
        
        System.out.println("---");
        System.out.println("[ORCHESTRATOR] [" + nodeId + "] Started");
        System.out.println("[ORCHESTRATOR] Subscribed to discover all actors via Receptionist");
        System.out.println("---");
    }
    
    // Factory method
    public static Behavior<Command> create(String nodeId) {
        return Behaviors.setup(context -> new OrchestratorActor(context, nodeId));
    }
    
    // Legacy factory method
    public static Behavior<Command> create(LLMService llmService) {
        return Behaviors.setup(context -> {
            // Handle Scala Option type
            scala.Option<Object> portOption = context.getSystem().address().port();
            int port = portOption.isDefined() ? (Integer) portOption.get() : 0;
            return new OrchestratorActor(context, "Node-" + port);
        });
    }
    
    // Message handler
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(ListingReceived.class, this::onListingReceived)
            .onMessage(ProcessQuery.class, this::onProcessQuery)
            .onMessage(SearchCompleted.class, this::onSearchCompleted)
            .onMessage(LLMCompleted.class, this::onLLMCompleted)
            .build();
    }
    
    // Update actor lists from Receptionist
    private Behavior<Command> onListingReceived(ListingReceived msg) {
        Receptionist.Listing listing = msg.listing;
        
        // Route based on service key
        if (listing.isForKey(SearchWorkerActor.SEARCH_WORKER_KEY)) {
            Set<ActorRef<SearchWorkerActor.Command>> newWorkers = 
                listing.getServiceInstances(SearchWorkerActor.SEARCH_WORKER_KEY);
            this.searchWorkers = new ArrayList<>(newWorkers);
            
            log.info("[ORCHESTRATOR] [{}] Search workers updated: {} available", nodeId, searchWorkers.size());
            System.out.println("[ORCHESTRATOR] Search workers: " + searchWorkers.size() + " available");
            
        } else if (listing.isForKey(LLMActor.SERVICE_KEY)) {
            Set<ActorRef<LLMActor.Command>> newLLMActors = 
                listing.getServiceInstances(LLMActor.SERVICE_KEY);
            this.llmActors = new ArrayList<>(newLLMActors);
            
            log.info("[ORCHESTRATOR] [{}] LLM actors updated: {} available", nodeId, llmActors.size());
            System.out.println("[ORCHESTRATOR] LLM actors: " + llmActors.size() + " available");
            
        } else if (listing.isForKey(LoggingActor.SERVICE_KEY)) {
            Set<ActorRef<LoggingActor.Command>> newLoggingActors = 
                listing.getServiceInstances(LoggingActor.SERVICE_KEY);
            this.loggingActors = new ArrayList<>(newLoggingActors);
            
            log.info("[ORCHESTRATOR] [{}] Logging actors updated: {} available", nodeId, loggingActors.size());
            System.out.println("[ORCHESTRATOR] Logging actors: " + loggingActors.size() + " available");
        }
        
        return this;
    }
    
    // Start RAG pipeline with round-robin load balancing
    private Behavior<Command> onProcessQuery(ProcessQuery command) {
        long startTime = System.currentTimeMillis();
        
        log.info("---");
        log.info("[ORCHESTRATOR] [{}] Received ProcessQuery", nodeId);
        log.info("[ORCHESTRATOR] Query: \"{}\"", 
            command.query.substring(0, Math.min(50, command.query.length())) + "...");
        
        System.out.println("---");
        System.out.println("[ORCHESTRATOR] [" + nodeId + "] Received ProcessQuery");
        System.out.println("[ORCHESTRATOR] Query: \"" + 
            command.query.substring(0, Math.min(50, command.query.length())) + "...\"");
        
        // TELL to logger (fire-and-forget)
        if (!loggingActors.isEmpty()) {
            ActorRef<LoggingActor.Command> logger = loggingActors.get(0);
            log.info("[ORCHESTRATOR] TELL → LoggingActor (fire-and-forget)");
            System.out.println("[ORCHESTRATOR] TELL → LoggingActor (fire-and-forget)");
            
            logger.tell(new LoggingActor.LogEntry(
                "QUERY_RECEIVED",
                "Received query: " + command.query.substring(0, Math.min(50, command.query.length())) + "...",
                System.currentTimeMillis(),
                nodeId
            ));
        }
        
        // Check workers available
        if (searchWorkers.isEmpty()) {
            log.error("[ORCHESTRATOR] No search workers available!");
            System.out.println("[ORCHESTRATOR] ❌ No search workers available!");
            command.replyTo.tell(QueryResult.error("No search workers available. Please wait for cluster to initialize."));
            return this;
        }
        
        try {
            // Round-robin selection
            ActorRef<SearchWorkerActor.Command> selectedWorker = 
                searchWorkers.get(searchWorkerIndex % searchWorkers.size());
            searchWorkerIndex++;
            
            String workerPath = selectedWorker.path().toString();
            
            log.info("[ORCHESTRATOR] Selected worker: {}", workerPath);
            log.info("[ORCHESTRATOR] TELL → SearchWorkerActor (with response adapter)");
            System.out.println("[ORCHESTRATOR] TELL → SearchWorkerActor: " + workerPath);
            
            // Build search query
            SearchQuery searchQuery = new SearchQuery(command.query, 5); // top-5 results
            
            // Adapter preserves original replyTo
            final String finalWorkerPath = workerPath;
            final long finalStartTime = startTime;
            ActorRef<SearchResponse> responseAdapter = getContext().messageAdapter(
                SearchResponse.class,
                response -> new SearchCompleted(
                    response, 
                    command.query, 
                    command.replyTo,
                    finalWorkerPath, 
                    finalStartTime
                )
            );
            
            // TELL to worker
            SearchWorkerActor.SearchCommand searchCommand = 
                new SearchWorkerActor.SearchCommand(searchQuery, responseAdapter);
            
            selectedWorker.tell(searchCommand);
            
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Error processing query: {}", e.getMessage(), e);
            command.replyTo.tell(QueryResult.error("Failed to process query: " + e.getMessage()));
        }
        
        return this;
    }
    
    // Forward to LLMActor for answer generation
    private Behavior<Command> onSearchCompleted(SearchCompleted command) {
        SearchResponse searchResponse = command.searchResponse;
        int chunksFound = searchResponse.isSuccess() ? searchResponse.getChunks().size() : 0;
        
        log.info("---");
        log.info("[ORCHESTRATOR] [{}] Search completed", nodeId);
        log.info("[ORCHESTRATOR] Chunks found: {}", chunksFound);
        log.info("[ORCHESTRATOR] Worker: {}", command.workerPath);
        
        System.out.println("---");
        System.out.println("[ORCHESTRATOR] [" + nodeId + "] Search completed");
        System.out.println("[ORCHESTRATOR] Chunks found: " + chunksFound);
        
        // TELL to logger
        if (!loggingActors.isEmpty()) {
            ActorRef<LoggingActor.Command> logger = loggingActors.get(0);
            log.info("[ORCHESTRATOR] TELL → LoggingActor (fire-and-forget)");
            System.out.println("[ORCHESTRATOR] TELL → LoggingActor (fire-and-forget)");
            
            logger.tell(new LoggingActor.LogSearch(
                command.originalQuery,
                chunksFound,
                System.currentTimeMillis(),
                nodeId
            ));
        }
        
        try {
            // Check search success
            if (!searchResponse.isSuccess()) {
                command.replyTo.tell(QueryResult.error("Search failed: " + searchResponse.getErrorMessage()));
                return this;
            }
            
            // Check results
            if (searchResponse.getChunks().isEmpty()) {
                long responseTime = System.currentTimeMillis() - command.startTimeMs;
                command.replyTo.tell(new QueryResult(
                    "No relevant information found in the course materials.",
                    command.workerPath, nodeId, 0, responseTime, searchWorkers.size()
                ));
                return this;
            }
            
            // Check LLM actors available
            if (llmActors.isEmpty()) {
                log.error("[ORCHESTRATOR] No LLM actors available!");
                System.out.println("[ORCHESTRATOR] ❌ No LLM actors available!");
                command.replyTo.tell(QueryResult.error("No LLM actors available. Please wait for cluster to initialize."));
                return this;
            }
            
            // FORWARD to LLMActor (round-robin)
            ActorRef<LLMActor.Command> selectedLLMActor = 
                llmActors.get(llmActorIndex % llmActors.size());
            llmActorIndex++;
            
            log.info("[ORCHESTRATOR] FORWARD → LLMActor (preserving replyTo chain)");
            log.info("[ORCHESTRATOR] Selected LLMActor: {}", selectedLLMActor.path());
            System.out.println("[ORCHESTRATOR] FORWARD → LLMActor: " + selectedLLMActor.path());
            System.out.println("[ORCHESTRATOR] (replyTo preserved for response routing)");
            
            // Adapter preserves replyTo
            final ActorRef<QueryResult> originalReplyTo = command.replyTo;
            final String finalWorkerPath = command.workerPath;
            final int finalChunksFound = chunksFound;
            final long finalStartTime = command.startTimeMs;
            
            ActorRef<LLMActor.LLMResponse> llmResponseAdapter = getContext().messageAdapter(
                LLMActor.LLMResponse.class,
                response -> new LLMCompleted(
                    response,
                    originalReplyTo,
                    finalWorkerPath,
                    finalChunksFound,
                    finalStartTime
                )
            );
            
            // Pass logger to LLMActor
            ActorRef<LoggingActor.Command> loggerForLLM = 
                loggingActors.isEmpty() ? null : loggingActors.get(0);
            
            // Send to LLM
            selectedLLMActor.tell(new LLMActor.GenerateAnswer(
                command.originalQuery,
                searchResponse.getChunks(),
                llmResponseAdapter,
                loggerForLLM
            ));
            
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Error in search completion: {}", e.getMessage(), e);
            command.replyTo.tell(QueryResult.error("Failed to generate answer: " + e.getMessage()));
        }
        
        return this;
    }
    
    // Reply to original sender
    private Behavior<Command> onLLMCompleted(LLMCompleted command) {
        log.info("---");
        log.info("[ORCHESTRATOR] [{}] LLM response received", nodeId);
        log.info("[ORCHESTRATOR] Success: {}", command.llmResponse.success);
        log.info("[ORCHESTRATOR] Processed by: {}", command.llmResponse.processedByNode);
        
        System.out.println("---");
        System.out.println("[ORCHESTRATOR] [" + nodeId + "] LLM response received");
        System.out.println("[ORCHESTRATOR] Processed by LLMActor on: " + command.llmResponse.processedByNode);
        
        long totalResponseTime = System.currentTimeMillis() - command.startTimeMs;
        
        if (command.llmResponse.success) {
            log.info("[ORCHESTRATOR] ✅ Sending final response to original sender");
            System.out.println("[ORCHESTRATOR] ✅ Sending final response to original sender");
            
            QueryResult result = new QueryResult(
                command.llmResponse.answer,
                command.workerPath,
                command.llmResponse.processedByNode,
                command.chunksFound,
                totalResponseTime,
                searchWorkers.size()
            );
            
            command.originalReplyTo.tell(result);
            
        } else {
            log.error("[ORCHESTRATOR] ❌ LLM error: {}", command.llmResponse.errorMessage);
            System.out.println("[ORCHESTRATOR] ❌ LLM error: " + command.llmResponse.errorMessage);
            
            command.originalReplyTo.tell(QueryResult.error(command.llmResponse.errorMessage));
        }
        
        log.info("---");
        System.out.println("---");
        
        return this;
    }
}
