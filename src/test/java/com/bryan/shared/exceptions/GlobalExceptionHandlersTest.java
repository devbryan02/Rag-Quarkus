package com.bryan.shared.exceptions;

import com.bryan.features.ingestion.exceptions.DuplicateDocumentException;
import com.bryan.features.ingestion.exceptions.UnsupportedDocumentTypeException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GlobalExceptionHandlersTest {

    private final GlobalExceptionHandlers handlers = new GlobalExceptionHandlers();

    @Test
    void mapsUnsupportedDocumentTypeTo400() {
        Response response = handlers.mapUnsupportedDocumentType(
                new UnsupportedDocumentTypeException("Formato no soportado: archivo.zip"));

        assertEquals(400, response.getStatus());
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertFalse(body.success());
        assertEquals(400, body.status());
    }

    @Test
    void mapsDuplicateDocumentTo409() {
        Response response = handlers.mapDuplicateDocument(
                new DuplicateDocumentException("Ya existe: archivo.txt"));

        assertEquals(409, response.getStatus());
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertFalse(body.success());
        assertEquals(409, body.status());
    }
}
