package io.eventlens.api.health;

import io.eventlens.core.spi.EventStoreReader;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks application liveness/readiness and performs lightweight dependency checks.
 */
public final class HealthService {

    private static final Instant startTime = Instant.now();
    private static volatile boolean shuttingDown = false;

    private HealthService() {
    }

    public static void setShuttingDown(boolean value) {
        shuttingDown = value;
    }

    public static boolean isShuttingDown() {
        return shuttingDown;
    }

    public static Map<String, Object> live(String version) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "UP");
        body.put("version", version);
        body.put("uptime", formatUptime(Duration.between(startTime, Instant.now())));
        return body;
    }

    public static Map<String, Object> ready(EventStoreReader reader, String version) {
        Map<String, Object> root = new HashMap<>();

        if (shuttingDown) {
            root.put("status", "DOWN");
            root.put("reason", "shutting_down");
            root.put("checks", Map.of());
            return root;
        }

        Map<String, Object> checks = new HashMap<>();
        boolean postgresUp = false;

        long start = System.nanoTime();
        try {
            List<?> types = reader.getAggregateTypes();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            postgresUp = true;
            checks.put("postgres", Map.of(
                    "status", "UP",
                    "responseTimeMs", elapsedMs,
                    "aggregateTypes", types.size()
            ));
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            checks.put("postgres", Map.of(
                    "status", "DOWN",
                    "error", e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : ""),
                    "responseTimeMs", elapsedMs
            ));
        }

        // For now, Kafka and disk checks are omitted; they can be added in v4 observability.

        root.put("status", postgresUp ? "UP" : "DOWN");
        root.put("version", version);
        root.put("checks", checks);
        return root;
    }

    private static String formatUptime(Duration d) {
        long seconds = d.getSeconds();
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return "%dh %dm %ds".formatted(h, m, s);
    }
}

