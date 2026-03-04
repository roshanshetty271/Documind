package com.northeastern.csye7374.finalproject;

import com.northeastern.csye7374.finalproject.services.EmbeddingService;
import com.northeastern.csye7374.finalproject.services.QdrantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * IndexingScript - Index course materials into Qdrant
 * 
 * Uses sentence-window chunking with 60% overlap
 * Tracks metadata for source attribution
 * 
 * Word2Vec Model: GoogleNews-vectors-negative300-SLIM.bin
 * - 300k vocabulary, 300 dimensions
 * - Loads in ~60 seconds
 * 
 * Run ONCE before starting the cluster!
 * 
 * Usage: mvn exec:java -Dexec.mainClass="com.northeastern.csye7374.finalproject.IndexingScript"
 */
public class IndexingScript {
    
    private static final Logger log = LoggerFactory.getLogger(IndexingScript.class);
    
    // Updated path - files are in Documents subdirectory
    private static final String COURSE_FILES_DIR = "CourseFiles/Documents";
    private static final String COLLECTION_NAME = "course_documents";
    
    public static void main(String[] args) {
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("🚀 COURSE MATERIALS INDEXING SCRIPT");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println("📖 Strategy: Sentence-Window Retrieval (Professor's Lecture)");
        System.out.println("📐 Chunks: 5 sentences, 60% overlap");
        System.out.println("📄 Metadata: filename, uploadedAt, chunkIndex");
        System.out.println("════════════════════════════════════════════════════════════════");
        System.out.println();
        
        log.info("Starting Course Materials Indexing with Metadata Tracking");
        
        try {
            // Initialize services
            System.out.println("🔧 [INIT] Initializing services...");
            QdrantService qdrantService = new QdrantService();
            System.out.println("✅ [INIT] QdrantService connected to localhost:6334");
            
            EmbeddingService embeddingService = new EmbeddingService();
            System.out.println("✅ [INIT] EmbeddingService ready (Word2Vec SLIM, " + embeddingService.getVocabularySize() + " words)");
            
            log.info("✅ Services initialized");
            
            // Find all text files
            File courseDir = new File(COURSE_FILES_DIR);
            if (!courseDir.exists() || !courseDir.isDirectory()) {
                System.out.println("❌ [ERROR] Directory not found: " + courseDir.getAbsolutePath());
                log.error("❌ Directory not found: {}", courseDir.getAbsolutePath());
                return;
            }
            
            File[] files = courseDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
            
            if (files == null || files.length == 0) {
                System.out.println("❌ [ERROR] No .txt files found in " + COURSE_FILES_DIR);
                log.warn("❌ No .txt files found in {}", COURSE_FILES_DIR);
                return;
            }
            
            System.out.println();
            System.out.println("📄 [SCAN] Found " + files.length + " .txt files to index:");
            for (File f : files) {
                System.out.println("   • " + f.getName());
            }
            System.out.println();
            
            log.info("📄 Found {} .txt files to index", files.length);
            
            // Create Qdrant collection FIRST (deletes existing if any)
            System.out.println("🗄️ [QDRANT] Creating collection: " + COLLECTION_NAME);
            qdrantService.createCollection(COLLECTION_NAME, embeddingService.getVectorSize());
            System.out.println("✅ [QDRANT] Collection created (300-dim, cosine similarity)");
            System.out.println();
            
            // Process each file INDIVIDUALLY (so each gets its own filename metadata!)
            int totalChunks = 0;
            
            for (File file : files) {
                String filename = file.getName();
                System.out.println("════════════════════════════════════════════════════════════════");
                System.out.println("📄 [PROCESSING] " + filename);
                
                // Read and chunk file
                System.out.println("   ✂️ Chunking with sentence-window...");
                List<String> chunks = qdrantService.readAndChunkFile(file.getPath());
                System.out.println("   📦 Created " + chunks.size() + " chunks");
                
                if (chunks.isEmpty()) {
                    System.out.println("   ⚠️ No chunks created (file may be empty), skipping...");
                    continue;
                }
                
                // Vectorize chunks
                System.out.println("   🧠 Vectorizing " + chunks.size() + " chunks...");
                List<float[]> vectors = embeddingService.vectorizeAll(chunks);
                System.out.println("   ✅ Vectorization complete");
                
                // Insert WITH METADATA (filename!)
                System.out.println("   💾 Storing with metadata (filename: " + filename + ")...");
                qdrantService.insertChunksWithMetadata(COLLECTION_NAME, chunks, vectors, filename);
                System.out.println("   ✅ Indexed " + chunks.size() + " chunks from " + filename);
                
                totalChunks += chunks.size();
                log.info("Indexed {} chunks from {}", chunks.size(), filename);
            }
            
            // Close connection
            qdrantService.close();
            
            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════════");
            System.out.println("✅ INDEXING COMPLETE!");
            System.out.println("════════════════════════════════════════════════════════════════");
            System.out.println("📊 Total chunks indexed: " + totalChunks);
            System.out.println("📁 From " + files.length + " files");
            System.out.println("🗄️ Collection: " + COLLECTION_NAME);
            System.out.println("📐 Vector size: 300 dimensions");
            System.out.println("🎯 Chunking: Sliding Window (5 sentences, 60% overlap)");
            System.out.println("📄 Metadata: Each chunk knows its source file!");
            System.out.println();
            System.out.println("🚀 You can now start the backend with: mvn spring-boot:run");
            System.out.println("════════════════════════════════════════════════════════════════");
            System.out.println();
            
            log.info("INDEXING COMPLETE! {} total chunks from {} files", totalChunks, files.length);
            
        } catch (Exception e) {
            System.out.println("════════════════════════════════════════════════════════════════");
            System.out.println("❌ INDEXING FAILED!");
            System.out.println("   Reason: " + e.getMessage());
            System.out.println("════════════════════════════════════════════════════════════════");
            log.error("Indexing failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
