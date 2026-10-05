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
import java.time.Duration;
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
    
    // Internal message when search completes (or fails/times out).
    // Built per request by context.ask, so it always carries the caller of THIS query.
    private static final class SearchCompleted implements Command {
        public final SearchResponse searchResponse;   // null when the ask failed
        public final Throwable failure;               // null on success
        public final String originalQuery;
        public final ActorRef<QueryResult> replyTo;
        public final ActorRef<SearchWorkerActor.Command> worker;
        public final String workerPath;
        public final long startTimeMs;
        public final int attempt;
        
        public SearchCompleted(SearchResponse searchResponse, Throwable failure, String originalQuery, 
                              ActorRef<QueryResult> replyTo, ActorRef<SearchWorkerActor.Command> worker,
                              long startTimeMs, int attempt) {
            this.searchResponse = searchResponse;
            this.failure = failure;
            this.originalQuery = originalQuery;
            this.replyTo = replyTo;
            this.worker = worker;
            this.workerPath = worker.path().toString();
            this.startTimeMs = startTimeMs;
            this.attempt = attempt;
        }
    }
    
    // Internal message when LLM finishes (or fails/times out), built per request by context.ask
    private static final class LLMCompleted implements Command {
        public final LLMActor.LLMResponse llmResponse;   // null when the ask failed
        public final Throwable failure;                  // null on success
        public final ActorRef<QueryResult> originalReplyTo;
        public final String originalQuery;
        public final List<String> chunks;
        public final ActorRef<LLMActor.Command> llmActor;
        public final String workerPath;
        public final long startTimeMs;
        public final int attempt;
        
        public LLMCompleted(LLMActor.LLMResponse llmResponse, Throwable failure, ActorRef<QueryResult> originalReplyTo,
                          String originalQuery, List<String> chunks, ActorRef<LLMActor.Command> llmActor,
                          String workerPath, long startTimeMs, int attempt) {
            this.llmResponse = llmResponse;
            this.failure = failure;
            this.originalReplyTo = originalReplyTo;
            this.originalQuery = originalQuery;
            this.chunks = chunks;
            this.llmActor = llmActor;
            this.workerPath = workerPath;
            this.startTimeMs = startTimeMs;
            this.attempt = attempt;
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
    
    // Answer when no chunk passes the relevance threshold
    public static final String NO_RELEVANT_CONTENT = "No relevant content found in the uploaded documents.";
    
    private final String nodeId;
    
    // Per-step timeouts (documind.orchestrator.* in application.conf). A step that
    // times out (e.g. its worker sat on a node that died) is retried once on a
    // different worker, preferably on another node. Defaults keep the worst case
    // (2 x search + 2 x LLM = 50s) inside the REST API's 60s ask timeout.
    private static final Duration DEFAULT_SEARCH_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_LLM_TIMEOUT = Duration.ofSeconds(20);
    private final Duration searchTimeout;
    private final Duration llmTimeout;
    
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
        
        com.typesafe.config.Config config = context.getSystem().settings().config();
        this.searchTimeout = config.hasPath("documind.orchestrator.search-timeout")
            ? config.getDuration("documind.orchestrator.search-timeout") : DEFAULT_SEARCH_TIMEOUT;
        this.llmTimeout = config.hasPath("documind.orchestrator.llm-timeout")
            ? config.getDuration("documind.orchestrator.llm-timeout") : DEFAULT_LLM_TIMEOUT;
        
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
            
            askSearch(command.query, command.replyTo, startTime, selectedWorker, 1);
            
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Error processing query: {}", e.getMessage(), e);
            command.replyTo.tell(QueryResult.error("Failed to process query: " + e.getMessage()));
        }
        
        return this;
    }
    
    // ASK one search worker; the reply (or timeout) comes back as SearchCompleted
    private void askSearch(String query, ActorRef<QueryResult> caller, long startTime,
                           ActorRef<SearchWorkerActor.Command> worker, int attempt) {
        log.info("[ORCHESTRATOR] Selected worker: {} (attempt {})", worker.path(), attempt);
        log.info("[ORCHESTRATOR] ASK → SearchWorkerActor (per-request reply)");
        System.out.println("[ORCHESTRATOR] ASK → SearchWorkerActor: " + worker.path());
        
        // Build search query
        SearchQuery searchQuery = new SearchQuery(query, 5); // top-5 results
        
        // context.ask creates a fresh reply ref for this request only, so the
        // response is mapped with THIS query's replyTo even when several
        // queries are in flight (a shared messageAdapter would be replaced
        // by the next query and cross the replies).
        getContext().ask(
            SearchResponse.class,
            worker,
            searchTimeout,
            replyTo -> new SearchWorkerActor.SearchCommand(searchQuery, replyTo),
            (response, failure) -> new SearchCompleted(
                response, failure, query, caller, worker, startTime, attempt)
        );
    }
    
    // ASK one LLM actor; it replies straight to the per-request ref (FORWARD of replyTo)
    private void askLlm(String query, List<String> chunks, ActorRef<QueryResult> caller, String workerPath,
                        long startTime, ActorRef<LLMActor.Command> llmActor, int attempt) {
        log.info("[ORCHESTRATOR] FORWARD → LLMActor (preserving replyTo chain)");
        log.info("[ORCHESTRATOR] Selected LLMActor: {} (attempt {})", llmActor.path(), attempt);
        System.out.println("[ORCHESTRATOR] FORWARD → LLMActor: " + llmActor.path());
        System.out.println("[ORCHESTRATOR] (replyTo preserved for response routing)");
        
        // Pass logger to LLMActor
        ActorRef<LoggingActor.Command> loggerForLLM = 
            loggingActors.isEmpty() ? null : loggingActors.get(0);
        
        getContext().ask(
            LLMActor.LLMResponse.class,
            llmActor,
            llmTimeout,
            replyTo -> new LLMActor.GenerateAnswer(query, chunks, replyTo, loggerForLLM),
            (response, failure) -> new LLMCompleted(
                response, failure, caller, query, chunks, llmActor, workerPath, startTime, attempt)
        );
    }
    
    // Pick a different actor than the one that failed, preferring one on another node
    private static <T> ActorRef<T> pickOther(List<ActorRef<T>> candidates, ActorRef<T> failed) {
        ActorRef<T> sameNode = null;
        for (ActorRef<T> candidate : candidates) {
            if (candidate.equals(failed)) {
                continue;
            }
            if (!candidate.path().address().equals(failed.path().address())) {
                return candidate;
            }
            if (sameNode == null) {
                sameNode = candidate;
            }
        }
        return sameNode;
    }
    
    // Forward to LLMActor for answer generation
    private Behavior<Command> onSearchCompleted(SearchCompleted command) {
        if (command.failure != null) {
            // Timed out (worker dead, unreachable or stuck): retry once elsewhere
            log.error("[ORCHESTRATOR] Search step failed on {}: {}", command.workerPath, command.failure.getMessage());
            ActorRef<SearchWorkerActor.Command> other =
                command.attempt == 1 ? pickOther(searchWorkers, command.worker) : null;
            if (other != null) {
                System.out.println("[ORCHESTRATOR] Search timed out, retrying on another worker");
                askSearch(command.originalQuery, command.replyTo, command.startTimeMs, other, command.attempt + 1);
            } else {
                command.replyTo.tell(QueryResult.error("Search failed: " + command.failure.getMessage()));
            }
            return this;
        }
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
                    NO_RELEVANT_CONTENT,
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
            
            askLlm(command.originalQuery, searchResponse.getChunks(), command.replyTo,
                command.workerPath, command.startTimeMs, selectedLLMActor, 1);
            
        } catch (Exception e) {
            log.error("[ORCHESTRATOR] Error in search completion: {}", e.getMessage(), e);
            command.replyTo.tell(QueryResult.error("Failed to generate answer: " + e.getMessage()));
        }
        
        return this;
    }
    
    // Reply to original sender
    private Behavior<Command> onLLMCompleted(LLMCompleted command) {
        if (command.failure != null) {
            // Timed out (LLM actor dead, unreachable or stuck): retry once elsewhere
            log.error("[ORCHESTRATOR] LLM step failed: {}", command.failure.getMessage());
            ActorRef<LLMActor.Command> other =
                command.attempt == 1 ? pickOther(llmActors, command.llmActor) : null;
            if (other != null) {
                System.out.println("[ORCHESTRATOR] LLM step timed out, retrying on another LLMActor");
                askLlm(command.originalQuery, command.chunks, command.originalReplyTo, command.workerPath,
                    command.startTimeMs, other, command.attempt + 1);
            } else {
                // No LLM actor answered in time: return the retrieved passages instead
                long totalResponseTime = System.currentTimeMillis() - command.startTimeMs;
                command.originalReplyTo.tell(new QueryResult(
                    LLMService.fallbackAnswer(command.chunks),
                    command.workerPath,
                    "fallback",
                    command.chunks.size(),
                    totalResponseTime,
                    searchWorkers.size()
                ));
            }
            return this;
        }
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
                command.chunks.size(),
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
