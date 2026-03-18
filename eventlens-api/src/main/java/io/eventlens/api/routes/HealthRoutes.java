package io.eventlens.api.routes;

import io.eventlens.api.health.HealthService;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.http.Context;

/**
 * Liveness and readiness health endpoints (v2+).
 */
public class HealthRoutes {

    private final EventStoreReader reader;
    private final String version;

    public HealthRoutes(EventStoreReader reader, String version) {
        this.reader = reader;
        this.version = version;
    }

    /** GET /api/v1/health/live */
    public void live(Context ctx) {
        ctx.json(HealthService.live(version));
    }

    /** GET /api/v1/health/ready */
    public void ready(Context ctx) {
        var body = HealthService.ready(reader, version);
        if ("DOWN".equals(body.get("status"))) {
            ctx.status(503);
        }
        ctx.json(body);
    }
}
