package io.eventlens.api.routes;

import io.eventlens.core.spi.EventStoreReader;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health check endpoint reporting connectivity and event store statistics.
 * Used by Docker health checks, load balancers, and monitoring systems.
 */
public class HealthRoutes {

    private static final Logger log = LoggerFactory.getLogger(HealthRoutes.class);
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
            // Fix 9: don't leak JDBC internals (URL, table names, credentials) to
            // the HTTP response. Log the real cause server-side only.
            log.error("Event store health check failed", e);
            status.put("status", "DEGRADED");
            status.put("eventStore", Map.of("status", "DOWN",
                    "error", "Event store connectivity check failed — see server logs for details"));
            ctx.status(503);
        }

        ctx.json(status);
    }
}
