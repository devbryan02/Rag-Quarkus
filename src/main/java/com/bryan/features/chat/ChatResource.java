package com.bryan.features.chat;

import com.bryan.features.chat.records.ChatRequest;
import com.bryan.features.chat.records.ChatResponse;
import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.temporal.ChronoUnit;

@Path("/chat")
public class ChatResource {

    @Inject RagAssistant ragAssistant;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RateLimit(value = 10, window = 1, windowUnit = ChronoUnit.MINUTES)
    public ChatResponse chat(@Valid ChatRequest request) {
        String answer = ragAssistant.chat(request.question());
        return new ChatResponse(answer);
    }
}