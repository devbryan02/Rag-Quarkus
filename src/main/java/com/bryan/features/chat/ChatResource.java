package com.bryan.features.chat;

import com.bryan.features.chat.records.ChatRequest;
import com.bryan.features.chat.records.ChatResponse;
import com.bryan.shared.exceptions.ErrorResponse;
import io.smallrye.faulttolerance.api.RateLimit;
import io.smallrye.faulttolerance.api.RateLimitType;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.temporal.ChronoUnit;

@Path("/chat")
@Tag(name = "Chat", description = "Preguntas y respuestas sobre los documentos indexados")
public class ChatResource {

    @Inject RagAssistant ragAssistant;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    // ROLLING: como máximo 10 en cualquier intervalo de 60 s. Con FIXED (el default)
    // las ventanas van alineadas al arranque de la app y una ráfaga que cruza el borde
    // de la ventana puede llegar a 20 seguidas sin recibir ningún 429.
    @RateLimit(value = 10, window = 1, windowUnit = ChronoUnit.MINUTES, type = RateLimitType.ROLLING)
    @SecurityRequirement(name = "ApiKeyAuth")
    @Operation(summary = "Responder una pregunta usando los documentos indexados",
            description = "Genera una respuesta basada exclusivamente en el contenido de los documentos indexados en pgvector. Limitado a 10 solicitudes por minuto.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Respuesta generada",
                    content = @Content(schema = @Schema(implementation = ChatResponse.class))),
            @APIResponse(responseCode = "400", description = "La pregunta es nula o está en blanco (validación de request)"),
            @APIResponse(responseCode = "401", description = "Header X-API-Key ausente o inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "429", description = "Límite de 10 solicitudes por minuto excedido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "Error inesperado (ej. falla al llamar al LLM)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ChatResponse chat(@Valid ChatRequest request) {
        String answer = ragAssistant.chat(request.question());
        return new ChatResponse(answer);
    }
}
