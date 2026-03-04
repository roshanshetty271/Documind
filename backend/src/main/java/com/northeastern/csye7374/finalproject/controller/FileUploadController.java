package com.northeastern.csye7374.finalproject.controller;

import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.QdrantService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * File Upload Controller - Dynamic document indexing
 * 
 * Upload PDF/TXT files to expand the knowledge base
 * Files are chunked, vectorized, and stored in Qdrant
 * 
 * @author Roshan Shetty & Rithwik
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    private final EmbeddingService embeddingService;
    private final QdrantService qdrantService;
    
    private static final String COLLECTION_NAME = "course_documents";
    private static final int WINDOW_SIZE = 5;      // 5 sentences per chunk
    private static final int SLIDE_STEP = 2;       // 60% overlap
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    // Constructor
    public FileUploadController() {
        log.info("🚀 Initializing FileUploadController...");
        
        // Initialize services
        this.embeddingService = new EmbeddingService();
        this.qdrantService = new QdrantService();
        
        log.info("✅ FileUploadController ready for file uploads!");
    }

    // Upload and index PDF/TXT file - POST /api/upload
    @PostMapping("/upload")
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file) {
        long startTime = System.currentTimeMillis();
        Map<String, Object> response = new HashMap<>();
        
        String filename = file.getOriginalFilename();
        
        // ---═══
        // DETAILED LOGGING FOR DEMO
        // ---═══
        System.out.println();
        System.out.println("---");
        System.out.println("[UPLOAD] Received: " + filename + " (" + formatBytes(file.getSize()) + ")");
        System.out.println("---");
        
        log.info("📤 [Upload] Received file: {} ({} bytes)", filename, file.getSize());
        
        try {
            // Validate file
            if (file.isEmpty()) {
                throw new IllegalArgumentException("File is empty");
            }
            
            if (file.getSize() > MAX_FILE_SIZE) {
                throw new IllegalArgumentException("File too large. Max size: 10MB");
            }
            
            String extension = getFileExtension(filename).toLowerCase();
            if (!extension.equals("pdf") && !extension.equals("txt")) {
                throw new IllegalArgumentException("Invalid file type. Only PDF and TXT allowed.");
            }
            
            // Step 1: Extract text from file
            System.out.println("📖 [EXTRACT] Reading " + extension.toUpperCase() + " content...");
            log.info("📄 [Extract] Extracting text from {}...", extension.toUpperCase());
            
            String textContent;
            if (extension.equals("pdf")) {
                textContent = extractTextFromPDF(file);
            } else {
                textContent = new String(file.getBytes(), StandardCharsets.UTF_8);
            }
            
            System.out.println("📖 [EXTRACT] Extracted " + textContent.length() + " characters");
            log.info("📄 [Extract] Extracted {} characters", textContent.length());
            
            // Step 2: Chunk the text using sliding sentence-window
            System.out.println("[CHUNK] Applying sentence-window chunking (5 sentences, 60% overlap)...");
            log.info("✂️ [Chunk] Applying sentence-window chunking...");
            
            List<String> chunks = chunkText(textContent);
            
            System.out.println("[CHUNK] Created " + chunks.size() + " chunks");
            log.info("✂️ [Chunk] Created {} chunks", chunks.size());
            
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Could not extract meaningful content from file");
            }
            
            // Step 3: Vectorize all chunks with Word2Vec
            System.out.println("[EMBED] Vectorizing " + chunks.size() + " chunks with Word2Vec (300-dim)...");
            log.info("🧠 [Embed] Vectorizing {} chunks with Word2Vec...", chunks.size());
            
            List<float[]> vectors = embeddingService.vectorizeAll(chunks);
            
            System.out.println("[EMBED] Vectorization complete!");
            log.info("🧠 [Embed] Vectorization complete");
            
            // Step 4: Ensure collection exists (creates if not present)
            System.out.println("[QDRANT] Ensuring collection exists...");
            qdrantService.createCollectionIfNotExists(COLLECTION_NAME, embeddingService.getVectorSize());
            
            // Step 5: Insert into Qdrant WITH METADATA
            System.out.println("[QDRANT] Storing vectors with metadata (filename: " + filename + ")...");
            log.info("📥 [Qdrant] Inserting chunks into vector database with metadata...");
            
            // *** KEY FIX: Pass filename for metadata tracking ***
            qdrantService.insertChunksWithMetadata(COLLECTION_NAME, chunks, vectors, filename);
            
            log.info("📥 [Qdrant] Insertion complete!");
            
            // Calculate processing time
            long endTime = System.currentTimeMillis();
            double processingTime = (endTime - startTime) / 1000.0;
            
            // Build success response
            response.put("success", true);
            response.put("filename", filename);
            response.put("chunksIndexed", chunks.size());
            response.put("charactersProcessed", textContent.length());
            response.put("processingTime", processingTime);
            response.put("message", "File indexed successfully! You can now ask questions about it.");
            
            System.out.println("---");
            System.out.println("✅ [COMPLETE] Indexed \"" + filename + "\"");
            System.out.println("   📦 " + chunks.size() + " chunks stored");
            System.out.println("   📄 " + textContent.length() + " characters processed");
            System.out.println("   ⏱️  " + String.format("%.2f", processingTime) + " seconds");
            System.out.println("---");
            System.out.println();
            
            log.info("[Upload] SUCCESS! {} indexed with {} chunks in {}s", 
                    filename, chunks.size(), String.format("%.2f", processingTime));
            
        } catch (Exception e) {
            System.out.println("---");
            System.out.println("[ERROR] Failed to process " + filename);
            System.out.println("   Reason: " + e.getMessage());
            System.out.println("---");
            
            log.error("❌ [Upload] Error processing file: {}", e.getMessage(), e);
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("filename", filename);
        }
        
        return response;
    }

    /**
     * Extract text content from PDF using Apache PDFBox
     */
    private String extractTextFromPDF(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * Chunk text using sliding sentence-window approach
     * Same algorithm as QdrantService for consistency
     * 
     * @param content Raw text content
     * @return List of text chunks
     */
    private List<String> chunkText(String content) {
        // Basic cleanup
        content = content.replaceAll("[=\\-]{4,}", " ");      // Remove divider lines
        content = content.replaceAll("[^\\x00-\\x7F]", " ");  // Remove non-ASCII
        content = content.replaceAll("\\s+", " ");            // Normalize whitespace
        
        // Split into sentences
        String[] rawSentences = content.split("(?<=[.!?:])\\s+");
        
        // Filter out short fragments
        List<String> sentences = new ArrayList<>();
        for (String s : rawSentences) {
            String trimmed = s.trim();
            if (trimmed.length() >= 20) {
                sentences.add(trimmed);
            }
        }
        
        // Build sliding window chunks
        List<String> chunks = new ArrayList<>();
        
        for (int i = 0; i < sentences.size(); i += SLIDE_STEP) {
            StringBuilder chunkBuilder = new StringBuilder();
            
            int end = Math.min(i + WINDOW_SIZE, sentences.size());
            for (int j = i; j < end; j++) {
                chunkBuilder.append(sentences.get(j)).append(" ");
            }
            
            String chunk = chunkBuilder.toString().trim();
            if (chunk.length() >= 80) {
                chunks.add(chunk);
            }
            
            if (end >= sentences.size()) break;
        }
        
        return chunks;
    }

    /**
     * Get file extension from filename
     */
    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
    
    /**
     * Format bytes into human-readable format
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Health check endpoint for upload service
     * GET /api/upload/health
     */
    @GetMapping("/upload/health")
    public Map<String, Object> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("service", "FileUploadController");
        health.put("maxFileSize", "10MB");
        health.put("supportedFormats", Arrays.asList("PDF", "TXT"));
        health.put("metadataTracking", true);
        return health;
    }
}
