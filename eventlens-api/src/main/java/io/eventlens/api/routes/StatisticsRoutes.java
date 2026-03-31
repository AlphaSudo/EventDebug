package io.eventlens.api.routes;

import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.security.Permission;
import io.eventlens.spi.EventStatisticsQuery;
import io.javalin.http.Context;

public final class StatisticsRoutes {

    private final SourceRegistry sourceRegistry;
    private final RouteAuthorizer routeAuthorizer;

    public StatisticsRoutes(SourceRegistry sourceRegistry, RouteAuthorizer routeAuthorizer) {
        this.sourceRegistry = sourceRegistry;
        this.routeAuthorizer = routeAuthorizer;
    }

    public void get(Context ctx) {
        int bucketHours = parsePositiveInt(ctx.queryParam("bucketHours"), 1);
        int maxBuckets = parsePositiveInt(ctx.queryParam("maxBuckets"), 24);
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_STATISTICS, source.id(), null)) {
            return;
        }
        ctx.json(sourceRegistry.statistics(source.id(), new EventStatisticsQuery(bucketHours, maxBuckets)));
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
