package io.eventlens.api.routes;

import io.eventlens.api.source.SourceRegistry;
import io.eventlens.api.http.SecurityContext;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.AnomalyDetector;
import io.javalin.http.Context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anomaly detection endpoints.
 *
 * <p>v2 - emits {@link AuditEvent#ACTION_VIEW_ANOMALIES} audit entries.
 */
public class AnomalyRoutes {

    private static final int MAX_SCAN_LIMIT = 500;

    private final SourceRegistry sourceRegistry;
    private final EventLensConfig.AnomalyConfig anomalyConfig;
    private final AuditLogger auditLogger;
    private final Map<String, AnomalyDetector> detectors = new ConcurrentHashMap<>();

    public AnomalyRoutes(
            SourceRegistry sourceRegistry,
            EventLensConfig.AnomalyConfig anomalyConfig,
            AuditLogger auditLogger) {
        this.sourceRegistry = sourceRegistry;
        this.anomalyConfig = anomalyConfig;
        this.auditLogger = auditLogger;
    }

    /** GET /api/aggregates/{id}/anomalies */
    public void scanAggregate(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        var result = detectorFor(source.id(), source).scan(id);

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_VIEW_ANOMALIES)
                .resourceType(AuditEvent.RT_ANOMALY)
                .resourceId(id)
                .details(Map.of(
                        "anomalyCount", result.size(),
                        "source", source.id()))
                .build());

        ctx.json(result);
    }

    /** GET /api/anomalies/recent?limit=100 */
    public void scanRecent(Context ctx) {
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 100, MAX_SCAN_LIMIT),
                MAX_SCAN_LIMIT);
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        var result = detectorFor(source.id(), source).scanRecent(limit);

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_VIEW_ANOMALIES)
                .resourceType(AuditEvent.RT_ANOMALY)
                .details(Map.of(
                        "limit", limit,
                        "anomalyCount", result.size(),
                        "source", source.id()))
                .build());

        ctx.json(result);
    }

    private AnomalyDetector detectorFor(String sourceId, SourceRegistry.ResolvedSource source) {
        return detectors.computeIfAbsent(
                sourceId,
                ignored -> new AnomalyDetector(source.reader(), source.replayEngine(), anomalyConfig));
    }
}
