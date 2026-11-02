package com.bryan.features.ingestion;

import com.bryan.features.ingestion.records.DocumentUploadResponse;
import com.bryan.shared.exceptions.ErrorResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/documents")
@Tag(name = "Documents", description = "Carga e indexado de documentos en caliente (sin reiniciar)")
public class DocumentUploadResource {

    @Inject DocumentUploadService uploadService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @SecurityRequirement(name = "ApiKeyAuth")
    @Operation(summary = "Subir e indexar un documento",
            description = "Guarda el archivo (.pdf, .docx, .txt, .md) en el directorio de ingestión y lo indexa de inmediato en pgvector, sin reiniciar la app.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Documento guardado e indexado",
                    content = @Content(schema = @Schema(implementation = DocumentUploadResponse.class))),
            @APIResponse(responseCode = "400", description = "Archivo faltante, sin nombre, extensión no soportada, vacío/en blanco o no procesable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "401", description = "Header X-API-Key ausente o inválido",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "409", description = "Ya existe un documento con ese nombre o hay una ingesta en curso",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @APIResponse(responseCode = "500", description = "Error inesperado",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response upload(@RestForm("file") FileUpload file) {
        DocumentUploadResponse response = uploadService.uploadAndIndex(file);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }
}
