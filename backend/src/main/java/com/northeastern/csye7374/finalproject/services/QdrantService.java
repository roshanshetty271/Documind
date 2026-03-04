package com.northeastern.csye7374.finalproject.services;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.*;
import io.qdrant.client.grpc.Points.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Qdrant Vector Database Service
 * 
 * Provides vector storage and similarity search
 * Uses sentence-window chunking with metadata tracking
 */
public class QdrantService {
    
    private static final Logger log = LoggerFactory.getLogger(QdrantService.class);
    
    private QdrantClient client;
    private static final int DEFAULT_BATCH_SIZE = 50;
    
    // Global counter for unique point IDs
    private static final AtomicLong globalPointId = new AtomicLong(System.currentTimeMillis());
    
    /**
     * Constructor - Initialize Qdrant client
     * Reuses EXACT same connection setup from HW2
     */
    public QdrantService() {
        try {
            // SAME connection setup from HW2 (127.0.0.1, port 6334, no TLS)
            // Using 127.0.0.1 instead of localhost to force IPv4
            this.client = new QdrantClient(
                QdrantGrpcClient.newBuilder("127.0.0.1", 6334, false).build()
            );
            log.info("Connected to Qdrant at 127.0.0.1:6334");
        } catch (Exception e) {
            log.error("Error connecting to Qdrant: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Qdrant client", e);
        }
    }
    
    /**
     * Constructor with custom host and port
     */
    public QdrantService(String host, int port) {
        try {
            this.client = new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build()
            );
            log.info("Connected to Qdrant at {}:{}", host, port);
        } catch (Exception e) {
            log.error("Error connecting to Qdrant at {}:{}: {}", host, port, e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Qdrant client", e);
        }
    }
    
    /**
     * Read file and chunk using SLIDING SENTENCE-WINDOW RETRIEVAL
     * Based on RAG lecture Slides 30-34:
     * - "breaks down documents into smaller units, such as sentences"
     * - "small groups of sentences" for surrounding context
     * - SLIDING WINDOW with 60% overlap for better recall!
     * 
     * Uses Naive Splitting (Slide 22): "split using periods and new lines"
     * Works for ANY text file - clean guides or messy OCR
     * 
     * @param filename Path to file to read
     * @return List of text chunks (5 sentences each, 60% overlap)
     * @throws IOException If file reading fails
     */
    public List<String> readAndChunkFile(String filename) throws IOException {
        log.info("Reading file: {}", filename);
        
        String content = new String(Files.readAllBytes(Paths.get(filename)), StandardCharsets.UTF_8);
        
        // Basic cleanup - works for any file (Universal Sanitizer)
        content = content.replaceAll("[=\\-]{4,}", " ");      // Remove divider lines (=====, -----)
        content = content.replaceAll("[^\\x00-\\x7F]", " ");  // Remove non-ASCII (OCR junk)
        content = content.replaceAll("\\s+", " ");            // Normalize whitespace
        
        // NAIVE SPLITTING: "split using periods and new lines" (Slide 22)
        // Split on . ! ? : followed by space
        String[] rawSentences = content.split("(?<=[.!?:])\\s+");
        
        // Filter out short fragments (noise, page numbers, etc.)
        List<String> sentences = new ArrayList<>();
        for (String s : rawSentences) {
            String trimmed = s.trim();
            if (trimmed.length() >= 20) {
                sentences.add(trimmed);
            }
        }
        
        List<String> chunks = new ArrayList<>();
        
        // SLIDING WINDOW: 5 sentences per chunk, slide by 2 sentences (60% overlap)
        // This improves recall - a query might match the overlap region!
        int windowSize = 5;      // 5 sentences per chunk (more context)
        int slideStep = 2;       // Slide by 2 sentences (60% overlap for better recall)
        
        for (int i = 0; i < sentences.size(); i += slideStep) {
            StringBuilder chunkBuilder = new StringBuilder();
            
            // Build chunk from windowSize sentences starting at position i
            int end = Math.min(i + windowSize, sentences.size());
            for (int j = i; j < end; j++) {
                chunkBuilder.append(sentences.get(j)).append(" ");
            }
            
            String chunk = chunkBuilder.toString().trim();
            
            // Only add substantial chunks (min 80 chars for quality)
            if (chunk.length() >= 80) {
                chunks.add(chunk);
            }
            
            // Stop if we've reached the end
            if (end >= sentences.size()) break;
        }
        
        log.info("Created {} sliding-window chunks from {} (5 sentences, 60% overlap)", chunks.size(), filename);
        return chunks;
    }
    
    /**
     * Create collection in Qdrant
     * SAME collection creation logic from HW2 (lines 136-163)
     * 
     * @param collectionName Name of the collection to create
     * @param vectorSize Dimension of vectors (e.g., 100 from HW2)
     * @throws Exception If collection creation fails
     */
    public void createCollection(String collectionName, int vectorSize) throws Exception {
        try {
            // delete if exists (SAME pattern from HW2)
            try {
                client.deleteCollectionAsync(collectionName).get();
                log.info("Deleted old collection: {}", collectionName);
            } catch (Exception e) {
                // collection doesn't exist, that's fine
                log.debug("Collection {} does not exist (will create new)", collectionName);
            }
            
            // create new collection with cosine similarity (SAME as HW2)
            client.createCollectionAsync(
                CreateCollection.newBuilder()
                    .setCollectionName(collectionName)
                    .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParams(VectorParams.newBuilder()
                            .setSize(vectorSize) // Vector size from HW2: 100
                            .setDistance(Distance.Cosine) // Cosine similarity from HW2
                            .build())
                        .build())
                    .build()
            ).get();
            
            log.info("Created collection: {} with vector size: {}", collectionName, vectorSize);
        } catch (Exception e) {
            log.error("Error creating collection {}: {}", collectionName, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Create collection ONLY if it doesn't already exist
     * Safer for upload endpoints - won't delete existing data
     * 
     * @param collectionName Name of the collection to create
     * @param vectorSize Dimension of vectors
     * @return true if created, false if already exists
     * @throws Exception If creation fails (other than "already exists")
     */
    public boolean createCollectionIfNotExists(String collectionName, int vectorSize) throws Exception {
        try {
            // Try to create collection
            client.createCollectionAsync(
                CreateCollection.newBuilder()
                    .setCollectionName(collectionName)
                    .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParams(VectorParams.newBuilder()
                            .setSize(vectorSize)
                            .setDistance(Distance.Cosine)
                            .build())
                        .build())
                    .build()
            ).get();
            
            log.info("Created new collection: {} with vector size: {}", collectionName, vectorSize);
            System.out.println("[QDRANT] Created new collection: " + collectionName);
            return true;
            
        } catch (Exception e) {
            // Check if error is "already exists"
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("already exists") || 
                (errorMsg.contains("Collection") && errorMsg.contains("exists")))) {
                log.info("Collection {} already exists, using existing", collectionName);
                System.out.println("[QDRANT] Collection '" + collectionName + "' already exists, using it");
                return false;
            } else {
                // Real error
                log.error("Error creating collection {}: {}", collectionName, errorMsg, e);
                throw e;
            }
        }
    }
    
    /**
     * Insert chunks with vectors into Qdrant (legacy method - no metadata)
     * Calls the new method with "unknown" as filename for backwards compatibility.
     */
    public void insertChunks(String collectionName, List<String> chunks, List<float[]> vectors) throws Exception {
        insertChunksWithMetadata(collectionName, chunks, vectors, "unknown");
    }
    
    /**
     * Insert chunks with vectors AND METADATA into Qdrant
     * ENHANCED insertion logic with document tracking
     * Uses batching for efficiency (batch size 50 from HW2)
     * 
     * STORES METADATA:
     * - text: The actual chunk content
     * - full_text: Full text (for search results)
     * - filename: Source document name (e.g., "BigData.pdf")
     * - uploadedAt: ISO timestamp when uploaded
     * - chunkIndex: Position in the original document
     * 
     * @param collectionName Name of the collection
     * @param chunks List of text chunks
     * @param vectors List of corresponding vectors (float arrays)
     * @param filename Source filename for tracking
     * @throws Exception If insertion fails
     */
    public void insertChunksWithMetadata(String collectionName, List<String> chunks, List<float[]> vectors, String filename) throws Exception {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("Chunks and vectors lists must have same size");
        }
        
        String timestamp = Instant.now().toString();
        
        try {
            System.out.println("💾 [QDRANT] Storing " + chunks.size() + " vectors with metadata:");
            System.out.println("   📄 filename: " + filename);
            System.out.println("   🕐 uploadedAt: " + timestamp);
            
            log.info("Inserting {} chunks into Qdrant collection: {} (source: {})", chunks.size(), collectionName, filename);
            
            List<PointStruct> points = new ArrayList<>();
            
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                float[] vector = vectors.get(i);
                
                // Convert float[] to List<Float> for Qdrant
                List<Float> vectorList = new ArrayList<>();
                for (float v : vector) {
                    vectorList.add(v);
                }
                
                // Use globally unique ID to avoid conflicts across uploads
                long uniqueId = globalPointId.incrementAndGet();
                
                // Create point with ID, vector, and METADATA payload
                PointStruct point = PointStruct.newBuilder()
                    .setId(PointId.newBuilder().setNum(uniqueId).build())
                    .setVectors(Vectors.newBuilder()
                        .setVector(io.qdrant.client.grpc.Points.Vector.newBuilder()
                            .addAllData(vectorList)
                            .build())
                        .build())
                    // METADATA: text preview (first 200 chars)
                    .putPayload("text", io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue(chunk.substring(0, Math.min(chunk.length(), 200)))
                        .build())
                    // METADATA: full text for RAG context
                    .putPayload("full_text", io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue(chunk)
                        .build())
                    // METADATA: source filename (e.g., "BigData.pdf")
                    .putPayload("filename", io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue(filename)
                        .build())
                    // METADATA: upload timestamp
                    .putPayload("uploadedAt", io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue(timestamp)
                        .build())
                    // METADATA: chunk index in original document
                    .putPayload("chunkIndex", io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setIntegerValue(i)
                        .build())
                    .build();
                
                points.add(point);
                
                // Insert in batches of 50 (efficient bulk insertion from HW2)
                if (points.size() >= DEFAULT_BATCH_SIZE || i == chunks.size() - 1) {
                    client.upsertAsync(
                        UpsertPoints.newBuilder()
                            .setCollectionName(collectionName)
                            .addAllPoints(points)
                            .build()
                    ).get();
                    
                    log.info("Inserted {} points (batch) from {}", points.size(), filename);
                    points.clear();
                }
            }
            
            System.out.println("✅ [QDRANT] Successfully stored " + chunks.size() + " vectors from " + filename);
            log.info("Done inserting all {} chunks from {}", chunks.size(), filename);
            
        } catch (Exception e) {
            log.error("Error inserting chunks from {} into {}: {}", filename, collectionName, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Search for similar vectors
     * SAME search logic from HW2 (lines 208-241)
     * Returns top-K results with cosine similarity
     * 
     * @param collectionName Name of the collection to search
     * @param queryVector Query vector (float array)
     * @param topK Number of results to return (5 from HW2)
     * @return List of matching text chunks
     * @throws Exception If search fails
     */
    public List<String> search(String collectionName, float[] queryVector, int topK) throws Exception {
        List<SearchResult> results = searchWithScores(collectionName, queryVector, topK);
        
        List<String> texts = new ArrayList<>();
        for (SearchResult r : results) {
            texts.add(r.getText());
        }
        return texts;
    }
    
    /**
     * Search and return results with scores AND METADATA
     * Extended version that returns text, similarity scores, and source document info
     * 
     * @param collectionName Name of the collection to search
     * @param queryVector Query vector
     * @param topK Number of results
     * @return List of SearchResult objects with text, score, and filename
     * @throws Exception If search fails
     */
    public List<SearchResult> searchWithScores(String collectionName, float[] queryVector, int topK) throws Exception {
        try {
            System.out.println("🔎 [QDRANT] Searching collection '" + collectionName + "' for top " + topK + " results...");
            
            log.info("Starting Qdrant search: collection={}, topK={}, vectorDim={}", 
                collectionName, topK, queryVector.length);
            
            // Convert float[] to List<Float>
            List<Float> queryVectorList = new ArrayList<>();
            for (float v : queryVector) {
                queryVectorList.add(v);
            }
            
            log.debug("Query vector converted, starting async search...");
            
            // Search in Qdrant with TIMEOUT to prevent infinite hangs
            List<ScoredPoint> results;
            try {
                results = client.searchAsync(
                    SearchPoints.newBuilder()
                        .setCollectionName(collectionName)
                        .addAllVector(queryVectorList)
                        .setLimit(topK)
                        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                        .build()
                ).get(30, TimeUnit.SECONDS); // 30 second timeout!
            } catch (TimeoutException te) {
                log.error("Qdrant search TIMED OUT after 30 seconds!");
                throw new RuntimeException("Qdrant search timed out after 30 seconds", te);
            }
            
            log.info("Search completed. Found {} results", results.size());
            
            List<SearchResult> searchResults = new ArrayList<>();
            
            // Track source files for logging
            Map<String, Integer> sourceCount = new HashMap<>();
            StringBuilder scoresStr = new StringBuilder();
            
            for (ScoredPoint point : results) {
                // Get full text from payload
                String text = point.getPayloadOrDefault("full_text", 
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue("No text")
                        .build()
                ).getStringValue();
                
                // Get filename from payload (or "unknown" for legacy data)
                String filename = point.getPayloadOrDefault("filename",
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setStringValue("unknown")
                        .build()
                ).getStringValue();
                
                // Get chunk index if available
                int chunkIndex = (int) point.getPayloadOrDefault("chunkIndex",
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                        .setIntegerValue(-1)
                        .build()
                ).getIntegerValue();
                
                searchResults.add(new SearchResult(text, point.getScore(), filename, chunkIndex));
                
                // Track source counts
                sourceCount.merge(filename, 1, Integer::sum);
                
                // Build scores string for logging
                if (scoresStr.length() > 0) scoresStr.append(", ");
                scoresStr.append(String.format("%.3f", point.getScore()));
            }
            
            // Log source files
            StringBuilder sourceStr = new StringBuilder();
            for (Map.Entry<String, Integer> entry : sourceCount.entrySet()) {
                if (sourceStr.length() > 0) sourceStr.append(", ");
                sourceStr.append(entry.getKey()).append(" (").append(entry.getValue()).append(")");
            }
            
            System.out.println("🔎 [QDRANT] Found " + results.size() + " results (scores: " + scoresStr + ")");
            System.out.println("📚 [QDRANT] Sources: " + sourceStr);
            
            return searchResults;
            
        } catch (Exception e) {
            log.error("Error searching in collection {}: {}", collectionName, e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Search with METADATA FILTER by filename
     * Allows filtering results to specific source documents
     * 
     * Based on Professor's RAG lecture - enables targeted retrieval
     * 
     * @param collectionName Collection to search
     * @param queryVector Query vector
     * @param topK Number of results
     * @param filenameFilter Filter by this filename (null = no filter)
     * @return Filtered search results
     * @throws Exception If search fails
     */
    public List<SearchResult> searchWithFilter(String collectionName, float[] queryVector, int topK, String filenameFilter) throws Exception {
        try {
            // Convert float[] to List<Float>
            List<Float> queryVectorList = new ArrayList<>();
            for (float v : queryVector) {
                queryVectorList.add(v);
            }
            
            // Build search request
            SearchPoints.Builder searchBuilder = SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(queryVectorList)
                .setLimit(topK)
                .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build());
            
            // Add filename filter if provided
            if (filenameFilter != null && !filenameFilter.trim().isEmpty()) {
                Filter filter = Filter.newBuilder()
                    .addMust(Condition.newBuilder()
                        .setField(FieldCondition.newBuilder()
                            .setKey("filename")
                            .setMatch(Match.newBuilder()
                                .setKeyword(filenameFilter)
                                .build())
                            .build())
                        .build())
                    .build();
                searchBuilder.setFilter(filter);
                System.out.println("[QDRANT] Filtering by filename: " + filenameFilter);
                log.info("Search with filter: filename={}", filenameFilter);
            }
            
            // Execute search with timeout
            List<ScoredPoint> results;
            try {
                results = client.searchAsync(searchBuilder.build()).get(30, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                log.error("Qdrant filtered search TIMED OUT!");
                throw new RuntimeException("Qdrant filtered search timed out", te);
            }
            
            // Convert to SearchResult objects
            List<SearchResult> searchResults = new ArrayList<>();
            for (ScoredPoint point : results) {
                String text = point.getPayloadOrDefault("full_text",
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setStringValue("No text").build()
                ).getStringValue();
                
                String filename = point.getPayloadOrDefault("filename",
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setStringValue("unknown").build()
                ).getStringValue();
                
                int chunkIndex = (int) point.getPayloadOrDefault("chunkIndex",
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder().setIntegerValue(-1).build()
                ).getIntegerValue();
                
                searchResults.add(new SearchResult(text, point.getScore(), filename, chunkIndex));
            }
            
            System.out.println("🔎 [QDRANT] Filtered search found " + results.size() + " results");
            return searchResults;
            
        } catch (Exception e) {
            log.error("Error in filtered search: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * KEYWORD RE-RANKING: Re-rank search results by query keyword overlap
     * 
     * Based on Professor's RAG lecture - improves precision after vector search
     * Vector search optimizes recall, keyword re-ranking improves precision
     * 
     * Algorithm:
     * 1. Extract keywords from query (remove stopwords)
     * 2. Score each chunk by how many keywords it contains
     * 3. Re-sort by keyword score (preserves original order for ties)
     * 
     * @param query Original user query
     * @param results Search results to re-rank
     * @return Re-ranked results (best matches first)
     */
    public List<SearchResult> rerankByKeywords(String query, List<SearchResult> results) {
        if (results == null || results.size() <= 1) {
            return results;
        }
        
        // Stopwords to filter out
        java.util.Set<String> stopwords = java.util.Set.of(
            "what", "is", "the", "a", "an", "how", "does", "explain", "describe",
            "tell", "me", "about", "can", "you", "please", "in", "of", "to", "and",
            "for", "with", "this", "that", "it", "are", "was", "were", "be", "been"
        );
        
        // Extract keywords from query
        java.util.Set<String> queryWords = new java.util.HashSet<>();
        for (String word : query.toLowerCase().split("\\s+")) {
            String clean = word.replaceAll("[^a-z0-9]", "");
            if (clean.length() > 2 && !stopwords.contains(clean)) {
                queryWords.add(clean);
            }
        }
        
        if (queryWords.isEmpty()) {
            System.out.println("[RERANK] No keywords extracted, keeping original order");
            return results;
        }
        
        System.out.println("[RERANK] Keywords: " + queryWords);
        
        // Score each result by keyword matches
        List<java.util.Map.Entry<SearchResult, Integer>> scored = new ArrayList<>();
        for (SearchResult result : results) {
            String lowerText = result.getText().toLowerCase();
            int keywordScore = 0;
            for (String keyword : queryWords) {
                if (lowerText.contains(keyword)) {
                    keywordScore++;
                }
            }
            scored.add(java.util.Map.entry(result, keywordScore));
        }
        
        // Sort by keyword score (highest first), stable sort preserves original order for ties
        scored.sort((a, b) -> {
            int cmp = b.getValue().compareTo(a.getValue());
            if (cmp == 0) {
                // Keep original vector similarity order for ties
                return Float.compare(b.getKey().getScore(), a.getKey().getScore());
            }
            return cmp;
        });
        
        List<SearchResult> reranked = new ArrayList<>();
        for (java.util.Map.Entry<SearchResult, Integer> entry : scored) {
            reranked.add(entry.getKey());
        }
        
        System.out.println("[RERANK] Re-ranked " + results.size() + " results by keyword relevance");
        log.info("Re-ranked {} results using {} keywords", results.size(), queryWords.size());
        
        return reranked;
    }
    
    /**
     * Close the Qdrant client connection
     */
    public void close() {
        try {
            if (client != null) {
                client.close();
                log.info("Qdrant client connection closed");
            }
        } catch (Exception e) {
            log.error("Error closing Qdrant client: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Data class to hold search results with scores AND METADATA
     * Enhanced to include source document tracking
     */
    public static class SearchResult {
        private final String text;
        private final float score;
        private final String filename;
        private final int chunkIndex;
        
        public SearchResult(String text, float score) {
            this(text, score, "unknown", -1);
        }
        
        public SearchResult(String text, float score, String filename, int chunkIndex) {
            this.text = text;
            this.score = score;
            this.filename = filename;
            this.chunkIndex = chunkIndex;
        }
        
        public String getText() {
            return text;
        }
        
        public float getScore() {
            return score;
        }
        
        public String getFilename() {
            return filename;
        }
        
        public int getChunkIndex() {
            return chunkIndex;
        }
        
        @Override
        public String toString() {
            return String.format("SearchResult{score=%.4f, file='%s', chunk=%d, text='%s'}", 
                score, filename, chunkIndex, text.substring(0, Math.min(50, text.length())) + "...");
        }
    }
}
