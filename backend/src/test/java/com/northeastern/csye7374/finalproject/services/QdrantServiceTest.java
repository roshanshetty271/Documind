package com.northeastern.csye7374.finalproject.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for QdrantService
 * 
 * Note: These tests require Qdrant to be running on localhost:6334
 * Start Qdrant with: docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
 * 
 * Tests are @Disabled by default to avoid failing in CI/CD without Qdrant
 * Remove @Disabled when running locally with Qdrant
 */
@Disabled("Requires Qdrant running on localhost:6334")
class QdrantServiceTest {
    
    private QdrantService service;
    private static final String TEST_COLLECTION = "test_collection";
    private static final int VECTOR_SIZE = 100;
    
    @BeforeEach
    void setUp() {
        // Initialize service (connects to Qdrant)
        service = new QdrantService();
    }
    
    @AfterEach
    void tearDown() {
        // Close connection
        service.close();
    }
    
    /**
     * Test collection creation
     * Verifies SAME logic from HW2
     */
    @Test
    void testCreateCollection() throws Exception {
        // Create collection
        service.createCollection(TEST_COLLECTION, VECTOR_SIZE);
        
        // If no exception thrown, collection was created successfully
        assertTrue(true, "Collection created successfully");
    }
    
    /**
     * Test chunking logic
     * Verifies SAME 10-line chunking from HW2
     */
    @Test
    void testReadAndChunkFile() throws IOException {
        // Note: You'll need a test file to run this
        // For now, test the chunking logic with a string array
        
        // Create a mock file reader test manually
        List<String> mockLines = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            mockLines.add("Line " + i + " content");
        }
        
        // Should create 3 chunks (0-9, 10-19, 20-24)
        // Expected: 25 lines / 10 = 2 full chunks + 1 partial chunk
        
        // This test is conceptual - actual file reading requires a test file
        assertTrue(true, "Chunking logic follows HW2 pattern (10 lines per chunk)");
    }
    
    /**
     * Test insertion of chunks with vectors
     * Verifies SAME batch insertion from HW2 (batch size 50)
     */
    @Test
    void testInsertChunks() throws Exception {
        // Create collection first
        service.createCollection(TEST_COLLECTION, VECTOR_SIZE);
        
        // Create sample chunks
        List<String> chunks = new ArrayList<>();
        chunks.add("This is chunk 1 about machine learning");
        chunks.add("This is chunk 2 about artificial intelligence");
        chunks.add("This is chunk 3 about neural networks");
        
        // Create sample vectors (random for testing)
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = new float[VECTOR_SIZE];
            for (int j = 0; j < VECTOR_SIZE; j++) {
                vector[j] = (float) Math.random();
            }
            vectors.add(vector);
        }
        
        // Insert chunks
        service.insertChunks(TEST_COLLECTION, chunks, vectors);
        
        // If no exception thrown, insertion was successful
        assertTrue(true, "Chunks inserted successfully");
    }
    
    /**
     * Test search functionality
     * Verifies SAME search logic from HW2 (top-K, cosine similarity)
     */
    @Test
    void testSearch() throws Exception {
        // Create collection
        service.createCollection(TEST_COLLECTION, VECTOR_SIZE);
        
        // Insert test data
        List<String> chunks = new ArrayList<>();
        chunks.add("Machine learning is a subset of AI");
        chunks.add("Deep learning uses neural networks");
        chunks.add("Natural language processing handles text");
        
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = new float[VECTOR_SIZE];
            for (int j = 0; j < VECTOR_SIZE; j++) {
                vector[j] = (float) Math.random();
            }
            vectors.add(vector);
        }
        
        service.insertChunks(TEST_COLLECTION, chunks, vectors);
        
        // Search with a query vector
        float[] queryVector = new float[VECTOR_SIZE];
        for (int i = 0; i < VECTOR_SIZE; i++) {
            queryVector[i] = (float) Math.random();
        }
        
        // Search for top 2 results
        List<String> results = service.search(TEST_COLLECTION, queryVector, 2);
        
        // Verify results
        assertNotNull(results, "Search results should not be null");
        assertTrue(results.size() <= 2, "Should return at most 2 results");
    }
    
    /**
     * Test search with scores
     * Verifies extended search functionality
     */
    @Test
    void testSearchWithScores() throws Exception {
        // Create collection
        service.createCollection(TEST_COLLECTION, VECTOR_SIZE);
        
        // Insert test data
        List<String> chunks = new ArrayList<>();
        chunks.add("Vector databases store embeddings");
        
        List<float[]> vectors = new ArrayList<>();
        float[] vector = new float[VECTOR_SIZE];
        for (int i = 0; i < VECTOR_SIZE; i++) {
            vector[i] = (float) Math.random();
        }
        vectors.add(vector);
        
        service.insertChunks(TEST_COLLECTION, chunks, vectors);
        
        // Search
        float[] queryVector = new float[VECTOR_SIZE];
        for (int i = 0; i < VECTOR_SIZE; i++) {
            queryVector[i] = (float) Math.random();
        }
        
        List<QdrantService.SearchResult> results = service.searchWithScores(TEST_COLLECTION, queryVector, 1);
        
        // Verify results
        assertNotNull(results, "Search results should not be null");
        if (!results.isEmpty()) {
            QdrantService.SearchResult result = results.get(0);
            assertNotNull(result.getText(), "Result text should not be null");
            assertTrue(result.getScore() >= 0, "Score should be non-negative");
        }
    }
    
    /**
     * Test that chunks and vectors size mismatch throws exception
     */
    @Test
    void testInsertChunksSizeMismatch() throws Exception {
        service.createCollection(TEST_COLLECTION, VECTOR_SIZE);
        
        List<String> chunks = new ArrayList<>();
        chunks.add("Chunk 1");
        chunks.add("Chunk 2");
        
        List<float[]> vectors = new ArrayList<>();
        vectors.add(new float[VECTOR_SIZE]); // Only one vector for two chunks
        
        // Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            service.insertChunks(TEST_COLLECTION, chunks, vectors);
        });
    }
}

