package io.eventlens.api.routes;

import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

public final class MetricsRoutes {

    private final RouteAuthorizer routeAuthorizer;

    public MetricsRoutes(RouteAuthorizer routeAuthorizer) {
        this.routeAuthorizer = routeAuthorizer;
    }

    /** GET /api/v1/metrics */
    public void metrics(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.VIEW_METRICS, null, null)) {
            return;
        }
        ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
        ctx.result(EventLensMetrics.registry.scrape());
    }
}
