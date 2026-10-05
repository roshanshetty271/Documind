package com.northeastern.csye7374.finalproject.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.DispatcherSelector;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.northeastern.csye7374.finalproject.messages.SearchQuery;
import com.northeastern.csye7374.finalproject.messages.SearchResponse;
import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.QdrantService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * SearchWorkerActor - Handles vector search
 * 
 * Receives queries, searches Qdrant, returns results
 */
public class SearchWorkerActor extends AbstractBehavior<SearchWorkerActor.Command> {
    
    private static final Logger log = LoggerFactory.getLogger(SearchWorkerActor.class);
    
    // Register with Receptionist
    public static final ServiceKey<Command> SEARCH_WORKER_KEY = 
        ServiceKey.create(Command.class, "SearchWorker");
    
    // All messages must be serializable
    public interface Command extends Serializable {}
    
    // SearchCommand with replyTo
    public static final class SearchCommand implements Command {
        private static final long serialVersionUID = 1L;
        
        public final SearchQuery searchQuery;
        public final ActorRef<SearchResponse> replyTo;
        
        public SearchCommand(SearchQuery searchQuery, ActorRef<SearchResponse> replyTo) {
            this.searchQuery = searchQuery;
            this.replyTo = replyTo;
        }
    }
    
    // Internal: blocking search finished on the blocking-io dispatcher
    private static final class SearchFinished implements Command {
        public final SearchResponse response;
        public final ActorRef<SearchResponse> replyTo;
        
        public SearchFinished(SearchResponse response, ActorRef<SearchResponse> replyTo) {
            this.response = response;
            this.replyTo = replyTo;
        }
    }
    
    // Dispatcher for blocking calls (defined in application.conf)
    public static final String BLOCKING_DISPATCHER = "documind.blocking-io-dispatcher";
    
    // Minimum cosine score for a chunk to count as relevant (documind.search.min-score)
    private static final double DEFAULT_MIN_SCORE = 0.3;
    
    private final QdrantService qdrantService;
    private final EmbeddingService embeddingService;
    private final String collectionName;
    private final Executor blockingExecutor;
    private final double minScore;
    private SearchWorkerActor(ActorContext<Command> context, String collectionName,
                              EmbeddingService embeddingService, QdrantService qdrantService) {
        super(context);
        this.collectionName = collectionName;
        this.embeddingService = embeddingService;
        this.qdrantService = qdrantService;
        this.blockingExecutor = context.getSystem().dispatchers()
            .lookup(DispatcherSelector.fromConfig(BLOCKING_DISPATCHER));
        com.typesafe.config.Config config = context.getSystem().settings().config();
        this.minScore = config.hasPath("documind.search.min-score")
            ? config.getDouble("documind.search.min-score") : DEFAULT_MIN_SCORE;
        
        log.info("SearchWorkerActor initialized for collection: {}", collectionName);
    }
    
    // Factory method - registers with Receptionist
    public static Behavior<Command> create(String collectionName, EmbeddingService embeddingService) {
        return Behaviors.setup(context -> {
            QdrantService qdrantService;
            try {
                // Initialize Qdrant service
                qdrantService = new QdrantService();
            } catch (Exception e) {
                log.error("Error initializing SearchWorkerActor: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to initialize SearchWorkerActor", e);
            }
            return register(context, collectionName, embeddingService, qdrantService);
        });
    }
    
    // Factory method with a given QdrantService (also used by tests)
    public static Behavior<Command> create(String collectionName, EmbeddingService embeddingService,
                                           QdrantService qdrantService) {
        return Behaviors.setup(context -> register(context, collectionName, embeddingService, qdrantService));
    }
    
    private static Behavior<Command> register(ActorContext<Command> context, String collectionName,
                                              EmbeddingService embeddingService, QdrantService qdrantService) {
        // Register with Receptionist
        context.getSystem().receptionist().tell(
            Receptionist.register(SEARCH_WORKER_KEY, context.getSelf())
        );
        
        log.info("SearchWorker registered with Receptionist: {}", context.getSelf().path());
        
        return new SearchWorkerActor(context, collectionName, embeddingService, qdrantService);
    }
    
    // Message handler
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(SearchCommand.class, this::onSearchCommand)
            .onMessage(SearchFinished.class, this::onSearchFinished)
            .build();
    }
    
    // Handle search request: run the blocking work off the actor's thread
    private Behavior<Command> onSearchCommand(SearchCommand command) {
        CompletableFuture<SearchResponse> future = CompletableFuture.supplyAsync(
            () -> runSearch(command.searchQuery), blockingExecutor);
        
        getContext().pipeToSelf(future, (response, failure) -> new SearchFinished(
            response != null ? response : new SearchResponse("Search failed: " + failure.getMessage()),
            command.replyTo));
        
        return this;
    }
    
    // Reply once the blocking search is done
    private Behavior<Command> onSearchFinished(SearchFinished finished) {
        finished.replyTo.tell(finished.response);
        log.debug("SearchResponse sent to requester");
        return this;
    }
    
    // Vectorize + Qdrant search + re-rank (blocking, runs on the blocking-io dispatcher)
    private SearchResponse runSearch(SearchQuery searchQuery) {
        log.info("Received SearchQuery: {}", searchQuery);
        
        try {
            // Extract query
            String queryText = searchQuery.getQuery();
            int topK = searchQuery.getTopK();
            
            log.debug("Processing search query: '{}', topK: {}", queryText, topK);
            
            // Vectorize query
            float[] queryVector = embeddingService.vectorize(queryText, null);
            
            log.debug("Query vectorized: {} dimensions", queryVector.length);
            
            // No known words: the vector is all zeros and cosine similarity is
            // meaningless, so report "no relevant content" instead of searching
            if (EmbeddingService.isZeroVector(queryVector)) {
                log.info("Query has no words in the embedding vocabulary, skipping search");
                return new SearchResponse(new ArrayList<>(), new ArrayList<>());
            }
            
            // Search Qdrant, dropping chunks below the relevance threshold
            List<QdrantService.SearchResult> searchResults = 
                qdrantService.searchWithScores(collectionName, queryVector, topK, minScore);
            
            log.info("Search completed: {} results found", searchResults.size());
            
            // Re-rank by keywords
            searchResults = qdrantService.rerankByKeywords(queryText, searchResults);
            
            log.info("Re-ranking completed");
            
            // Build response
            List<String> chunks = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            
            for (QdrantService.SearchResult result : searchResults) {
                chunks.add(result.getText());
                scores.add((double) result.getScore());
            }
            
            return new SearchResponse(chunks, scores);
            
        } catch (Exception e) {
            // Error handling
            log.error("Error processing search query: {}", e.getMessage(), e);
            
            return new SearchResponse("Search failed: " + e.getMessage());
        }
    }
}

