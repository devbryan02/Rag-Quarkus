package com.bryan.shared.security;

import com.bryan.shared.exceptions.ErrorResponse;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class ApiKeyFilter  implements ContainerRequestFilter {

    @ConfigProperty(name = "app.api-key")
    String apiKey;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {

        String headerKey = requestContext.getHeaderString("X-API-Key");

        String path = requestContext.getUriInfo().getPath();
        if(!path.startsWith("/chat")) return;

        if(headerKey == null || !headerKey.equals(apiKey)){
            requestContext.abortWith(
                    Response.status(Response.Status.UNAUTHORIZED)
                            .entity(new ErrorResponse(
                                    false,
                                    "Api Key invalida o ausente",
                                    401
                            )).build()
            );
        }
    }
}
