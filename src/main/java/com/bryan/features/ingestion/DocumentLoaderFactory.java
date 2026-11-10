package com.bryan.features.ingestion;

import com.bryan.features.ingestion.exceptions.DocumentLoadException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class DocumentLoaderFactory {

    public Document load(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        DocumentParser parser = resolveParser(fileName);

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            return parser.parse(inputStream);
        } catch (IOException e) {
            throw new DocumentLoadException("No se pudo leer el archivo: " + filePath, e);
        }
    }

    public boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")
                || lower.endsWith(".txt") || lower.endsWith(".md");
    }

    private DocumentParser resolveParser(String fileName) {
        if (fileName.endsWith(".pdf")) {
            return new ApachePdfBoxDocumentParser();
        }
        if (fileName.endsWith(".docx") || fileName.endsWith(".doc")) {
            return new ApachePoiDocumentParser();
        }
        if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            return new TextDocumentParser();
        }
        throw new IllegalArgumentException(
                "Formato no soportado: " + fileName + ". Usa .pdf, .docx o .txt"
        );
    }

}