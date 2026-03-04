package com.northeastern.csye7374.finalproject.messages;

import java.io.Serializable;

/**
 * Search query message
 */
public final class SearchQuery implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private final String query;
    private final int topK;
    
    public SearchQuery(String query, int topK) {
        this.query = query;
        this.topK = topK;
    }
    
    public SearchQuery(String query) {
        this(query, 5);
    }
    
    public String getQuery() {
        return query;
    }
    
    public int getTopK() {
        return topK;
    }
    
    @Override
    public String toString() {
        return "SearchQuery{query='" + query + "', topK=" + topK + "}";
    }
}