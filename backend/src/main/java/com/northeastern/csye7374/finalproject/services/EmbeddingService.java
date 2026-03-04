package com.northeastern.csye7374.finalproject.services;

import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer;
import org.deeplearning4j.models.word2vec.Word2Vec;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;

/**
 * Embedding Service using Word2Vec
 * 
 * Uses pre-trained Google News Word2Vec SLIM model
 * - 300k vocabulary, 300-dimensional vectors
 * - Optimized for 8GB RAM systems
 * - Provides semantic text embeddings
 */
public class EmbeddingService {
    
    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    
    private Word2Vec word2Vec;
    private static final int VECTOR_SIZE = 300;
    private static final String DEFAULT_MODEL_PATH = "models/GoogleNews-vectors-negative300-SLIM.bin";
    
    /**
     * Constructor - Load Word2Vec SLIM model
     * SLIM model loads in ~60 seconds (vs 2-3 min for full model)
     */
    public EmbeddingService() {
        this(DEFAULT_MODEL_PATH);
    }
    
    /**
     * Constructor with custom model path
     * 
     * @param modelPath Path to Word2Vec binary model file
     */
    public EmbeddingService(String modelPath) {
        try {
            log.info("Loading Word2Vec SLIM model from: {}", modelPath);
            log.info("SLIM model loads faster (~60 seconds) and uses less RAM (~1.5GB)...");
            
            long startTime = System.currentTimeMillis();
            
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                throw new RuntimeException("Word2Vec model file not found: " + modelPath);
            }
            
            // Load pre-trained Google News Word2Vec model with memory mapping
            // This uses less RAM by loading on-demand
            this.word2Vec = WordVectorSerializer.readWord2VecModel(modelFile, true); // true = memory mapped
            
            long loadTime = (System.currentTimeMillis() - startTime) / 1000;
            log.info("Word2Vec model loaded successfully in {} seconds", loadTime);
            log.info("Vocabulary size: {} words", word2Vec.getVocab().numWords());
            log.info("Vector dimensions: {}", word2Vec.getLayerSize());
            
        } catch (Exception e) {
            log.error("Error loading Word2Vec model: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Word2Vec model", e);
        }
    }
    
    /**
     * Vectorize text using Word2Vec
     * 
     * Strategy: Average all word vectors in the text
     * - Split text into words
     * - Look up each word in Word2Vec model
     * - Average all word vectors
     * - Handle out-of-vocabulary (OOV) words gracefully
     * 
     * @param text Text to vectorize
     * @return 300-dimensional Word2Vec vector
     */
    public float[] vectorize(String text) {
        // Initialize zero vector
        float[] docVector = new float[VECTOR_SIZE];
        Arrays.fill(docVector, 0.0f);
        
        // Tokenize text
        String[] words = text.toLowerCase()
                             .replaceAll("[^a-z\\s]", "")
                             .split("\\s+");
        
        int wordCount = 0;
        int oovCount = 0;
        
        // Sum all word vectors
        for (String word : words) {
            if (word.length() > 2) { // Skip very short words
                if (word2Vec.hasWord(word)) {
                    INDArray wordVector = word2Vec.getWordVectorMatrix(word);
                    
                    // Add word vector to document vector
                    for (int i = 0; i < VECTOR_SIZE; i++) {
                        docVector[i] += wordVector.getFloat(i);
                    }
                    wordCount++;
                } else {
                    oovCount++;
                }
            }
        }
        
        // Average the vectors (if we found any words)
        if (wordCount > 0) {
            for (int i = 0; i < VECTOR_SIZE; i++) {
                docVector[i] /= wordCount;
            }
        }
        
        // Log OOV rate if significant
        if (oovCount > wordCount) {
            log.debug("Text has {} OOV words out of {} total words", oovCount, words.length);
        }
        
        return docVector;
    }
    
    /**
     * Vectorize text using Word2Vec (with vocabulary map for API compatibility)
     * 
     * @param text Text to vectorize
     * @param vocabularyMap Not used in Word2Vec (kept for API compatibility)
     * @return 300-dimensional Word2Vec vector
     */
    public float[] vectorize(String text, Map<String, Integer> vocabularyMap) {
        // Vocabulary map not needed for Word2Vec (pre-trained model has its own vocab)
        return vectorize(text);
    }
    
    /**
     * Vectorize all chunks at once
     * 
     * @param chunks List of text chunks
     * @return List of 300-dimensional vectors (one per chunk)
     */
    public List<float[]> vectorizeAll(List<String> chunks) {
        log.info("Vectorizing {} chunks with Word2Vec", chunks.size());
        
        List<float[]> vectors = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = vectorize(chunks.get(i));
            vectors.add(vector);
            
            // Log progress every 100 chunks
            if ((i + 1) % 100 == 0) {
                log.debug("Vectorized {}/{} chunks", i + 1, chunks.size());
            }
        }
        
        log.info("Vectorized {} chunks into {}-dimensional Word2Vec vectors", chunks.size(), VECTOR_SIZE);
        return vectors;
    }
    
    /**
     * Build vocabulary (for API compatibility)
     * Word2Vec uses pre-trained model vocabulary - no building needed
     * 
     * @param documents List of documents (not used)
     * @return Empty vocabulary map
     */
    public Map<String, Integer> buildVocabulary(List<String> documents) {
        log.debug("buildVocabulary() called - Word2Vec uses pre-trained vocabulary");
        // Return empty map - Word2Vec model already has vocabulary
        return new HashMap<>();
    }
    
    /**
     * Get vector size
     * 
     * @return 300 (Google News Word2Vec dimension)
     */
    public int getVectorSize() {
        return VECTOR_SIZE;
    }
    
    /**
     * Get vocabulary size
     * 
     * @return Number of words in Word2Vec model (~300k for SLIM version)
     */
    public int getVocabularySize() {
        return word2Vec != null ? word2Vec.getVocab().numWords() : 0;
    }
    
    /**
     * Get vocabulary words (top N most frequent)
     * 
     * @return List of vocabulary words
     */
    public List<String> getVocabulary() {
        if (word2Vec == null) {
            return new ArrayList<>();
        }
        
        // Return first 100 words as sample
        List<String> vocabSample = new ArrayList<>();
        Collection<String> words = word2Vec.getVocab().words();
        int count = 0;
        for (String word : words) {
            vocabSample.add(word);
            if (++count >= 100) break;
        }
        return vocabSample;
    }
    
    /**
     * Get semantic similarity between two words
     * 
     * @param word1 First word
     * @param word2 Second word
     * @return Similarity score (0.0 to 1.0)
     */
    public double similarity(String word1, String word2) {
        if (word2Vec == null || !word2Vec.hasWord(word1) || !word2Vec.hasWord(word2)) {
            return 0.0;
        }
        return word2Vec.similarity(word1, word2);
    }
    
    /**
     * Find words most similar to the given word
     * 
     * @param word Query word
     * @param topN Number of similar words to return
     * @return List of similar words with similarity scores
     */
    public Collection<String> wordsNearest(String word, int topN) {
        if (word2Vec == null || !word2Vec.hasWord(word)) {
            return new ArrayList<>();
        }
        return word2Vec.wordsNearest(word, topN);
    }
    
    /**
     * Check if word exists in Word2Vec vocabulary
     * 
     * @param word Word to check
     * @return True if word exists in vocabulary
     */
    public boolean hasWord(String word) {
        return word2Vec != null && word2Vec.hasWord(word);
    }
}
