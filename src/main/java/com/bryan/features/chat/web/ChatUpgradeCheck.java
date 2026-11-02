package com.bryan.features.chat.web;

import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ChatUpgradeCheck implements HttpUpgradeCheck {
    @Inject TicketStore tickets;
    @Inject WebOrigins origins;
    @Override public boolean appliesTo(String endpointId) { return endpointId.equals("rag-chat"); }
    @Override public Uni<CheckResult> perform(HttpUpgradeContext context) {
        var request=context.httpRequest(); String origin=request.getHeader("Origin");
        if(!origins.permits(origin)) return CheckResult.rejectUpgrade(403);
        if(!tickets.consume(request.getParam("ticket"),origin,System.currentTimeMillis())) return CheckResult.rejectUpgrade(401);
        return CheckResult.permitUpgrade();
    }
}
