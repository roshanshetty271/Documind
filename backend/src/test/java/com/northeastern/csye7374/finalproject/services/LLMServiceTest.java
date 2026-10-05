package com.northeastern.csye7374.finalproject.services;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LLMService that make no API calls.
 */
class LLMServiceTest {

    // ---- prompt building ----

    @Test
    void promptNumbersChunksAndEndsWithTheQuestion() {
        String prompt = LLMService.buildPrompt("What is an actor?",
            List.of("Actors process one message at a time.", "Supervisors restart actors."));

        int first = prompt.indexOf("[1] Actors process one message at a time.");
        int second = prompt.indexOf("[2] Supervisors restart actors.");
        int question = prompt.indexOf("QUESTION: What is an actor?");
        assertTrue(first >= 0, prompt);
        assertTrue(second > first, prompt);
        assertTrue(question > second, "question must come after the context");
        assertTrue(prompt.trim().endsWith("ANSWER:"), prompt);
    }

    @Test
    void promptSkipsBlankChunksAndKeepsNumberingContiguous() {
        String prompt = LLMService.buildPrompt("q", Arrays.asList("real chunk", "   ", null, "second chunk"));

        assertTrue(prompt.contains("[1] real chunk"));
        assertTrue(prompt.contains("[2] second chunk"));
        assertFalse(prompt.contains("\n[3] "), "only two passages should be numbered");
    }

    @Test
    void promptRestrictsAnswerToContextAndAsksForCitations() {
        String prompt = LLMService.buildPrompt("What is an actor?", List.of("Actors process messages."));

        assertTrue(prompt.contains("Answer ONLY with information from the numbered context passages"), prompt);
        assertTrue(prompt.contains("Do not use general knowledge"), prompt);
        assertFalse(prompt.contains("you may use your general knowledge"), prompt);
        assertTrue(prompt.contains("reply exactly: \"" + LLMService.NOT_FOUND_ANSWER + "\""), prompt);
        assertTrue(prompt.contains("Cite the passages you used by number"), prompt);
    }

    @Test
    void promptMarksMissingContext() {
        assertTrue(LLMService.buildPrompt("q", List.of()).contains("[No relevant documents found]"));
    }

    // ---- retry and fallback ----

    /** ChatModel that throws the given error for the first `failures` calls, then answers. */
    static class FlakyChatModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final int failures;
        final RuntimeException error;

        FlakyChatModel(int failures, RuntimeException error) {
            this.failures = failures;
            this.error = error;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.incrementAndGet() <= failures) {
                throw error;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("grounded answer [1]"))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    @Test
    void transientFailuresAreRetried() {
        FlakyChatModel model = new FlakyChatModel(2, new TransientAiException("503 from OpenAI"));

        String answer = new LLMService(model, 3, 1).generateAnswer("q", List.of("c"));

        assertEquals("grounded answer [1]", answer);
        assertEquals(3, model.calls.get());
    }

    @Test
    void retriesAreBounded() {
        FlakyChatModel model = new FlakyChatModel(Integer.MAX_VALUE, new TransientAiException("timeout"));

        assertThrows(RuntimeException.class, () -> new LLMService(model, 3, 1).generateAnswer("q", List.of("c")));
        assertEquals(3, model.calls.get());
    }

    @Test
    void clientErrorsAreNotRetried() {
        FlakyChatModel model = new FlakyChatModel(Integer.MAX_VALUE, new NonTransientAiException("401 invalid key"));

        assertThrows(RuntimeException.class, () -> new LLMService(model, 3, 1).generateAnswer("q", List.of("c")));
        assertEquals(1, model.calls.get());
    }

    @Test
    void fallbackAnswerListsThePassages() {
        String fallback = LLMService.fallbackAnswer(Arrays.asList("first passage", " ", "second passage"));

        assertTrue(fallback.startsWith("The answer service is unavailable right now."), fallback);
        assertTrue(fallback.contains("[1] first passage"), fallback);
        assertTrue(fallback.contains("[2] second passage"), fallback);
    }

    @Test
    void apiKeyConstructorBuildsWithoutCallingOpenAi() {
        assertNotNull(new LLMService("sk-test-not-a-real-key").getChatModel());
    }
}
