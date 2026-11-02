package com.bryan.features.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
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

    public record PreparedDocument(List<TextSegment> segments, List<Embedding> embeddings) { }

    public PreparedDocument prepare(Document document) {
        DocumentSplitter splitter = DocumentSplitters.recursive(config.chunkSize(), config.chunkOverlap());
        List<TextSegment> segments = splitter.split(document);
        if (segments.isEmpty()) throw new IllegalArgumentException("Documento sin texto procesable");
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        return new PreparedDocument(segments, embeddings);
    }

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
