package io.eventlens.api.routes;

import io.eventlens.core.InputValidator;
import io.eventlens.core.engine.AnomalyDetector;
import io.javalin.http.Context;

/** Anomaly detection endpoints. */
public class AnomalyRoutes {

    private static final int MAX_SCAN_LIMIT = 500;

    private final AnomalyDetector anomalyDetector;

    public AnomalyRoutes(AnomalyDetector anomalyDetector) {
        this.anomalyDetector = anomalyDetector;
    }

    /** GET /api/aggregates/{id}/anomalies */
    public void scanAggregate(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        ctx.json(anomalyDetector.scan(id));
    }

    /** GET /api/anomalies/recent?limit=100 */
    public void scanRecent(Context ctx) {
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 100, MAX_SCAN_LIMIT),
                MAX_SCAN_LIMIT);
        ctx.json(anomalyDetector.scanRecent(limit));
    }
}
