package com.bryan.features.ingestion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentLoaderFactoryTest {

    private final DocumentLoaderFactory factory = new DocumentLoaderFactory();

    @Test
    void supportsKnownExtensions() {
        assertTrue(factory.isSupported("report.pdf"));
        assertTrue(factory.isSupported("REPORT.PDF"));
        assertTrue(factory.isSupported("notes.docx"));
        assertTrue(factory.isSupported("notes.doc"));
        assertTrue(factory.isSupported("readme.txt"));
        assertTrue(factory.isSupported("readme.md"));
    }

    @Test
    void rejectsUnknownExtensions() {
        assertFalse(factory.isSupported("archive.zip"));
        assertFalse(factory.isSupported("no-extension"));
    }
}
