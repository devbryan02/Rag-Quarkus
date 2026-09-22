package com.bryan.features.ingestion;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class DocumentUploadResourceTest {

    private static final String API_KEY = "test-api-key";
    private static final Path DOCUMENTS_DIR = Path.of("build/test-documents");

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
