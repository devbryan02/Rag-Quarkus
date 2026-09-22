package com.bryan.features.ingestion;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DocumentUploadResourceTest {

    private static final String API_KEY = "test-api-key";
    private static final Path DOCUMENTS_DIR = Path.of("build/test-documents");

    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore<TextSegment> embeddingStore;

    private Path uploadedFile;

    @AfterEach
    void cleanup() throws IOException {
        if (uploadedFile != null && Files.exists(uploadedFile)) {
            Files.delete(uploadedFile);
        }
    }

    @Test
    void uploadsSupportedDocumentAndIndexesIt() {
        String fileName = "upload-" + UUID.randomUUID() + ".txt";
        uploadedFile = DOCUMENTS_DIR.resolve(fileName);

        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", fileName, "El puma es un felino nativo de America.".getBytes(), "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(201)
                .body("success", is(true))
                .body("filename", is(fileName));

        assertTrue(Files.exists(uploadedFile));

        Embedding queryEmbedding = embeddingModel.embed("¿Que animal es nativo de America?").content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(1)
                        .build()
        );

        assertEquals(1, result.matches().size());
        assertTrue(result.matches().get(0).embedded().text().contains("puma"));
    }

    @Test
    void rejectsAndCleansUpBlankContentFile() {
        String fileName = "blank-" + UUID.randomUUID() + ".txt";
        uploadedFile = DOCUMENTS_DIR.resolve(fileName);

        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", fileName, "\n".getBytes(), "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(400)
                .body("success", is(false));

        assertFalse(Files.exists(uploadedFile));
    }

    @Test
    void passesThroughFrameworkNotFoundInsteadOfGenericError() {
        given()
                .header("X-API-Key", API_KEY)
                .when().post("/documents/nonexistent")
                .then()
                .statusCode(404);
    }

    @Test
    void rejectsUnsupportedExtension() {
        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", "archivo.zip", "contenido".getBytes(), "application/zip")
                .when().post("/documents")
                .then()
                .statusCode(400)
                .body("success", is(false));
    }

    @Test
    void rejectsDuplicateFileName() {
        String fileName = "upload-" + UUID.randomUUID() + ".txt";
        uploadedFile = DOCUMENTS_DIR.resolve(fileName);

        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", fileName, "Primer contenido.".getBytes(), "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(201);

        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", fileName, "Segundo contenido.".getBytes(), "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(409)
                .body("success", is(false));
    }

    @Test
    void rejectsMissingApiKey() {
        given()
                .multiPart("file", "no-key.txt", "contenido".getBytes(), "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(401);
    }

    @Test
    void rejectsRequestWithoutFilePart() {
        given()
                .header("X-API-Key", API_KEY)
                .multiPart("otherField", "ignored")
                .when().post("/documents")
                .then()
                .statusCode(400)
                .body("success", is(false));
    }

    @Test
    void sanitizesPathTraversalInFilename() {
        String fileName = "traversal-" + UUID.randomUUID() + ".txt";
        uploadedFile = DOCUMENTS_DIR.resolve(fileName);

        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", "../../" + fileName, "Contenido inofensivo.".getBytes(), "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(201)
                .body("filename", is(fileName));
    }

    @Test
    void rejectsBlankFilename() {
        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", "", "contenido".getBytes(), "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(400)
                .body("success", is(false));
    }

    @Test
    void rejectsEmptyFile() {
        given()
                .header("X-API-Key", API_KEY)
                .multiPart("file", "vacio.txt", new byte[0], "text/plain")
                .when().post("/documents")
                .then()
                .statusCode(400)
                .body("success", is(false));
    }
}
