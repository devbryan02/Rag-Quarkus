package com.bryan.shared.embedings;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.nio.file.Path;

@ApplicationScoped
public class EmbeddingStoreProducer {

    @Inject EmbeddingConfig config;

    // paraphrase-multilingual-MiniLM-L12-v2: multilingüe (relaciona "sueldo" con
    // "remuneración" aunque no compartan palabras), 384 dims, mean pooling.
    @Produces
    @Singleton
    public EmbeddingModel embeddingModel() {
        return new OnnxEmbeddingModel(
                Path.of(config.modelPath()),
                Path.of(config.tokenizerPath()),
                PoolingMode.MEAN
        );
    }
}
