package com.bryan.features.chat.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.annotation.Priority;
import io.smallrye.mutiny.Multi;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Alternative @Priority(1) @ApplicationScoped
public class FakeChatTokens implements ChatTokenSource {
    volatile java.util.concurrent.CompletableFuture<Void> lastCancellation=new java.util.concurrent.CompletableFuture<>();
    public java.util.concurrent.CompletableFuture<Void> cancellation(){return lastCancellation;}
    final AtomicInteger cancellations=new AtomicInteger();
    volatile List<ChatCommand.HistoryMessage> lastHistory=List.of();
    public int cancellationCount(){return cancellations.get();}
    public List<ChatCommand.HistoryMessage> history(){return lastHistory;}
    @Override public Multi<String> stream(String question,List<ChatCommand.HistoryMessage> history) {
        lastHistory=history;
        var cancellation=new java.util.concurrent.CompletableFuture<Void>();lastCancellation=cancellation;
        return Multi.createFrom().ticks().every(Duration.ofMillis(80))
                .onItem().transform(i->i==0?"Hola ":"mundo")
                .select().first(question.equals("hold")?1000:3)
                .onCancellation().invoke(()->{cancellations.incrementAndGet();cancellation.complete(null);});
    }
}
