package com.bryan.features.ingestion.exceptions;

public class IndexPersistenceException extends RuntimeException {
    public IndexPersistenceException(Throwable cause) {
        super("No se pudo acceder al indice de documentos", cause);
    }
}
