package com.northeastern.csye7374.finalproject.actors;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import com.northeastern.csye7374.finalproject.messages.SearchQuery;
import com.northeastern.csye7374.finalproject.messages.SearchResponse;
import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.LLMService;
import com.northeastern.csye7374.finalproject.services.QdrantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blocking calls (Qdrant, OpenAI) must not run on the actor's own thread:
 * one actor must be able to have two blocking calls in flight at once, and
 * they must run on the dedicated blocking-io dispatcher.
 */
class BlockingDispatcherTest {

    private ActorTestKit testKit;

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create(TestConfigs.local());
    }

    @AfterEach
    void tearDown() {
        testKit.shutdownTestKit();
    }

    /** Embedding fake: every word maps to a fixed non-zero vector. */
    static class FakeEmbeddingService extends EmbeddingService {
        FakeEmbeddingService() {
            super((org.deeplearning4j.models.word2vec.Word2Vec) null); // no model needed
        }

        @Override
        public float[] vectorize(String text) {
            float[] v = new float[getVectorSize()];
            v[0] = 1f;
            return v;
        }
    }

    /** Qdrant fake that blocks until `parties` searches are running at the same time. */
    static class BlockingQdrantService extends QdrantService {
        final CountDownLatch allInside;
        final Set<String> threads = ConcurrentHashMap.newKeySet();

        BlockingQdrantService(int parties) {
            super("127.0.0.1", 6334); // gRPC channel is lazy, nothing connects
            this.allInside = new CountDownLatch(parties);
        }

        @Override
        public List<SearchResult> searchWithScores(String collectionName, float[] queryVector, int topK,
                                                   Double minScore) throws Exception {
            threads.add(Thread.currentThread().getName());
            allInside.countDown();
            if (!allInside.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("searches were not running concurrently");
            }
            return List.of(new SearchResult("chunk", 0.9f, "f.txt", 0));
        }
    }

    /** ChatModel fake that blocks until `parties` calls are running at the same time. */
    static class BlockingChatModel implements ChatModel {
        final CountDownLatch allInside;
        final Set<String> threads = ConcurrentHashMap.newKeySet();

        BlockingChatModel(int parties) {
            this.allInside = new CountDownLatch(parties);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            threads.add(Thread.currentThread().getName());
            allInside.countDown();
            try {
                if (!allInside.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("LLM calls were not running concurrently");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    @Test
    void searchWorkerRunsSearchesConcurrentlyOnBlockingDispatcher() {
        BlockingQdrantService qdrant = new BlockingQdrantService(2);
        try {
            ActorRef<SearchWorkerActor.Command> worker = testKit.spawn(
                SearchWorkerActor.create("test", new FakeEmbeddingService(), qdrant));

            TestProbe<SearchResponse> probe1 = testKit.createTestProbe();
            TestProbe<SearchResponse> probe2 = testKit.createTestProbe();
            worker.tell(new SearchWorkerActor.SearchCommand(new SearchQuery("first question", 5), probe1.getRef()));
            worker.tell(new SearchWorkerActor.SearchCommand(new SearchQuery("second question", 5), probe2.getRef()));

            SearchResponse r1 = probe1.receiveMessage(Duration.ofSeconds(10));
            SearchResponse r2 = probe2.receiveMessage(Duration.ofSeconds(10));
            assertTrue(r1.isSuccess(), "first search failed: " + r1.getErrorMessage());
            assertTrue(r2.isSuccess(), "second search failed: " + r2.getErrorMessage());
            assertEquals(List.of("chunk"), r1.getChunks());

            assertFalse(qdrant.threads.isEmpty());
            for (String thread : qdrant.threads) {
                assertTrue(thread.contains("blocking-io-dispatcher"), "search ran on " + thread);
            }
        } finally {
            qdrant.close();
        }
    }

    @Test
    void llmActorRunsCallsConcurrentlyOnBlockingDispatcher() {
        BlockingChatModel chatModel = new BlockingChatModel(2);
        ActorRef<LLMActor.Command> llm = testKit.spawn(LLMActor.create(new LLMService(chatModel), "Node-test"));

        TestProbe<LLMActor.LLMResponse> probe1 = testKit.createTestProbe();
        TestProbe<LLMActor.LLMResponse> probe2 = testKit.createTestProbe();
        llm.tell(new LLMActor.GenerateAnswer("q1", List.of("c1"), probe1.getRef(), null));
        llm.tell(new LLMActor.GenerateAnswer("q2", List.of("c2"), probe2.getRef(), null));

        LLMActor.LLMResponse r1 = probe1.receiveMessage(Duration.ofSeconds(10));
        LLMActor.LLMResponse r2 = probe2.receiveMessage(Duration.ofSeconds(10));
        assertTrue(r1.success, "first call failed: " + r1.errorMessage);
        assertTrue(r2.success, "second call failed: " + r2.errorMessage);
        assertEquals("ok", r1.answer);

        for (String thread : chatModel.threads) {
            assertTrue(thread.contains("blocking-io-dispatcher"), "LLM call ran on " + thread);
        }
    }

    @Test
    void llmActorFallsBackToPassagesWhenOpenAiKeepsFailing() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new org.springframework.ai.retry.TransientAiException("OpenAI unavailable");
            }

            @Override
            public ChatOptions getDefaultOptions() {
                return null;
            }
        };
        ActorRef<LLMActor.Command> llm = testKit.spawn(
            LLMActor.create(new LLMService(failing, 2, 1), "Node-test"));

        TestProbe<LLMActor.LLMResponse> probe = testKit.createTestProbe();
        llm.tell(new LLMActor.GenerateAnswer("q", List.of("Actors process one message at a time."),
            probe.getRef(), null));

        LLMActor.LLMResponse response = probe.receiveMessage(Duration.ofSeconds(5));
        assertTrue(response.success);
        assertTrue(response.answer.contains("[1] Actors process one message at a time."), response.answer);
    }
}
