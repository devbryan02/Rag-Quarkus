# Document Upload Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `POST /documents`, a multipart endpoint that lets a caller upload one document (`.pdf`/`.docx`/`.doc`/`.txt`/`.md`), persist it to `rag.ingestion.documents-path`, and index it into the embedding store immediately, without restarting the app.

**Architecture:** Reuse the existing ingestion pieces (`DocumentLoaderFactory` for parsing, the splitter/embed/store logic already in `DocumentIngestor`) by extracting the shared indexing logic into a new `EmbeddingIngestionService`, then add a new REST resource that validates, saves, and indexes an uploaded file through that same shared path. Extend `ApiKeyFilter` so the new route is protected the same way `/chat` is.

**Tech Stack:** Quarkus 3.37 (`quarkus-rest` / RESTEasy Reactive multipart via `org.jboss.resteasy.reactive.RestForm` + `FileUpload`), LangChain4j 1.16.x (`Document`, `EmbeddingStoreIngestor`, `EmbeddingStore`), JUnit 5 + `@QuarkusTest` + REST-assured (existing test stack).

**Spec:** `docs/superpowers/specs/2026-09-22-document-upload-endpoint-design.md`

## Global Constraints

- New endpoint is `POST /documents`, `multipart/form-data`, single file per request, form field name `file`.
- Protected by the same `X-API-Key` header/value as `/chat` (`app.api-key` config).
- Uploaded file is written to `rag.ingestion.documents-path` **and** indexed immediately in the same request (no async/deferred indexing).
- A filename that already exists in `documents-path` is rejected with `409 Conflict`, never overwritten.
- Supported extensions: `.pdf`, `.docx`, `.doc`, `.txt`, `.md` — same set `DocumentLoaderFactory` already parses.
- Follow the existing feature-based package layout (`features/ingestion/...`) and the existing `@ConfigMapping` pattern for config (`IngestionConfig`) — no new raw `@ConfigProperty` injections.
- No new third-party dependencies; multipart support is already available transitively via `quarkus-rest`.

## Review Focus

- A request with no `file` part at all — without a guard, `file.fileName()` NPEs into an opaque 500 instead of a clear 400. *(Task 4)*
- A filename containing path-traversal segments (e.g. `../../secret.txt`) — without sanitizing to the base name before resolving against `documents-path`, this could write outside the documents folder. *(Task 4)*
- A `file` part present but with a blank/empty filename — same NPE risk as the missing-part case if unguarded. *(Task 4)*
- A zero-byte file upload — LangChain4j's `Document.from` rejects blank text, which today would surface as an opaque 500 instead of a clear 400 telling the caller the file is empty. *(Task 4)*
- Two requests racing to upload the same not-yet-existing filename — a naive "check exists, then copy" has a TOCTOU window where both could pass the check; using `Files.copy` without `REPLACE_EXISTING` (which throws `FileAlreadyExistsException` atomically) closes that window. *(Task 4)*

---

### Task 1: Extract `DocumentLoaderFactory.isSupported()` and reuse it in `DocumentIngestor`

**Files:**
- Modify: `src/main/java/com/bryan/features/ingestion/DocumentLoaderFactory.java`
- Modify: `src/main/java/com/bryan/features/ingestion/DocumentIngestor.java`
- Test: `src/test/java/com/bryan/features/ingestion/DocumentLoaderFactoryTest.java` (create)

**Interfaces:**
- Produces: `DocumentLoaderFactory.isSupported(String fileName): boolean` — true for `.pdf`, `.docx`, `.doc`, `.txt`, `.md` (case-insensitive), false otherwise. Used by Task 4's upload resource to validate before touching disk.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.bryan.features.ingestion.DocumentLoaderFactoryTest"`
Expected: FAIL (compile error — `isSupported` does not exist on `DocumentLoaderFactory`)

- [ ] **Step 3: Add `isSupported` to `DocumentLoaderFactory`**

Add this method to the existing class (keep `load` and `resolveParser` unchanged):

```java
    public boolean isSupported(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")
                || lower.endsWith(".txt") || lower.endsWith(".md");
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.bryan.features.ingestion.DocumentLoaderFactoryTest"`
Expected: PASS

- [ ] **Step 5: Refactor `DocumentIngestor` to use `loaderFactory.isSupported` instead of its own private check**

Replace the whole file with:

```java
package com.bryan.features.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Singleton
@Startup
public class DocumentIngestor {

    private static final Logger LOG = Logger.getLogger(DocumentIngestor.class);

    @Inject IngestionConfig config;
    @Inject DocumentLoaderFactory loaderFactory;
    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore<TextSegment> embeddingStore;

    @PostConstruct
    void ingest() {
        Path documentsDir = Path.of(config.documentsPath());

        if (!Files.isDirectory(documentsDir)) {
            LOG.warnf("Carpeta de documentos no encontrada: %s. " +
                    "El chat no tendrá contexto hasta que agregues archivos.", documentsDir);
            return;
        }

        List<Document> documents = loadAllDocuments(documentsDir);

        if (documents.isEmpty()) {
            LOG.warnf("No se encontraron archivos soportados (.pdf, .docx, .txt) en %s", documentsDir);
            return;
        }

        DocumentSplitter splitter = DocumentSplitters.recursive(
                config.chunkSize(),
                config.chunkOverlap()
        );

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(documents);

        LOG.infof("Ingestion completa: %d documento(s) procesado(s) desde %s",
                documents.size(), documentsDir);
    }

    private List<Document> loadAllDocuments(Path documentsDir) {
        try (Stream<Path> paths = Files.list(documentsDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> loaderFactory.isSupported(path.getFileName().toString()))
                    .map(path -> {
                        LOG.infof("Cargando documento: %s", path.getFileName());
                        return loaderFactory.load(path);
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Error leyendo la carpeta de documentos: " + documentsDir, e);
        }
    }
}
```

(This removes the private `isSupportedFile` method; behavior is unchanged.)

- [ ] **Step 6: Run the full test suite to confirm nothing broke**

Run: `./gradlew test`
Expected: PASS (existing `ExampleResourceTest` + new `DocumentLoaderFactoryTest`)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bryan/features/ingestion/DocumentLoaderFactory.java src/main/java/com/bryan/features/ingestion/DocumentIngestor.java src/test/java/com/bryan/features/ingestion/DocumentLoaderFactoryTest.java
git commit -m "refactor: extract supported-extension check into DocumentLoaderFactory"
```

---

### Task 2: Extract `EmbeddingIngestionService` (shared splitter/embed/store logic)

**Files:**
- Create: `src/main/java/com/bryan/features/ingestion/EmbeddingIngestionService.java`
- Modify: `src/main/java/com/bryan/features/ingestion/DocumentIngestor.java`
- Test: `src/test/java/com/bryan/features/ingestion/EmbeddingIngestionServiceTest.java` (create)

**Interfaces:**
- Consumes: `IngestionConfig.chunkSize()/chunkOverlap()` (existing `@ConfigMapping`), CDI beans `EmbeddingModel` and `EmbeddingStore<TextSegment>` (existing producers in `EmbeddingStoreProducer`).
- Produces: `EmbeddingIngestionService.ingest(List<Document> documents): void` — splits, embeds, and stores the given documents. Used by `DocumentIngestor` (Task 1's startup flow) and by `DocumentUploadResource` (Task 4).

- [ ] **Step 1: Write the failing test**

```java
package com.bryan.features.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EmbeddingIngestionServiceTest {

    @Inject EmbeddingIngestionService ingestionService;
    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore<TextSegment> embeddingStore;

    @Test
    void ingestsDocumentIntoEmbeddingStore() {
        ingestionService.ingest(List.of(
                Document.from("La jirafa es el animal terrestre mas alto del mundo.")));

        Embedding queryEmbedding = embeddingModel.embed("¿Cual es el animal terrestre mas alto?").content();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(1)
                        .build()
        );

        assertEquals(1, result.matches().size());
        assertTrue(result.matches().get(0).embedded().text().contains("jirafa"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.bryan.features.ingestion.EmbeddingIngestionServiceTest"`
Expected: FAIL (compile error — `EmbeddingIngestionService` does not exist)

- [ ] **Step 3: Create `EmbeddingIngestionService`**

```java
package com.bryan.features.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class EmbeddingIngestionService {

    @Inject IngestionConfig config;
    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore<TextSegment> embeddingStore;

    public void ingest(List<Document> documents) {
        DocumentSplitter splitter = DocumentSplitters.recursive(
                config.chunkSize(),
                config.chunkOverlap()
        );

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(splitter)
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(documents);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.bryan.features.ingestion.EmbeddingIngestionServiceTest"`
Expected: PASS

- [ ] **Step 5: Refactor `DocumentIngestor` to delegate to `EmbeddingIngestionService`**

Replace the whole file with:

```java
package com.bryan.features.ingestion;

import dev.langchain4j.data.document.Document;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Singleton
@Startup
public class DocumentIngestor {

    private static final Logger LOG = Logger.getLogger(DocumentIngestor.class);

    @Inject IngestionConfig config;
    @Inject DocumentLoaderFactory loaderFactory;
    @Inject EmbeddingIngestionService embeddingIngestionService;

    @PostConstruct
    void ingest() {
        Path documentsDir = Path.of(config.documentsPath());

        if (!Files.isDirectory(documentsDir)) {
            LOG.warnf("Carpeta de documentos no encontrada: %s. " +
                    "El chat no tendrá contexto hasta que agregues archivos.", documentsDir);
            return;
        }

        List<Document> documents = loadAllDocuments(documentsDir);

        if (documents.isEmpty()) {
            LOG.warnf("No se encontraron archivos soportados (.pdf, .docx, .txt) en %s", documentsDir);
            return;
        }

        embeddingIngestionService.ingest(documents);

        LOG.infof("Ingestion completa: %d documento(s) procesado(s) desde %s",
                documents.size(), documentsDir);
    }

    private List<Document> loadAllDocuments(Path documentsDir) {
        try (Stream<Path> paths = Files.list(documentsDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> loaderFactory.isSupported(path.getFileName().toString()))
                    .map(path -> {
                        LOG.infof("Cargando documento: %s", path.getFileName());
                        return loaderFactory.load(path);
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Error leyendo la carpeta de documentos: " + documentsDir, e);
        }
    }
}
```

- [ ] **Step 6: Run the full test suite to confirm nothing broke**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/bryan/features/ingestion/EmbeddingIngestionService.java src/main/java/com/bryan/features/ingestion/DocumentIngestor.java src/test/java/com/bryan/features/ingestion/EmbeddingIngestionServiceTest.java
git commit -m "refactor: extract EmbeddingIngestionService shared by startup ingestion"
```

---

### Task 3: Add `UnsupportedDocumentTypeException` (400) and `DuplicateDocumentException` (409) mappers

**Files:**
- Create: `src/main/java/com/bryan/features/ingestion/exceptions/UnsupportedDocumentTypeException.java`
- Create: `src/main/java/com/bryan/features/ingestion/exceptions/DuplicateDocumentException.java`
- Modify: `src/main/java/com/bryan/shared/exceptions/GlobalExceptionHandlers.java`
- Test: `src/test/java/com/bryan/shared/exceptions/GlobalExceptionHandlersTest.java` (create)

**Interfaces:**
- Produces: `new UnsupportedDocumentTypeException(String message)`, `new DuplicateDocumentException(String message)` — both `RuntimeException`. Thrown by `DocumentUploadResource` (Task 4).
- Produces: `GlobalExceptionHandlers.mapUnsupportedDocumentType(UnsupportedDocumentTypeException): Response` → 400 with `ErrorResponse`; `GlobalExceptionHandlers.mapDuplicateDocument(DuplicateDocumentException): Response` → 409 with `ErrorResponse`.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.bryan.shared.exceptions.GlobalExceptionHandlersTest"`
Expected: FAIL (compile error — exception classes and mapper methods don't exist)

- [ ] **Step 3: Create the two exception classes**

```java
package com.bryan.features.ingestion.exceptions;

public class UnsupportedDocumentTypeException extends RuntimeException {
    public UnsupportedDocumentTypeException(String message) {
        super(message);
    }
}
```

```java
package com.bryan.features.ingestion.exceptions;

public class DuplicateDocumentException extends RuntimeException {
    public DuplicateDocumentException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Add the two mappers to `GlobalExceptionHandlers`**

Replace the whole file with:

```java
package com.bryan.shared.exceptions;

import com.bryan.features.ingestion.exceptions.DuplicateDocumentException;
import com.bryan.features.ingestion.exceptions.UnsupportedDocumentTypeException;
import io.smallrye.faulttolerance.api.RateLimitException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

public class GlobalExceptionHandlers {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionHandlers.class);

    @ServerExceptionMapper
    public Response mapRateLimit(RateLimitException e) {
        LOG.warn("Rate limit exceeded: " + e.getMessage());
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .entity(new ErrorResponse(
                        false,
                        "Demasiadas solicitudes, intenta de nuevo en unos segundos",
                        429
                ))
                .build();
    }

    @ServerExceptionMapper
    public Response mapUnsupportedDocumentType(UnsupportedDocumentTypeException e) {
        LOG.warn("Unsupported document type: " + e.getMessage());
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(
                        false,
                        e.getMessage(),
                        400
                ))
                .build();
    }

    @ServerExceptionMapper
    public Response mapDuplicateDocument(DuplicateDocumentException e) {
        LOG.warn("Duplicate document: " + e.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(
                        false,
                        e.getMessage(),
                        409
                ))
                .build();
    }

    @ServerExceptionMapper
    public Response mapGeneric(Exception e) {
        LOG.error("Unhandled exception", e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse(
                        false,
                        "Ocurrió un error inesperado",
                        500
                ))
                .build();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.bryan.shared.exceptions.GlobalExceptionHandlersTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/bryan/features/ingestion/exceptions/UnsupportedDocumentTypeException.java src/main/java/com/bryan/features/ingestion/exceptions/DuplicateDocumentException.java src/main/java/com/bryan/shared/exceptions/GlobalExceptionHandlers.java src/test/java/com/bryan/shared/exceptions/GlobalExceptionHandlersTest.java
git commit -m "feat: add exception mappers for unsupported document type and duplicate document"
```

---

### Task 4: `POST /documents` endpoint (upload, save, index) + API key protection

**Files:**
- Create: `src/main/java/com/bryan/features/ingestion/records/DocumentUploadResponse.java`
- Create: `src/main/java/com/bryan/features/ingestion/DocumentUploadResource.java`
- Modify: `src/main/java/com/bryan/shared/security/ApiKeyFilter.java`
- Create: `src/test/resources/application.properties` (test-only config overrides)
- Test: `src/test/java/com/bryan/features/ingestion/DocumentUploadResourceTest.java` (create)

**Interfaces:**
- Consumes: `DocumentLoaderFactory.isSupported(String): boolean` and `.load(Path): Document` (Task 1), `EmbeddingIngestionService.ingest(List<Document>): void` (Task 2), `UnsupportedDocumentTypeException`/`DuplicateDocumentException` (Task 3), `IngestionConfig.documentsPath(): String` (existing).
- Produces: `POST /documents` — `201` with `DocumentUploadResponse(success=true, filename, message)` on success; `400` on unsupported/blank/empty file; `409` on duplicate filename; `401` on missing/invalid `X-API-Key`.

- [ ] **Step 1: Add test-only config overrides**

Create `src/test/resources/application.properties`:

```properties
app.api-key=test-api-key
rag.ingestion.documents-path=build/test-documents
```

This keeps tests independent from the real `.env` secrets and from the real `src/main/resources/documents` folder (so tests never write into version-controlled project files).

- [ ] **Step 2: Write the failing tests**

```java
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
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "com.bryan.features.ingestion.DocumentUploadResourceTest"`
Expected: FAIL (compile error — `DocumentUploadResource` doesn't exist; `/documents` route doesn't exist)

- [ ] **Step 4: Create `DocumentUploadResponse`**

```java
package com.bryan.features.ingestion.records;

public record DocumentUploadResponse(
        boolean success,
        String filename,
        String message
) { }
```

- [ ] **Step 5: Create `DocumentUploadResource`**

```java
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
```

- [ ] **Step 6: Extend `ApiKeyFilter` to protect `/documents`**

In `src/main/java/com/bryan/shared/security/ApiKeyFilter.java`, change:

```java
        String path = requestContext.getUriInfo().getPath();
        if(!path.startsWith("/chat")) return;
```

to:

```java
        String path = requestContext.getUriInfo().getPath();
        if(!path.startsWith("/chat") && !path.startsWith("/documents")) return;
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew test --tests "com.bryan.features.ingestion.DocumentUploadResourceTest"`
Expected: PASS (all 8 test methods)

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew test`
Expected: PASS (all tests across the project)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/bryan/features/ingestion/records/DocumentUploadResponse.java src/main/java/com/bryan/features/ingestion/DocumentUploadResource.java src/main/java/com/bryan/shared/security/ApiKeyFilter.java src/test/resources/application.properties src/test/java/com/bryan/features/ingestion/DocumentUploadResourceTest.java
git commit -m "feat: add POST /documents endpoint to upload and index new documents"
```
