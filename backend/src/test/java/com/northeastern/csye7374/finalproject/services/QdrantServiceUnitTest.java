package com.northeastern.csye7374.finalproject.services;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for QdrantService logic that needs no running Qdrant:
 * sentence-window chunking and keyword re-ranking.
 */
class QdrantServiceUnitTest {

    @TempDir
    Path tempDir;

    private QdrantService service;

    @BeforeEach
    void setUp() {
        // The gRPC channel is created lazily, so nothing connects here
        service = new QdrantService("127.0.0.1", 6334);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    private static String sentence(int i) {
        return "Sentence number " + i + " talks about distributed actors.";
    }

    private List<String> chunk(String text) throws Exception {
        Path file = tempDir.resolve("doc.txt");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return service.readAndChunkFile(file.toString());
    }

    // ---- chunking: 5-sentence window, slide by 2 ----

    @Test
    void chunksUseFiveSentenceWindowsSlidingByTwo() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 9; i++) {
            text.append(sentence(i)).append(' ');
        }

        List<String> chunks = chunk(text.toString());

        // windows start at sentences 1, 3, 5; the third one reaches the end
        assertEquals(3, chunks.size());
        assertEquals(String.join(" ", sentence(1), sentence(2), sentence(3), sentence(4), sentence(5)), chunks.get(0));
        assertEquals(String.join(" ", sentence(3), sentence(4), sentence(5), sentence(6), sentence(7)), chunks.get(1));
        assertEquals(String.join(" ", sentence(5), sentence(6), sentence(7), sentence(8), sentence(9)), chunks.get(2));
    }

    @Test
    void fiveSentencesMakeExactlyOneChunk() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            text.append(sentence(i)).append('\n');
        }

        List<String> chunks = chunk(text.toString());

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).startsWith(sentence(1)));
        assertTrue(chunks.get(0).endsWith(sentence(5)));
    }

    @Test
    void shortFragmentsAndDividersAreDropped() throws Exception {
        String text = "Page 1. ========== " + sentence(1) + " Ok. " + sentence(2) + " " + sentence(3);

        List<String> chunks = chunk(text);

        assertEquals(1, chunks.size());
        assertEquals(String.join(" ", sentence(1), sentence(2), sentence(3)), chunks.get(0));
    }

    @Test
    void tooLittleTextGivesNoChunks() throws Exception {
        assertTrue(chunk("A short line that is long enough.").isEmpty());
    }

    // ---- re-ranking: distinct keyword hits first, then cosine score ----

    private static QdrantService.SearchResult result(String text, float score) {
        return new QdrantService.SearchResult(text, score);
    }

    @Test
    void rerankOrdersByDistinctKeywordHitsThenScore() {
        QdrantService.SearchResult noHits = result("Nothing relevant here", 0.95f);
        QdrantService.SearchResult oneHitHighScore = result("The actor model", 0.90f);
        QdrantService.SearchResult repeatedOneHit = result("actor actor actor actor", 0.80f);
        QdrantService.SearchResult twoHits = result("Supervision restarts a failed actor", 0.50f);

        List<QdrantService.SearchResult> reranked = service.rerankByKeywords(
            "What is actor supervision?",
            new ArrayList<>(List.of(noHits, oneHitHighScore, repeatedOneHit, twoHits)));

        // "actor" + "supervision" beats one keyword; a repeated keyword counts once,
        // so the two one-hit results are ordered by their vector score
        assertEquals(List.of(twoHits, oneHitHighScore, repeatedOneHit, noHits), reranked);
    }

    @Test
    void rerankKeepsOrderWhenQueryHasOnlyStopwords() {
        List<QdrantService.SearchResult> results = List.of(result("first", 0.2f), result("second", 0.9f));

        assertSame(results, service.rerankByKeywords("what is the", results));
    }

    @Test
    void rerankHandlesTrivialInputs() {
        assertNull(service.rerankByKeywords("anything", null));
        List<QdrantService.SearchResult> single = List.of(result("only one", 0.1f));
        assertSame(single, service.rerankByKeywords("anything", single));
    }

    // ---- deterministic point IDs ----

    @Test
    void pointIdIsStableUuidPerFileAndChunk() {
        String id = QdrantService.pointIdFor("lecture.pdf", 3);

        assertEquals(id, QdrantService.pointIdFor("lecture.pdf", 3));
        assertEquals(id, java.util.UUID.fromString(id).toString(), "must be a valid UUID");
        assertNotEquals(id, QdrantService.pointIdFor("lecture.pdf", 4));
        assertNotEquals(id, QdrantService.pointIdFor("other.pdf", 3));
    }
}
