package com.bryan.features.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore<TextSegment> embeddingStore;

    @PostConstruct
    void ingest() {
        if (alreadyIndexed()) {
            LOG.info("La tabla de embeddings ya contiene datos; se omite la ingestión de arranque.");
            return;
        }

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

    private boolean alreadyIndexed() {
        Embedding probe = embeddingModel.embed("ping").content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(probe)
                        .maxResults(1)
                        .minScore(0.0)
                        .build());
        return !result.matches().isEmpty();
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
