package com.bryan.features.chat;

import com.bryan.features.chat.records.ChatRequest;
import com.bryan.features.chat.records.ChatResponse;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/chat")
public class ChatResource {

    @Inject RagAssistant ragAssistant;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatResponse chat(@Valid ChatRequest request) {
        String answer = ragAssistant.chat(request.question());
        return new ChatResponse(answer);
    }
}