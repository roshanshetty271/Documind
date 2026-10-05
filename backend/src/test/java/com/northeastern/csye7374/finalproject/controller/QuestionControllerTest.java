package com.northeastern.csye7374.finalproject.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Question validation happens before anything is sent to the cluster,
 * so these tests need no ActorSystem.
 */
class QuestionControllerTest {

    private final QuestionController controller = new QuestionController(null);

    private ResponseEntity<Map<String, Object>> ask(String question) {
        Map<String, String> body = new HashMap<>();
        body.put("question", question);
        return controller.askQuestion(body).join();
    }

    @Test
    void rejectsMissingOrBlankQuestion() {
        assertEquals(400, ask(null).getStatusCode().value());
        assertEquals(400, ask("   ").getStatusCode().value());
        assertEquals(400, controller.askQuestion(new HashMap<>()).join().getStatusCode().value());
    }

    @Test
    void rejectsOverlongQuestion() {
        ResponseEntity<Map<String, Object>> response = ask("x".repeat(QuestionController.MAX_QUESTION_LENGTH + 1));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("error")).contains("too long"));
    }

    @Test
    void acceptsQuestionAtTheLimit() {
        assertNull(QuestionController.validateQuestion("x".repeat(QuestionController.MAX_QUESTION_LENGTH)));
        assertNull(QuestionController.validateQuestion("What is an actor?"));
    }
}
