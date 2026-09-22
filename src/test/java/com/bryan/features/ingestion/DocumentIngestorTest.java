package com.bryan.features.ingestion;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DocumentIngestorTest {

    @Inject DocumentIngestor ingestor;
    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore<TextSegment> embeddingStore;

    @Test
    void skipsStartupIngestionWhenStoreAlreadyHasData() {
        Embedding seedEmbedding = embeddingModel.embed("contenido previamente indexado").content();
        embeddingStore.add(seedEmbedding, TextSegment.from("contenido previamente indexado"));

        ingestor.ingest();

        int count = embeddingStore.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(seedEmbedding)
                        .maxResults(1000)
                        .minScore(0.0)
                        .build())
                .matches().size();

        assertEquals(1, count);
    }
}
