package com.bryan.features.chat.web;

import com.bryan.shared.exceptions.ErrorResponse;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/chat/ws-ticket")
@Produces(MediaType.APPLICATION_JSON)
public class ChatTicketResource {
    @Inject TicketStore tickets;
    @Inject WebOrigins origins;
    @Inject ChatLimits limits;
    @Inject TrustedProxyAddress clientAddress;
    @Inject HttpServerRequest request;
    public record TicketResponse(String ticket,int expiresInSeconds) { }
    @POST
    @SecurityRequirement(name="ApiKeyAuth")
    @Operation(summary="Crear ticket de un solo uso para el chat WebSocket")
    public TicketResponse issue(@HeaderParam("Origin") String origin) {
        if(!origins.permits(origin)) throw new WebApplicationException(Response.status(403)
                .entity(new ErrorResponse(false,"Origen no permitido",403)).build());
        int retry=limits.admitTicket(clientAddress.address(request.remoteAddress().host(),request.getHeader("X-Real-IP")));
        if(retry>0) throw new WebApplicationException(Response.status(429).header("Retry-After",retry)
                .entity(new ErrorResponse(false,"Demasiados intentos de conexion",429)).build());
        return new TicketResponse(tickets.issue(origin,System.currentTimeMillis()),30);
    }
}
