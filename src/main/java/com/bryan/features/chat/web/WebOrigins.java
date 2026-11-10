package com.bryan.features.chat.web;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Arrays;

@ApplicationScoped
public class WebOrigins {
    @ConfigProperty(name="app.web.origins",defaultValue="http://localhost:4200,http://127.0.0.1:4200,http://localhost:8081,http://127.0.0.1:8081") String allowed;
    public boolean permits(String origin) {
        return origin!=null && Arrays.stream(allowed.split(",")).map(String::trim).anyMatch(origin::equals);
    }
}
