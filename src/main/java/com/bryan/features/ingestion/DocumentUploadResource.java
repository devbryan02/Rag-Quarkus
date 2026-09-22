package com.bryan.features.ingestion;

import com.bryan.features.ingestion.exceptions.DuplicateDocumentException;
import com.bryan.features.ingestion.exceptions.UnsupportedDocumentTypeException;
import com.bryan.features.ingestion.records.DocumentUploadResponse;
import dev.langchain4j.data.document.Document;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.List;

@Path("/documents")
public class DocumentUploadResource {

    @Inject IngestionConfig config;
    @Inject DocumentLoaderFactory loaderFactory;
    @Inject EmbeddingIngestionService embeddingIngestionService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(@RestForm("file") FileUpload file) {
        if (file == null) {
            throw new UnsupportedDocumentTypeException("No se recibió ningún archivo");
        }

        String rawFileName = file.fileName();
        if (rawFileName == null || rawFileName.isBlank()) {
            throw new UnsupportedDocumentTypeException("El archivo no tiene nombre");
        }

        String fileName = java.nio.file.Path.of(rawFileName).getFileName().toString();

        if (!loaderFactory.isSupported(fileName)) {
            throw new UnsupportedDocumentTypeException(
                    "Formato no soportado: " + fileName + ". Usa .pdf, .docx, .txt o .md");
        }

        if (file.size() == 0) {
            throw new UnsupportedDocumentTypeException("El archivo está vacío: " + fileName);
        }

        java.nio.file.Path documentsDir = java.nio.file.Path.of(config.documentsPath());
        java.nio.file.Path targetPath = documentsDir.resolve(fileName);

        try {
            Files.createDirectories(documentsDir);
            Files.copy(file.uploadedFile(), targetPath);
        } catch (FileAlreadyExistsException e) {
            throw new DuplicateDocumentException("Ya existe un documento llamado: " + fileName);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo guardar el archivo: " + fileName, e);
        }

        Document document = loaderFactory.load(targetPath);
        embeddingIngestionService.ingest(List.of(document));

        return Response.status(Response.Status.CREATED)
                .entity(new DocumentUploadResponse(true, fileName, "Documento indexado correctamente"))
                .build();
    }
}
