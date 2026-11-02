package com.bryan.features.ingestion;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.sql.*;
import java.util.Comparator;
import javax.sql.DataSource;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DocumentScanResourceTest {
    private static final Path ROOT = Path.of("build/test-documents");
    @Inject EmbeddingStore<TextSegment> store;
    @Inject DataSource dataSource;
    @Inject IngestionCoordinator coordinator;
    @Inject EmbeddingIngestionService ingestion;
    @Inject DocumentIndexRepository repository;
    @ConfigProperty(name="quarkus.langchain4j.pgvector.table") String table;

    @BeforeEach
    void reset() throws Exception {
        cleanFiles();
        Files.createDirectories(ROOT);
        store.removeAll();
        try (var c=dataSource.getConnection(); var s=c.createStatement()) {
            try (var r=s.executeQuery("SELECT to_regclass('rag_indexed_documents')")) {
                r.next();
                if (r.getString(1)==null) return;
            }
            s.executeUpdate("DELETE FROM rag_indexed_documents");
        }
    }
    @AfterEach void cleanFiles() throws Exception {
        if (!Files.exists(ROOT)) return;
        try (var paths=Files.walk(ROOT)) {
            for (var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    private void write(String path,String text) throws Exception {
        Path target=ROOT.resolve(path); Files.createDirectories(target.getParent()); Files.writeString(target,text);
    }
    private io.restassured.response.ValidatableResponse scan() {
        return given().header("X-API-Key","test-api-key").post("/documents/scan").then();
    }
    private int count(String predicate) throws Exception {
        try(var c=dataSource.getConnection(); var s=c.createStatement(); var r=s.executeQuery("SELECT count(*) FROM "+table+" WHERE "+predicate)) {
            r.next(); return r.getInt(1);
        }
    }
    @Test void indexesNestedFilesWithIdenticalNamesAndSkipsUnchanged() throws Exception {
        write("ventas/manual.txt","La clave comercial es VENTAS-123.");
        write("soporte/nivel2/manual.txt","La clave tecnica es SOPORTE-456.");
        scan().statusCode(200).body("indexed",is(2),"updated",is(0),"failed",is(0));
        assertEquals(1,count("metadata->>'source_path' = 'ventas/manual.txt'"));
        assertEquals(1,count("metadata->>'source_path' = 'soporte/nivel2/manual.txt'"));
        assertEquals(2,count("vector_dims(embedding) = 384"));
        scan().statusCode(200).body("indexed",is(0),"updated",is(0),"skipped",is(2));
        assertEquals(2,count("true"));
    }
    @Test void replacesChangedContentAndPreservesLastGoodVersionOnParseFailure() throws Exception {
        write("manual.txt","La version inicial tiene codigo ANTIGUO-123.");
        scan().statusCode(200).body("indexed",is(1));
        write("manual.txt","La version actual tiene codigo NUEVO-456.");
        scan().statusCode(200).body("updated",is(1));
        assertEquals(0,count("text LIKE '%ANTIGUO-123%'"));
        assertEquals(1,count("text LIKE '%NUEVO-456%'"));
        write("manual.txt","   ");
        scan().statusCode(200).body("failed",is(1),"errors[0].path",is("manual.txt"));
        assertEquals(1,count("text LIKE '%NUEVO-456%'"));
        write("manual.txt","La version corregida tiene codigo FINAL-789.");
        scan().statusCode(200).body("updated",is(1));
        assertEquals(1,count("true"));
    }
    @Test void continuesAfterCorruptAndEmptyFiles() throws Exception {
        write("ok.md","Documento Markdown valido para indexar.");
        write("ignore.zip","No soportado"); write("empty.txt",""); write("broken.pdf","Not a PDF");
        scan().statusCode(200).body("indexed",is(1),"skipped",is(1),"failed",is(2),"errors",hasSize(2));
    }
    @Test void doesNotDuplicateIndividualUploads() throws Exception {
        given().header("X-API-Key","test-api-key")
            .multiPart("file","upload.txt","El puma vive en America.".getBytes(),"text/plain")
            .post("/documents").then().statusCode(201);
        scan().statusCode(200).body("indexed",is(0),"skipped",is(1));
        assertEquals(1,count("metadata->>'source_path' = 'upload.txt'"));
    }
    @Test void keepsDeletedSourceIndexed() throws Exception {
        write("keep.txt","Este documento debe conservarse en la base."); scan().statusCode(200);
        Files.delete(ROOT.resolve("keep.txt"));
        scan().statusCode(200).body("indexed",is(0),"updated",is(0),"failed",is(0));
        assertEquals(1,count("true"));
    }
    @Test void requiresAuthentication() {
        given().post("/documents/scan").then().statusCode(401);
        given().header("X-API-Key","wrong").post("/documents/scan").then().statusCode(401);
    }
    @Test void failsWhenRootIsMissing() throws Exception {
        Files.delete(ROOT); scan().statusCode(500);
    }
    @Test void rollsBackReplacementWhenDatabaseRejectsAnInsert() throws Exception {
        write("atomic.txt","Contenido valido previo a la actualizacion."); scan().statusCode(200);
        try(var c=dataSource.getConnection(); var s=c.createStatement()) {
            s.execute("ALTER TABLE "+table+" ADD CONSTRAINT reject_test CHECK (text NOT LIKE '%REJECT_SQL%')");
        }
        try {
            write("atomic.txt","Este contenido incluye REJECT_SQL y debe revertirse.");
            scan().statusCode(500);
            assertEquals(1,count("text LIKE '%Contenido valido%'"));
            write("atomic.txt","Actualizacion correcta despues del rollback.");
            scan().statusCode(200).body("updated",is(1));
            assertEquals(1,count("true"));
        } finally {
            try(var c=dataSource.getConnection(); var s=c.createStatement()) {s.execute("ALTER TABLE "+table+" DROP CONSTRAINT reject_test");}
        }
    }
    @Test void rejectsConcurrentScanAndUploadAndReleasesLock() {
        try (var guard = coordinator.acquire()) {
            scan().statusCode(409);
            given().header("X-API-Key","test-api-key")
                .multiPart("file","concurrent.txt","contenido".getBytes(),"text/plain")
                .post("/documents").then().statusCode(409);
        }
        scan().statusCode(200);
    }
    @Test void registryIsScopedToTheActiveEmbeddingTable() throws Exception {
        write("namespaced.txt","Contenido independiente del indice anterior.");
        try(var c=dataSource.getConnection(); var s=c.prepareStatement(
                "INSERT INTO rag_indexed_documents (embedding_table,source_path,sha256) VALUES ('old_table',?,?)")) {
            s.setString(1,"namespaced.txt");
            s.setString(2,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(ROOT.resolve("namespaced.txt"))))); s.executeUpdate();
        }
        scan().statusCode(200).body("indexed",is(1),"skipped",is(0));
    }
    @Test void usesSameSnapshotForParsingAndHashingWhenSourceChanges() throws Exception {
        write("snapshot.txt","Contenido inicial INSTANTANEA.");
        FileDocumentIndexer indexer=new FileDocumentIndexer();
        indexer.repository=repository; indexer.ingestion=ingestion;
        indexer.loader=new DocumentLoaderFactory() {
            @Override public dev.langchain4j.data.document.Document load(Path snapshot) {
                try { write("snapshot.txt","Contenido modificado DESPUES."); }
                catch(Exception e) { throw new RuntimeException(e); }
                return super.load(snapshot);
            }
        };
        indexer.index(ROOT,ROOT.resolve("snapshot.txt"));
        assertEquals(1,count("text LIKE '%INSTANTANEA%'"));
        scan().statusCode(200).body("updated",is(1));
        assertEquals(0,count("text LIKE '%INSTANTANEA%'"));
        assertEquals(1,count("text LIKE '%DESPUES%'"));
    }
    @Test void skipsSymbolicLinksWithoutReadingOutsideRoot() throws Exception {
        Path outside=Files.createTempFile("rag-outside-",".txt");
        try {
            Files.writeString(outside,"Este secreto externo no debe indexarse.");
            try { Files.createSymbolicLink(ROOT.resolve("external.txt"),outside); }
            catch (java.io.IOException | UnsupportedOperationException | SecurityException e) {
                Assumptions.assumeTrue(false,"El sistema no permite crear enlaces simbolicos: "+e.getClass().getSimpleName());
            }
            scan().statusCode(200).body("indexed",is(0),"skipped",is(1));
            assertEquals(0,count("true"));
        } finally { Files.deleteIfExists(outside); }
    }
    @Test void rollsBackVectorsWhenRegistryUpdateFails() throws Exception {
        write("registry.txt","Version original que debe sobrevivir."); scan().statusCode(200);
        String oldHash=repository.findHash("registry.txt").orElseThrow();
        write("registry.txt","Version nueva que no debe persistir.");
        String newHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(ROOT.resolve("registry.txt"))));
        try(var c=dataSource.getConnection(); var s=c.createStatement()) {
            s.execute("ALTER TABLE rag_indexed_documents ADD CONSTRAINT reject_registry_test CHECK (sha256 != '"+newHash+"')");
        }
        try {
            scan().statusCode(500);
            assertEquals(oldHash,repository.findHash("registry.txt").orElseThrow());
            assertEquals(1,count("text LIKE '%Version original%'"));
            assertEquals(0,count("text LIKE '%Version nueva%'"));
        } finally {
            try(var c=dataSource.getConnection(); var s=c.createStatement()) {
                s.execute("ALTER TABLE rag_indexed_documents DROP CONSTRAINT reject_registry_test");
            }
        }
    }

    @Test void preservesLiteralBackslashesInLinuxFileIdentities() throws Exception {
        Assumptions.assumeTrue(java.io.File.separatorChar == '/', "Requiere nombres de archivo de Linux");
        write("sales/manual.txt","Documento de la carpeta ventas.");
        write("sales\\manual.txt","Documento distinto con barra invertida literal.");
        scan().statusCode(200).body("indexed",is(2));
        scan().statusCode(200).body("indexed",is(0),"updated",is(0),"skipped",is(2));
        assertEquals(2,count("true"));
    }
    @Test void sanitizesWindowsStyleUploadedPaths() {
        given().header("X-API-Key","test-api-key")
            .multiPart("file","C:\\temp\\windows.txt","Contenido de carga Windows.".getBytes(),"text/plain")
            .post("/documents").then().statusCode(201).body("filename",is("windows.txt"));
        scan().statusCode(200).body("indexed",is(0),"skipped",is(1));
    }

}
