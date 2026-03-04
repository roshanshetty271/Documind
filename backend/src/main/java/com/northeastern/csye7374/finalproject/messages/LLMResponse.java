package com.northeastern.csye7374.finalproject.messages;

import java.io.Serializable;

/**
 * LLM response with generated answer
 */
public final class LLMResponse implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String answer;
    private final boolean success;
    private final String errorMessage;
    
    // Success
    public LLMResponse(String answer) {
        this.answer = answer;
        this.success = true;
        this.errorMessage = null;
    }
    
    // Error
    private LLMResponse(String answer, String errorMessage) {
        this.answer = answer;
        this.success = false;
        this.errorMessage = errorMessage;
    }
    
    public static LLMResponse error(String errorMessage) {
        return new LLMResponse(null, errorMessage);
    }
    
    public String getAnswer() {
        return answer;
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
            return "LLMResponse{answer length=" + (answer != null ? answer.length() : 0) + " chars}";
        } else {
            return "LLMResponse{error='" + errorMessage + "'}";
        }
    }
}