package com.bryan.features.ingestion;

import com.bryan.features.ingestion.exceptions.DuplicateDocumentException;
import com.bryan.features.ingestion.exceptions.UnsupportedDocumentTypeException;
import com.bryan.features.ingestion.records.DocumentUploadResponse;
import dev.langchain4j.data.document.Document;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@ApplicationScoped
public class DocumentUploadService {

    @Inject IngestionConfig config;
    @Inject DocumentLoaderFactory loaderFactory;
    @Inject EmbeddingIngestionService embeddingIngestionService;

    public DocumentUploadResponse uploadAndIndex(FileUpload file) {
        validateFilePresence(file);
        String fileName = extractAndSanitizeFileName(file.fileName());
        validateFileExtension(fileName);
        validateFileSize(file.size(), fileName);

        Path documentsDir = Path.of(config.documentsPath());
        Path targetPath = documentsDir.resolve(fileName);

        saveFile(file, documentsDir, targetPath, fileName);
        indexDocument(targetPath, fileName);

        return new DocumentUploadResponse(true, fileName, "Documento indexado correctamente");
    }

    private void validateFilePresence(FileUpload file) {
        if (file == null) {
            throw new UnsupportedDocumentTypeException("No se recibió ningún archivo");
        }
    }

    private String extractAndSanitizeFileName(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            throw new UnsupportedDocumentTypeException("El archivo no tiene nombre");
        }
        return Path.of(rawFileName).getFileName().toString();
    }

    private void validateFileExtension(String fileName) {
        if (!loaderFactory.isSupported(fileName)) {
            throw new UnsupportedDocumentTypeException(
                    "Formato no soportado: " + fileName + ". Usa .pdf, .docx, .txt o .md");
        }
    }

    private void validateFileSize(long size, String fileName) {
        if (size == 0) {
            throw new UnsupportedDocumentTypeException("El archivo está vacío: " + fileName);
        }
    }

    private void saveFile(FileUpload file, Path documentsDir, Path targetPath, String fileName) {
        try {
            Files.createDirectories(documentsDir);
            Files.copy(file.uploadedFile(), targetPath);
        } catch (FileAlreadyExistsException e) {
            throw new DuplicateDocumentException("Ya existe un documento llamado: " + fileName);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo guardar el archivo: " + fileName, e);
        }
    }

    private void indexDocument(Path targetPath, String fileName) {
        try {
            Document document = loaderFactory.load(targetPath);
            embeddingIngestionService.ingest(List.of(document));
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (IOException ignored) {
                // best-effort cleanup; the original processing failure is what we report
            }
            throw new UnsupportedDocumentTypeException(
                    "No se pudo procesar el documento: " + fileName);
        }
    }
}
