package com.bryan.features.rerieval;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "rag.retrieval")
public interface RetrievalConfig {

    int maxResults();
    double minScore();
}