package io.eventlens.api.routes;

import io.eventlens.core.engine.AnomalyDetector;
import io.javalin.http.Context;

/** Anomaly detection endpoints. */
public class AnomalyRoutes {

    private final AnomalyDetector anomalyDetector;

    public AnomalyRoutes(AnomalyDetector anomalyDetector) {
        this.anomalyDetector = anomalyDetector;
    }

    /** GET /api/aggregates/{id}/anomalies */
    public void scanAggregate(Context ctx) {
        ctx.json(anomalyDetector.scan(ctx.pathParam("id")));
    }

    /** GET /api/anomalies/recent?limit=100 */
    public void scanRecent(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        ctx.json(anomalyDetector.scanRecent(limit));
    }
}
