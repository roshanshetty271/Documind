package com.northeastern.csye7374.finalproject.messages;

import java.io.Serializable;
import java.util.List;

/**
 * LLM request with query and context
 */
public final class LLMRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String originalQuery;
    private final List<String> contextChunks;
    
    public LLMRequest(String originalQuery, List<String> contextChunks) {
        this.originalQuery = originalQuery;
        this.contextChunks = contextChunks;
    }
    
    public String getOriginalQuery() {
        return originalQuery;
    }
    
    public List<String> getContextChunks() {
        return contextChunks;
    }
    
    @Override
    public String toString() {
        return "LLMRequest{query='" + originalQuery + "', contextChunks=" + contextChunks.size() + "}";
    }
}