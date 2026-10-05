package com.northeastern.csye7374.finalproject.controller;

import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.QdrantService;
import org.deeplearning4j.models.word2vec.Word2Vec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileUploadController with fake embedding/Qdrant services.
 */
class FileUploadControllerTest {

    static class FakeEmbeddingService extends EmbeddingService {
        FakeEmbeddingService() {
            super((Word2Vec) null); // no model needed
        }

        @Override
        public float[] vectorize(String text) {
            float[] v = new float[getVectorSize()];
            v[0] = 1f;
            return v;
        }
    }

    static class FakeQdrantService extends QdrantService {
        final boolean failInsert;
        final List<String> inserted = new ArrayList<>();

        FakeQdrantService(boolean failInsert) {
            super("127.0.0.1", 6334); // lazy channel, nothing connects
            this.failInsert = failInsert;
        }

        @Override
        public boolean createCollectionIfNotExists(String collectionName, int vectorSize) {
            return false;
        }

        @Override
        public void insertChunksWithMetadata(String collectionName, List<String> chunks,
                                             List<float[]> vectors, String filename) throws Exception {
            if (failInsert) {
                throw new RuntimeException("UNAVAILABLE: io exception");
            }
            inserted.addAll(chunks);
        }
    }

    private FakeQdrantService qdrant;

    @AfterEach
    void tearDown() {
        if (qdrant != null) {
            qdrant.close();
        }
    }

    private FileUploadController controller(boolean failInsert) {
        qdrant = new FakeQdrantService(failInsert);
        return new FileUploadController(new FakeEmbeddingService(), qdrant);
    }

    static MockMultipartFile textFile(String name) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 6; i++) {
            text.append("Sentence number ").append(i).append(" explains how actors exchange messages. ");
        }
        return new MockMultipartFile("file", name, "text/plain", text.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void successfulUploadReturns200() {
        ResponseEntity<Map<String, Object>> response = controller(false).uploadFile(textFile("notes.txt"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
        assertFalse(qdrant.inserted.isEmpty());
    }

    @Test
    void qdrantFailureReturns500NotOk() {
        ResponseEntity<Map<String, Object>> response = controller(true).uploadFile(textFile("notes.txt"));

        assertEquals(500, response.getStatusCode().value());
        assertEquals(false, response.getBody().get("success"));
        assertTrue(String.valueOf(response.getBody().get("error")).contains("UNAVAILABLE"));
    }

    @Test
    void invalidFileReturns400() {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        MockMultipartFile wrongType = new MockMultipartFile("file", "slides.pptx", "application/octet-stream", new byte[] {1});

        assertEquals(400, controller(false).uploadFile(empty).getStatusCode().value());
        assertEquals(400, controller(false).uploadFile(wrongType).getStatusCode().value());
    }

    @Test
    void pdfOverPageLimitIsRejected() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i <= FileUploadController.MAX_PDF_PAGES; i++) {
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            doc.save(bytes);
        }
        MockMultipartFile pdf = new MockMultipartFile("file", "huge.pdf", "application/pdf", bytes.toByteArray());

        ResponseEntity<Map<String, Object>> response = controller(false).uploadFile(pdf);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("error")).contains("Max pages"),
            String.valueOf(response.getBody().get("error")));
        assertTrue(qdrant.inserted.isEmpty());
    }
}
