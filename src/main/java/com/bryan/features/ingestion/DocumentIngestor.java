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
