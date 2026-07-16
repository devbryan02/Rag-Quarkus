package com.bryan.features.ingestion.exceptions;

public class DocumentLoadException extends RuntimeException {

    public DocumentLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
