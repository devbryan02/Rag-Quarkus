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
