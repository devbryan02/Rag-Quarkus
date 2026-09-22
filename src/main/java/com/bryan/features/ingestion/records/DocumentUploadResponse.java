package com.bryan.features.ingestion.records;

public record DocumentUploadResponse(
        boolean success,
        String filename,
        String message
) { }
