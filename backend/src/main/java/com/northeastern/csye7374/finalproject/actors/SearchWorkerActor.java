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

import com.northeastern.csye7374.finalproject.messages.SearchQuery;
import com.northeastern.csye7374.finalproject.messages.SearchResponse;
import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.QdrantService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

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
    
    private final QdrantService qdrantService;
    private final EmbeddingService embeddingService;
    private final String collectionName;
    private SearchWorkerActor(ActorContext<Command> context, String collectionName, EmbeddingService embeddingService) {
        super(context);
        this.collectionName = collectionName;
        this.embeddingService = embeddingService;
        
        try {
            // Initialize Qdrant service
            this.qdrantService = new QdrantService();
            
            log.info("SearchWorkerActor initialized for collection: {}", collectionName);
            
        } catch (Exception e) {
            log.error("Error initializing SearchWorkerActor: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize SearchWorkerActor", e);
        }
    }
    
    // Factory method - registers with Receptionist
    public static Behavior<Command> create(String collectionName, EmbeddingService embeddingService) {
        return Behaviors.setup(context -> {
            // Register with Receptionist
            context.getSystem().receptionist().tell(
                Receptionist.register(SEARCH_WORKER_KEY, context.getSelf())
            );
            
            log.info("SearchWorker registered with Receptionist: {}", context.getSelf().path());
            
            return new SearchWorkerActor(context, collectionName, embeddingService);
        });
    }
    
    // Message handler
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(SearchCommand.class, this::onSearchCommand)
            .build();
    }
    
    // Handle search request
    private Behavior<Command> onSearchCommand(SearchCommand command) {
        SearchQuery searchQuery = command.searchQuery;
        
        log.info("Received SearchQuery: {}", searchQuery);
        
        try {
            // Extract query
            String queryText = searchQuery.getQuery();
            int topK = searchQuery.getTopK();
            
            log.debug("Processing search query: '{}', topK: {}", queryText, topK);
            
            // Vectorize query
            float[] queryVector = embeddingService.vectorize(queryText, null);
            
            log.debug("Query vectorized: {} dimensions", queryVector.length);
            
            // Search Qdrant
            List<QdrantService.SearchResult> searchResults = 
                qdrantService.searchWithScores(collectionName, queryVector, topK);
            
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
            
            SearchResponse response = new SearchResponse(chunks, scores);
            
            // Reply to sender
            command.replyTo.tell(response);
            
            log.debug("SearchResponse sent to requester");
            
        } catch (Exception e) {
            // Error handling
            log.error("Error processing search query: {}", e.getMessage(), e);
            
            SearchResponse errorResponse = new SearchResponse(
                "Search failed: " + e.getMessage()
            );
            
            // Reply with error
            command.replyTo.tell(errorResponse);
        }
        
        // Return same behavior
        return this;
    }
}

