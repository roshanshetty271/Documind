package com.northeastern.csye7374.finalproject.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;

/**
 * LLM Service - Generates answers using Spring AI
 * 
 * Builds prompts with context and calls OpenAI GPT
 */
public class LLMService {
    
    private static final Logger log = LoggerFactory.getLogger(LLMService.class);
    
    private final ChatModel chatModel;
    
    // Default model settings
    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";
    private static final double DEFAULT_TEMPERATURE = 0.7;
    
    // Constructor with API key
    public LLMService(String apiKey) {
        try {
            log.info("Initializing LLMService with OpenAI ChatModel");
            
            // Create OpenAI API client
            OpenAiApi openAiApi = new OpenAiApi(apiKey);
            
            // Create chat options
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(DEFAULT_MODEL)
                .withTemperature(DEFAULT_TEMPERATURE)
                .build();
            
            // Initialize ChatModel
            this.chatModel = new OpenAiChatModel(openAiApi, options);
            
            log.info("LLMService initialized successfully with model: {}", DEFAULT_MODEL);
            
        } catch (Exception e) {
            log.error("Error initializing LLMService: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize LLM service", e);
        }
    }
    
    // Constructor with custom model
    public LLMService(String apiKey, String model, double temperature) {
        try {
            log.info("Initializing LLMService with model: {}, temperature: {}", model, temperature);
            
            OpenAiApi openAiApi = new OpenAiApi(apiKey);
            
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(model)
                .withTemperature(temperature)
                .build();
            
            this.chatModel = new OpenAiChatModel(openAiApi, options);
            
            log.info("LLMService initialized successfully");
            
        } catch (Exception e) {
            log.error("Error initializing LLMService: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize LLM service", e);
        }
    }
    
    // Generate answer using RAG
    public String generateAnswer(String query, List<String> contextChunks) {
        try {
            log.info("Generating answer for query: {}", query);
            log.debug("Using {} context chunks", contextChunks.size());
            
            // Build RAG prompt with context
            String promptText = buildPrompt(query, contextChunks);
            
            // Create Prompt object (SAME as HW3 line 126)
            Prompt prompt = new Prompt(promptText);
            
            // Call ChatModel and extract response (SAME as HW3 line 127)
            String response = chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getContent();
            
            log.info("Answer generated successfully ({} characters)", response.length());
            log.debug("Generated answer: {}", response.substring(0, Math.min(100, response.length())) + "...");
            
            return response;
            
        } catch (Exception e) {
            log.error("Error generating answer: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate answer", e);
        }
    }
    
    /**
     * Build RAG prompt with context chunks and query
     * Format: Instructions + Context sections + Question
     * 
     * IMPROVED: Better handling of context relevance and fallback to general knowledge
     * 
     * @param query User question
     * @param contextChunks Retrieved context from vector DB
     * @return Formatted prompt string
     */
    private String buildPrompt(String query, List<String> contextChunks) {
        StringBuilder prompt = new StringBuilder();
        
        // System instruction
        prompt.append("You are a helpful assistant answering questions based on provided documents.\n\n");
        
        // Clear instructions for better answers
        prompt.append("INSTRUCTIONS:\n");
        prompt.append("1. Use the context below as your primary source of information\n");
        prompt.append("2. If the context contains relevant information, base your answer on it\n");
        prompt.append("3. If the context is not relevant or insufficient, you may use your general knowledge\n");
        prompt.append("4. Be concise and accurate\n");
        prompt.append("5. Do not make up information that contradicts the context\n\n");
        
        // Context section with clear formatting
        prompt.append("CONTEXT FROM DOCUMENTS:\n");
        prompt.append("─────────────────────────\n");
        
        if (contextChunks == null || contextChunks.isEmpty()) {
            prompt.append("[No relevant documents found]\n");
        } else {
            for (int i = 0; i < contextChunks.size(); i++) {
                String chunk = contextChunks.get(i);
                if (chunk != null && !chunk.trim().isEmpty()) {
                    prompt.append("[").append(i + 1).append("] ").append(chunk.trim()).append("\n\n");
                }
            }
        }
        
        prompt.append("─────────────────────────\n\n");
        
        // Question and answer prompt
        prompt.append("QUESTION: ").append(query).append("\n\n");
        prompt.append("ANSWER: ");
        
        String promptText = prompt.toString();
        log.debug("Built prompt with {} context chunks, total length: {} characters", 
            contextChunks.size(), promptText.length());
        
        return promptText;
    }
    
    /**
     * Generate answer with custom instructions
     * Allows overriding the default instruction text
     * 
     * @param query User question
     * @param contextChunks Retrieved context
     * @param instructions Custom instructions for the LLM
     * @return Generated answer
     */
    public String generateAnswer(String query, List<String> contextChunks, String instructions) {
        try {
            log.info("Generating answer with custom instructions");
            
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("Based on the following course material context:\n\n");
            
            for (int i = 0; i < contextChunks.size(); i++) {
                promptBuilder.append("[Context Chunk ").append(i + 1).append("]\n");
                promptBuilder.append(contextChunks.get(i)).append("\n\n");
            }
            
            promptBuilder.append("Question: ").append(query).append("\n\n");
            promptBuilder.append(instructions);
            
            String promptText = promptBuilder.toString();
            
            // Use SAME ChatModel pattern from HW3
            Prompt prompt = new Prompt(promptText);
            String response = chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getContent();
            
            log.info("Answer generated with custom instructions");
            return response;
            
        } catch (Exception e) {
            log.error("Error generating answer with custom instructions: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate answer", e);
        }
    }
    
    /**
     * Generate answer without context (direct Q&A)
     * Useful for testing or general queries without RAG
     * 
     * @param query User question
     * @return Generated answer
     */
    public String generateAnswer(String query) {
        try {
            log.info("Generating direct answer (no context)");
            
            // Use SAME ChatModel pattern from HW3
            Prompt prompt = new Prompt(query);
            String response = chatModel.call(prompt)
                .getResult()
                .getOutput()
                .getContent();
            
            log.info("Direct answer generated");
            return response;
            
        } catch (Exception e) {
            log.error("Error generating direct answer: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate answer", e);
        }
    }
    
    /**
     * Get the underlying ChatModel
     * Useful for advanced use cases
     * 
     * @return ChatModel instance
     */
    public ChatModel getChatModel() {
        return chatModel;
    }
}

