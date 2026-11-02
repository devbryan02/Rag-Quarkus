package com.bryan.features.chat.web;

import com.bryan.features.rerieval.RetrievalConfig;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.*;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

@ApplicationScoped
public class RagTokenSource implements ChatTokenSource {
    @Inject EmbeddingModel embeddingModel;
    @Inject EmbeddingStore<TextSegment> store;
    @Inject RetrievalConfig retrieval;
    @ConfigProperty(name="quarkus.langchain4j.openai.base-url") String baseUrl;
    @ConfigProperty(name="quarkus.langchain4j.openai.api-key") String key;
    @ConfigProperty(name="quarkus.langchain4j.openai.chat-model.model-name") String modelName;
    @ConfigProperty(name="quarkus.langchain4j.openai.chat-model.temperature",defaultValue="0.3") double temperature;
    private StreamingChatModel model;
    @PostConstruct void initialize() {
        model=OpenAiStreamingChatModel.builder().baseUrl(baseUrl).apiKey(key).modelName(modelName)
                .temperature(temperature).timeout(Duration.ofSeconds(60)).logRequests(false).logResponses(false).build();
    }
    @Override public Multi<String> stream(String question,List<ChatCommand.HistoryMessage> history) {
        return Multi.createFrom().<String>emitter(emitter->{
            AtomicBoolean stopped=new AtomicBoolean();
            AtomicReference<StreamingHandle> handle=new AtomicReference<>();
            emitter.onTermination(()->{stopped.set(true);var h=handle.get();if(h!=null) h.cancel();});
            try {
                var searches=RetrievalQueryPlanner.queries(question,history);
                var embedding=embeddingModel.embed(searches.get(0).text()).content();
                if(stopped.get()) return;
                Map<String,TextSegment> documents=new LinkedHashMap<>();
                for(var query:searches) {
                    var request=EmbeddingSearchRequest.builder().queryEmbedding(embedding)
                            .maxResults(retrieval.maxResults())
                            .minScore(query.documentReference()==null?retrieval.minScore():0.0);
                    if(query.documentReference()!=null) {
                        request.filter(MetadataFilterBuilder.metadataKey("source_path")
                                .containsString(query.documentReference()));
                    }
                    var result=store.search(request.build());
                    if(stopped.get()) return;
                    for(var match:result.matches()) {
                        TextSegment segment=match.embedded();
                        String source=segment.metadata().getString("source_path");
                        documents.putIfAbsent((source==null?"":source)+"\u0000"+segment.text(),segment);
                    }
                }
                model.chat(RagPrompt.compose(question,history,List.copyOf(documents.values())),new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(PartialResponse part,PartialResponseContext context) {
                        handle.set(context.streamingHandle());
                        if(stopped.get()) context.streamingHandle().cancel();else emitter.emit(part.text());
                    }
                    @Override public void onCompleteResponse(ChatResponse response) { if(!stopped.get()) emitter.complete(); }
                    @Override public void onError(Throwable failure) { if(!stopped.get()) emitter.fail(failure); }
                });
            } catch(Exception failure) { if(!stopped.get()) emitter.fail(failure); }
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }
}
