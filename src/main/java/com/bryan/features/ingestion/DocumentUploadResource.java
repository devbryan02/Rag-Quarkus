package com.bryan.features.ingestion;

import com.bryan.features.ingestion.records.DocumentUploadResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

@Path("/documents")
public class DocumentUploadResource {

    @Inject DocumentUploadService uploadService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public Response upload(@RestForm("file") FileUpload file) {
        DocumentUploadResponse response = uploadService.uploadAndIndex(file);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }
}
