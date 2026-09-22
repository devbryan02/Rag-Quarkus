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