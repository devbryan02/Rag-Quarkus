package com.bryan.features.chat.web;

import com.bryan.shared.exceptions.ErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class ChatLimits {
    @Inject RollingQuota quota;
    public void admitGeneration() {
        int retry=quota.admit("generation",10,60000,System.currentTimeMillis());
        if(retry>0) throw new WebApplicationException(Response.status(429).header("Retry-After",retry)
                .entity(new ErrorResponse(false,"Limite de preguntas alcanzado; vuelve a intentarlo en "+retry+" segundos",429)).build());
    }
    public int admitTicket(String address) { return quota.admit("ticket:"+address,30,60000,System.currentTimeMillis()); }
}
