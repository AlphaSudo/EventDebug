package io.eventlens.api.routes;

import io.eventlens.core.spi.EventStoreReader;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoint reporting connectivity and event store statistics.
 * Used by Docker health checks, load balancers, and monitoring systems.
 */
public class HealthRoutes {

    private final EventStoreReader reader;

    public HealthRoutes(EventStoreReader reader) {
        this.reader = reader;
    }

    /** GET /api/health */
    public void health(Context ctx) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("timestamp", Instant.now().toString());
        status.put("version", "1.0.0");

        try {
            var recentEvents = reader.getRecentEvents(1);
            var types = reader.getAggregateTypes();

            status.put("eventStore", Map.of(
                    "status", "UP",
                    "aggregateTypes", types.size(),
                    "hasRecentEvents", !recentEvents.isEmpty()));
        } catch (Exception e) {
            status.put("status", "DEGRADED");
            status.put("eventStore", Map.of("status", "DOWN", "error", e.getMessage()));
            ctx.status(503);
        }

        ctx.json(status);
    }
}
