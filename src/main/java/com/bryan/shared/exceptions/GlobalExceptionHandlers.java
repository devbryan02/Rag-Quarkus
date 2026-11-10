package com.bryan.shared.exceptions;

import com.bryan.features.ingestion.exceptions.DuplicateDocumentException;
import com.bryan.features.ingestion.exceptions.UnsupportedDocumentTypeException;
import io.smallrye.faulttolerance.api.RateLimitException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

public class GlobalExceptionHandlers {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionHandlers.class);

    @ServerExceptionMapper
    public Response mapWebApplicationException(WebApplicationException e) {
        return e.getResponse();
    }

    @ServerExceptionMapper
    public Response mapRateLimit(RateLimitException e) {
        LOG.warn("Rate limit exceeded: " + e.getMessage());
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .entity(new ErrorResponse(
                        false,
                        "Demasiadas solicitudes, intenta de nuevo en unos segundos",
                        429
                ))
                .build();
    }

    @ServerExceptionMapper
    public Response mapUnsupportedDocumentType(UnsupportedDocumentTypeException e) {
        LOG.warn("Unsupported document type: " + e.getMessage());
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse(
                        false,
                        e.getMessage(),
                        400
                ))
                .build();
    }

    @ServerExceptionMapper
    public Response mapDuplicateDocument(DuplicateDocumentException e) {
        LOG.warn("Duplicate document: " + e.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .entity(new ErrorResponse(
                        false,
                        e.getMessage(),
                        409
                ))
                .build();
    }

    @ServerExceptionMapper
    public Response mapGeneric(Exception e) {
        LOG.error("Unhandled exception", e);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse(
                        false,
                        "Ocurrió un error inesperado",
                        500
                ))
                .build();
    }
}
