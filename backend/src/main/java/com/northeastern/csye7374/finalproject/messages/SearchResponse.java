package com.northeastern.csye7374.finalproject.messages;

import java.io.Serializable;
import java.util.List;

/**
 * Search response with retrieved chunks
 */
public final class SearchResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final List<String> chunks;
    private final List<Double> scores;
    private final boolean success;
    private final String errorMessage;
    
    // Success
    public SearchResponse(List<String> chunks, List<Double> scores) {
        this.chunks = chunks;
        this.scores = scores;
        this.success = true;
        this.errorMessage = null;
    }
    
    // Error
    public SearchResponse(String errorMessage) {
        this.chunks = List.of();
        this.scores = List.of();
        this.success = false;
        this.errorMessage = errorMessage;
    }
    
    public List<String> getChunks() {
        return chunks;
    }
    
    public List<Double> getScores() {
        return scores;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    @Override
    public String toString() {
        if (success) {
            return "SearchResponse{chunks=" + chunks.size() + ", avgScore=" + 
                   scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) + "}";
        } else {
            return "SearchResponse{error='" + errorMessage + "'}";
        }
    }
}