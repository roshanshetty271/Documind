package com.northeastern.csye7374.finalproject.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for QdrantService against a real Qdrant started by
 * Testcontainers. Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class QdrantServiceTest {

    // Same major/minor line as the io.qdrant:client version in the pom
    @Container
    private static final GenericContainer<?> QDRANT = new GenericContainer<>("qdrant/qdrant:v1.7.4")
        .withExposedPorts(6333, 6334)
        .waitingFor(Wait.forHttp("/").forPort(6333));

    private static final int VECTOR_SIZE = 4;

    private QdrantService service;
    private String collection;

    @BeforeEach
    void setUp() {
        service = new QdrantService(QDRANT.getHost(), QDRANT.getMappedPort(6334));
        collection = "test_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    /** One-hot vector: makes the nearest neighbour of each query obvious. */
    private static float[] axis(int i) {
        float[] v = new float[VECTOR_SIZE];
        v[i] = 1f;
        return v;
    }

    private static List<float[]> axes(int count) {
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            vectors.add(axis(i));
        }
        return vectors;
    }

    @Test
    void createCollectionMakesAnEmptySearchableCollection() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);

        assertTrue(service.searchWithScores(collection, axis(0), 5).isEmpty());
    }

    @Test
    void createCollectionReplacesExistingData() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);
        service.insertChunks(collection, List.of("old chunk"), axes(1));

        service.createCollection(collection, VECTOR_SIZE);

        assertTrue(service.searchWithScores(collection, axis(0), 5).isEmpty());
    }

    @Test
    void createCollectionIfNotExistsKeepsExistingCollection() throws Exception {
        assertTrue(service.createCollectionIfNotExists(collection, VECTOR_SIZE));
        service.insertChunks(collection, List.of("kept chunk"), axes(1));

        assertFalse(service.createCollectionIfNotExists(collection, VECTOR_SIZE));
        assertEquals(List.of("kept chunk"), service.search(collection, axis(0), 5));
    }

    @Test
    void searchReturnsNearestChunksFirst() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);
        service.insertChunks(collection,
            List.of("machine learning", "deep learning", "language processing"), axes(3));

        List<String> results = service.search(collection, axis(1), 2);

        assertEquals(2, results.size());
        assertEquals("deep learning", results.get(0));
    }

    @Test
    void searchWithScoresReturnsScoresAndMetadata() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);
        service.insertChunksWithMetadata(collection,
            List.of("vector databases store embeddings", "actors exchange messages"), axes(2), "notes.txt");

        List<QdrantService.SearchResult> results = service.searchWithScores(collection, axis(1), 2);

        assertEquals(2, results.size());
        QdrantService.SearchResult top = results.get(0);
        assertEquals("actors exchange messages", top.getText());
        assertEquals("notes.txt", top.getFilename());
        assertEquals(1, top.getChunkIndex());
        assertEquals(1.0f, top.getScore(), 1e-4);
        assertEquals(0.0f, results.get(1).getScore(), 1e-4);
        assertTrue(top.getScore() >= results.get(1).getScore(), "results must be sorted by score");
    }

    @Test
    void searchWithFilterOnlyReturnsThatFile() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);
        service.insertChunksWithMetadata(collection, List.of("from a"), List.of(axis(0)), "a.txt");
        service.insertChunksWithMetadata(collection, List.of("from b"), List.of(axis(0)), "b.txt");

        List<QdrantService.SearchResult> results = service.searchWithFilter(collection, axis(0), 5, "b.txt");

        assertEquals(1, results.size());
        assertEquals("from b", results.get(0).getText());
    }

    @Test
    void insertChunksRejectsSizeMismatch() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);

        assertThrows(IllegalArgumentException.class,
            () -> service.insertChunks(collection, List.of("Chunk 1", "Chunk 2"), axes(1)));
    }

    // ---- deterministic point IDs ----

    @Test
    void reuploadingAFileOverwritesInsteadOfDuplicating() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);
        List<String> chunks = List.of("chunk zero", "chunk one", "chunk two");

        service.insertChunksWithMetadata(collection, chunks, axes(3), "lecture.txt");
        service.insertChunksWithMetadata(collection, chunks, axes(3), "lecture.txt");

        assertEquals(3, service.search(collection, axis(0), 100).size());
    }

    @Test
    void shorterReuploadRemovesOldTailChunks() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);
        service.insertChunksWithMetadata(collection, List.of("v1 zero", "v1 one", "v1 two"), axes(3), "lecture.txt");

        service.insertChunksWithMetadata(collection, List.of("v2 zero", "v2 one"), axes(2), "lecture.txt");

        List<String> all = service.search(collection, axis(0), 100);
        assertEquals(2, all.size());
        assertTrue(all.containsAll(List.of("v2 zero", "v2 one")), all.toString());
    }

    @Test
    void differentFilesDoNotOverwriteEachOther() throws Exception {
        service.createCollection(collection, VECTOR_SIZE);
        service.insertChunksWithMetadata(collection, List.of("from a"), List.of(axis(0)), "a.txt");
        service.insertChunksWithMetadata(collection, List.of("from b"), List.of(axis(0)), "b.txt");

        assertEquals(2, service.search(collection, axis(0), 100).size());
    }
}
