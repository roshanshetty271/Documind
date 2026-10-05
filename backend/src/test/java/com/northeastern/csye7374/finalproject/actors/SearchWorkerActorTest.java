package com.northeastern.csye7374.finalproject.actors;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import com.northeastern.csye7374.finalproject.messages.SearchQuery;
import com.northeastern.csye7374.finalproject.messages.SearchResponse;
import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.QdrantService;
import org.deeplearning4j.models.word2vec.Word2Vec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchWorkerActor: relevance threshold and unknown-word queries.
 */
class SearchWorkerActorTest {

    private ActorTestKit testKit;
    private RecordingQdrantService qdrant;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create(TestConfigs.local("documind.search.min-score = 0.42"));
        qdrant = new RecordingQdrantService();
    }

    @AfterEach
    void tearDown() {
        qdrant.close();
        testKit.shutdownTestKit();
    }

    /** Knows only the word "actor"; everything else is out of vocabulary. */
    static class TinyVocabularyEmbedding extends EmbeddingService {
        TinyVocabularyEmbedding() {
            super((Word2Vec) null); // no model needed
        }

        @Override
        public float[] vectorize(String text) {
            float[] v = new float[getVectorSize()];
            if (text.toLowerCase().contains("actor")) {
                v[0] = 1f;
            }
            return v;
        }
    }

    static class RecordingQdrantService extends QdrantService {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Double> minScore = new AtomicReference<>();

        RecordingQdrantService() {
            super("127.0.0.1", 6334); // lazy channel, nothing connects
        }

        @Override
        public List<SearchResult> searchWithScores(String collectionName, float[] queryVector, int topK,
                                                   Double minScore) {
            calls.incrementAndGet();
            this.minScore.set(minScore);
            return List.of(new SearchResult("Actors process one message at a time.", 0.8f, "notes.txt", 0));
        }
    }

    private SearchResponse search(String query) {
        ActorRef<SearchWorkerActor.Command> worker = testKit.spawn(
            SearchWorkerActor.create("test", new TinyVocabularyEmbedding(), qdrant));
        TestProbe<SearchResponse> probe = testKit.createTestProbe();
        worker.tell(new SearchWorkerActor.SearchCommand(new SearchQuery(query, 5), probe.getRef()));
        return probe.receiveMessage(Duration.ofSeconds(5));
    }

    @Test
    void passesConfiguredMinimumScoreToQdrant() {
        SearchResponse response = search("what is an actor");

        assertTrue(response.isSuccess());
        assertEquals(1, response.getChunks().size());
        assertEquals(0.42, qdrant.minScore.get(), 1e-9);
    }

    @Test
    void queryWithOnlyUnknownWordsReturnsNoChunksWithoutSearching() {
        SearchResponse response = search("zzqx qqzx");

        assertTrue(response.isSuccess());
        assertTrue(response.getChunks().isEmpty());
        assertEquals(0, qdrant.calls.get(), "Qdrant must not be searched with a zero vector");
    }

    @Test
    void zeroVectorDetection() {
        assertTrue(EmbeddingService.isZeroVector(new float[3]));
        assertTrue(EmbeddingService.isZeroVector(null));
        assertFalse(EmbeddingService.isZeroVector(new float[] {0f, 0.1f, 0f}));
    }
}
