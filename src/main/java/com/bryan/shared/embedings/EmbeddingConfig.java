package com.bryan.shared.embedings;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "rag.embedding")
public interface EmbeddingConfig {

    String modelPath();
    String tokenizerPath();

}
