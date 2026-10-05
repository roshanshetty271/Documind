package com.northeastern.csye7374.finalproject.actors;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.receptionist.Receptionist;
import com.northeastern.csye7374.finalproject.messages.SearchResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrchestratorActor tests with fake search workers and LLM actors
 * registered under the real Receptionist keys.
 */
class OrchestratorActorTest {

    // Fresh ActorSystem per test so the Receptionist only holds that test's fakes
    private ActorTestKit testKit;
    private final AtomicInteger names = new AtomicInteger();

    @BeforeEach
    void setUp() {
        testKit = ActorTestKit.create(TestConfigs.local());
    }

    @AfterEach
    void tearDown() {
        testKit.shutdownTestKit();
    }

    // ---- fakes ----

    /** Search worker that holds requests until it has `batch` of them, then answers all (echoes the query). */
    static Behavior<SearchWorkerActor.Command> batchingWorker(int batch) {
        return Behaviors.setup(ctx -> {
            List<SearchWorkerActor.SearchCommand> pending = new ArrayList<>();
            return Behaviors.receive(SearchWorkerActor.Command.class)
                .onMessage(SearchWorkerActor.SearchCommand.class, cmd -> {
                    String q = cmd.searchQuery.getQuery();
                    if (q.startsWith("warmup")) {
                        cmd.replyTo.tell(new SearchResponse(List.of("chunk for " + q), List.of(0.9)));
                        return Behaviors.same();
                    }
                    pending.add(cmd);
                    if (pending.size() >= batch) {
                        for (SearchWorkerActor.SearchCommand c : pending) {
                            String query = c.searchQuery.getQuery();
                            c.replyTo.tell(new SearchResponse(List.of("chunk for " + query), List.of(0.9)));
                        }
                        pending.clear();
                    }
                    return Behaviors.same();
                })
                .build();
        });
    }

    /** LLM actor that answers with the question and the chunks it received. */
    static Behavior<LLMActor.Command> echoLlm(String nodeId) {
        return Behaviors.receive(LLMActor.Command.class)
            .onMessage(LLMActor.GenerateAnswer.class, cmd -> {
                cmd.replyTo.tell(new LLMActor.LLMResponse(
                    "answer to " + cmd.query + " using " + cmd.contextChunks, 0.01, nodeId));
                return Behaviors.same();
            })
            .build();
    }

    <T> ActorRef<T> spawnRegistered(Behavior<T> behavior,
                                           akka.actor.typed.receptionist.ServiceKey<T> key) {
        ActorRef<T> ref = testKit.spawn(behavior, "fake-" + names.incrementAndGet());
        TestProbe<Receptionist.Registered> ack = testKit.createTestProbe();
        testKit.system().receptionist().tell(Receptionist.register(key, ref, ack.getRef()));
        ack.receiveMessage();
        return ref;
    }

    /** Sends warmup queries until the orchestrator has discovered workers and LLM actors. */
    void awaitReady(ActorRef<OrchestratorActor.Command> orchestrator) {
        TestProbe<OrchestratorActor.QueryResult> probe = testKit.createTestProbe();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            orchestrator.tell(new OrchestratorActor.ProcessQuery("warmup", probe.getRef()));
            OrchestratorActor.QueryResult result = probe.receiveMessage(Duration.ofSeconds(10));
            if (result.success) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        fail("Orchestrator never discovered the fake actors");
    }

    // ---- tests ----

    @Test
    void concurrentQueriesReplyToTheirOwnCallers() {
        spawnRegistered(batchingWorker(2), SearchWorkerActor.SEARCH_WORKER_KEY);
        spawnRegistered(echoLlm("Node-test"), LLMActor.SERVICE_KEY);
        ActorRef<OrchestratorActor.Command> orchestrator =
            testKit.spawn(OrchestratorActor.create("Node-test"), "orchestrator");
        awaitReady(orchestrator);

        TestProbe<OrchestratorActor.QueryResult> callerA = testKit.createTestProbe();
        TestProbe<OrchestratorActor.QueryResult> callerB = testKit.createTestProbe();

        // Both questions are in flight on the same orchestrator before either search returns
        orchestrator.tell(new OrchestratorActor.ProcessQuery("question A", callerA.getRef()));
        orchestrator.tell(new OrchestratorActor.ProcessQuery("question B", callerB.getRef()));

        OrchestratorActor.QueryResult resultA = callerA.receiveMessage(Duration.ofSeconds(5));
        OrchestratorActor.QueryResult resultB = callerB.receiveMessage(Duration.ofSeconds(5));

        assertTrue(resultA.success, "caller A should get a successful result");
        assertTrue(resultB.success, "caller B should get a successful result");
        assertTrue(resultA.answer.contains("question A"), "A got: " + resultA.answer);
        assertTrue(resultA.answer.contains("chunk for question A"), "A got: " + resultA.answer);
        assertTrue(resultB.answer.contains("question B"), "B got: " + resultB.answer);
        assertTrue(resultB.answer.contains("chunk for question B"), "B got: " + resultB.answer);

        // Nobody gets a second (crossed) reply
        callerA.expectNoMessage(Duration.ofMillis(200));
        callerB.expectNoMessage(Duration.ofMillis(200));
    }

    // ---- per-step timeouts and retry ----

    private static final String SHORT_TIMEOUTS =
        "documind.orchestrator.search-timeout = 300ms\n"
        + "documind.orchestrator.llm-timeout = 300ms\n";

    /** Search worker that echoes immediately. */
    static Behavior<SearchWorkerActor.Command> echoWorker() {
        return batchingWorker(1);
    }

    @Test
    void searchTimeoutIsRetriedOnAnotherWorker() {
        testKit.shutdownTestKit();
        testKit = ActorTestKit.create(TestConfigs.local(SHORT_TIMEOUTS));

        // A worker on a "dead" node never answers
        spawnRegistered(Behaviors.<SearchWorkerActor.Command>ignore(), SearchWorkerActor.SEARCH_WORKER_KEY);
        ActorRef<SearchWorkerActor.Command> good =
            spawnRegistered(echoWorker(), SearchWorkerActor.SEARCH_WORKER_KEY);
        spawnRegistered(echoLlm("Node-test"), LLMActor.SERVICE_KEY);
        ActorRef<OrchestratorActor.Command> orchestrator =
            testKit.spawn(OrchestratorActor.create("Node-test"), "orchestrator");
        awaitReady(orchestrator);

        // Round-robin means at least one of two queries starts on the dead worker
        TestProbe<OrchestratorActor.QueryResult> caller = testKit.createTestProbe();
        for (int i = 0; i < 2; i++) {
            orchestrator.tell(new OrchestratorActor.ProcessQuery("question " + i, caller.getRef()));
            OrchestratorActor.QueryResult result = caller.receiveMessage(Duration.ofSeconds(3));
            assertTrue(result.success, "query " + i + " failed: " + result.errorMessage);
            assertTrue(result.answer.contains("question " + i));
            assertEquals(good.path().toString(), result.workerPath);
        }
    }

    @Test
    void searchFailsFastWhenNoOtherWorkerAnswers() {
        testKit.shutdownTestKit();
        testKit = ActorTestKit.create(TestConfigs.local(SHORT_TIMEOUTS));

        spawnRegistered(Behaviors.<SearchWorkerActor.Command>ignore(), SearchWorkerActor.SEARCH_WORKER_KEY);
        spawnRegistered(Behaviors.<SearchWorkerActor.Command>ignore(), SearchWorkerActor.SEARCH_WORKER_KEY);
        spawnRegistered(echoLlm("Node-test"), LLMActor.SERVICE_KEY);
        ActorRef<OrchestratorActor.Command> orchestrator =
            testKit.spawn(OrchestratorActor.create("Node-test"), "orchestrator");

        TestProbe<OrchestratorActor.QueryResult> caller = testKit.createTestProbe();
        // Retry until discovery has happened, then expect a timeout error (two attempts, ~600ms)
        OrchestratorActor.QueryResult result;
        long deadline = System.currentTimeMillis() + 5_000;
        do {
            orchestrator.tell(new OrchestratorActor.ProcessQuery("lost question", caller.getRef()));
            result = caller.receiveMessage(Duration.ofSeconds(3));
        } while (result.errorMessage != null && result.errorMessage.startsWith("No search workers")
                 && System.currentTimeMillis() < deadline);

        assertFalse(result.success);
        assertTrue(result.errorMessage.startsWith("Search failed"), result.errorMessage);
    }

    @Test
    void llmTimeoutIsRetriedOnAnotherLlmActor() {
        testKit.shutdownTestKit();
        testKit = ActorTestKit.create(TestConfigs.local(SHORT_TIMEOUTS));

        spawnRegistered(echoWorker(), SearchWorkerActor.SEARCH_WORKER_KEY);
        spawnRegistered(Behaviors.<LLMActor.Command>ignore(), LLMActor.SERVICE_KEY);
        spawnRegistered(echoLlm("Node-good"), LLMActor.SERVICE_KEY);
        ActorRef<OrchestratorActor.Command> orchestrator =
            testKit.spawn(OrchestratorActor.create("Node-test"), "orchestrator");
        awaitReady(orchestrator);

        TestProbe<OrchestratorActor.QueryResult> caller = testKit.createTestProbe();
        for (int i = 0; i < 2; i++) {
            orchestrator.tell(new OrchestratorActor.ProcessQuery("question " + i, caller.getRef()));
            OrchestratorActor.QueryResult result = caller.receiveMessage(Duration.ofSeconds(3));
            assertTrue(result.success, "query " + i + " failed: " + result.errorMessage);
            assertEquals("Node-good", result.llmNodeId);
        }
    }

    // ---- no relevant content ----

    @Test
    void emptySearchResultAnswersNoRelevantContentWithoutCallingLlm() {
        spawnRegistered(Behaviors.<SearchWorkerActor.Command>receiveMessage(cmd -> {
            SearchWorkerActor.SearchCommand search = (SearchWorkerActor.SearchCommand) cmd;
            search.replyTo.tell(new SearchResponse(List.of(), List.of()));
            return Behaviors.same();
        }), SearchWorkerActor.SEARCH_WORKER_KEY);
        TestProbe<LLMActor.Command> llm = testKit.createTestProbe();
        TestProbe<Receptionist.Registered> ack = testKit.createTestProbe();
        testKit.system().receptionist().tell(Receptionist.register(LLMActor.SERVICE_KEY, llm.getRef(), ack.getRef()));
        ack.receiveMessage();
        ActorRef<OrchestratorActor.Command> orchestrator =
            testKit.spawn(OrchestratorActor.create("Node-test"), "orchestrator");

        TestProbe<OrchestratorActor.QueryResult> caller = testKit.createTestProbe();
        OrchestratorActor.QueryResult result;
        long deadline = System.currentTimeMillis() + 5_000;
        do {
            orchestrator.tell(new OrchestratorActor.ProcessQuery("zzqx qqzx", caller.getRef()));
            result = caller.receiveMessage(Duration.ofSeconds(3));
        } while (!result.success && System.currentTimeMillis() < deadline);

        assertTrue(result.success, String.valueOf(result.errorMessage));
        assertEquals(OrchestratorActor.NO_RELEVANT_CONTENT, result.answer);
        assertEquals(0, result.chunksFound);
        llm.expectNoMessage(Duration.ofMillis(200));
    }
}
