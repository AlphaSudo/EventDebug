package io.eventlens.api.routes;

import io.eventlens.api.source.SourceRegistry;
import io.eventlens.spi.EventStatisticsQuery;
import io.javalin.http.Context;

public final class StatisticsRoutes {

    private final SourceRegistry sourceRegistry;

    public StatisticsRoutes(SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
    }

    public void get(Context ctx) {
        int bucketHours = parsePositiveInt(ctx.queryParam("bucketHours"), 1);
        int maxBuckets = parsePositiveInt(ctx.queryParam("maxBuckets"), 24);
        ctx.json(sourceRegistry.statistics(ctx.queryParam("source"), new EventStatisticsQuery(bucketHours, maxBuckets)));
    }

    private static int parsePositiveInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("Expected a positive integer");
        }
        return parsed;
    }
}
