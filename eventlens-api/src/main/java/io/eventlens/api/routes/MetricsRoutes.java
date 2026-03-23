package io.eventlens.api.routes;

import io.eventlens.api.metrics.EventLensMetrics;
import io.javalin.http.Context;

public final class MetricsRoutes {

    /** GET /api/v1/metrics */
    public void metrics(Context ctx) {
        ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
        ctx.result(EventLensMetrics.registry.scrape());
    }
}

