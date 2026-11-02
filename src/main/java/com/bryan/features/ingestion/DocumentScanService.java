package com.bryan.features.ingestion;

import com.bryan.features.ingestion.exceptions.UnsupportedDocumentTypeException;
import com.bryan.features.ingestion.records.DocumentScanResponse;
import com.bryan.features.ingestion.records.DocumentScanResponse.FileError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import org.jboss.logging.Logger;

@ApplicationScoped
public class DocumentScanService {
    private static final Logger LOG = Logger.getLogger(DocumentScanService.class);
    @Inject IngestionConfig config;
    @Inject IngestionCoordinator coordinator;
    @Inject DocumentLoaderFactory loader;
    @Inject FileDocumentIndexer indexer;

    public DocumentScanResponse scan() {
        try (var guard = coordinator.acquire()) {
            Path root = Path.of(config.documentsPath()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Directorio de documentos no disponible");
            }
            var visitor = new ScanVisitor(root);
            try { Files.walkFileTree(root, visitor); }
            catch (IOException e) { throw new IllegalStateException("No se pudo recorrer el directorio de documentos", e); }
            var result = visitor.result();
            LOG.infof("Escaneo finalizado: nuevos=%d, actualizados=%d, omitidos=%d, fallidos=%d",
                    result.indexed(), result.updated(), result.skipped(), result.failed());
            return result;
        }
    }

    private final class ScanVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private int indexed, updated, skipped;
        private final List<FileError> errors = new ArrayList<>();
        private ScanVisitor(Path root) { this.root = root; }
        @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (!attrs.isRegularFile() || !loader.isSupported(file.getFileName().toString())) {
                skipped++; return FileVisitResult.CONTINUE;
            }
            try {
                switch (indexer.index(root, file)) {
                    case INDEXED -> indexed++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
            } catch (IOException | UnsupportedDocumentTypeException e) { error(file, e); }
            return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
            if (file.equals(root)) throw e;
            error(file, e); return FileVisitResult.CONTINUE;
        }
        @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) {
                if (dir.equals(root)) throw e;
                error(dir, e);
            }
            return FileVisitResult.CONTINUE;
        }
        private void error(Path file, Exception e) {
            String relative = FileDocumentIndexer.relativePath(root, file);
            String reason = e instanceof IOException ? "No se pudo leer el archivo o directorio; reintenta el escaneo" : e.getMessage();
            errors.add(new FileError(relative, reason));
            LOG.warnf("Documento omitido por error: %s (%s)", relative, reason);
        }
        private DocumentScanResponse result() {
            return new DocumentScanResponse(indexed, updated, skipped, errors.size(), List.copyOf(errors));
        }
    }
}
