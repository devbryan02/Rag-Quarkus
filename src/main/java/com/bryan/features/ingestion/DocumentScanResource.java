package com.bryan.features.ingestion;

import com.bryan.features.ingestion.records.DocumentScanResponse;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.*;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/documents/scan")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Documents")
public class DocumentScanResource {
    @Inject DocumentScanService service;

    @POST
    @Blocking
    @SecurityRequirement(name = "ApiKeyAuth")
    @Operation(summary = "Escanear carpetas y subcarpetas",
            description = "Indexa archivos nuevos, actualiza los modificados y omite los que no cambiaron. "
                    + "Recorre el directorio configurado sin seguir enlaces simbolicos. Espera hasta terminar.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Escaneo finalizado; revisar contadores y errores por archivo"),
            @APIResponse(responseCode = "401", description = "API key ausente o invalida"),
            @APIResponse(responseCode = "409", description = "Ya hay una ingesta en curso"),
            @APIResponse(responseCode = "500", description = "Directorio no disponible o fallo de persistencia")
    })
    public DocumentScanResponse scan() { return service.scan(); }
}
