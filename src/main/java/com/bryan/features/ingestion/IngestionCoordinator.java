package com.bryan.features.ingestion;

import com.bryan.shared.exceptions.ErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes ingestion within this application's single-instance deployment. */
@ApplicationScoped
public class IngestionCoordinator {
    private final AtomicBoolean busy = new AtomicBoolean();

    public Guard acquire() {
        if (!busy.compareAndSet(false, true)) {
            throw new WebApplicationException(Response.status(409)
                    .entity(new ErrorResponse(false, "Ya hay una ingesta en curso", 409)).build());
        }
        return new Guard();
    }

    public final class Guard implements AutoCloseable {
        private boolean closed;
        private Guard() { }
        @Override public void close() {
            if (!closed) { closed = true; busy.set(false); }
        }
    }
}
