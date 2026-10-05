package com.northeastern.csye7374.finalproject.services;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

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
}
