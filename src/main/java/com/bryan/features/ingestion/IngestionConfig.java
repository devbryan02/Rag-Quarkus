package com.bryan.features.ingestion;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "rag.ingestion")
public interface IngestionConfig {

    String documentsPath();
    int chunkSize();
    int chunkOverlap();

}