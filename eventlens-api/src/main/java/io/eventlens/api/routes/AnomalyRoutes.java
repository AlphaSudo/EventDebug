package io.eventlens.api.routes;

import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.AnomalyDetector;
import io.javalin.http.Context;

import java.util.Map;

/**
 * Anomaly detection endpoints.
 *
 * <p>v2 — emits {@link AuditEvent#ACTION_VIEW_ANOMALIES} audit entries.
 */
public class AnomalyRoutes {

    private static final int MAX_SCAN_LIMIT = 500;

    private final AnomalyDetector anomalyDetector;
    private final AuditLogger     auditLogger;

    public AnomalyRoutes(AnomalyDetector anomalyDetector, AuditLogger auditLogger) {
        this.anomalyDetector = anomalyDetector;
        this.auditLogger     = auditLogger;
    }

    /** GET /api/aggregates/{id}/anomalies */
    public void scanAggregate(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        var result = anomalyDetector.scan(id);

        // 1.8 — audit
        auditLogger.log(AuditEvent.builder()
                .action(AuditEvent.ACTION_VIEW_ANOMALIES)
                .resourceType(AuditEvent.RT_ANOMALY)
                .resourceId(id)
                .userId(userId(ctx))
                .authMethod(authMethod(ctx))
                .clientIp(clientIp(ctx))
                .requestId(requestId(ctx))
                .userAgent(ctx.userAgent())
                .details(Map.of("anomalyCount", result.size()))
                .build());

        ctx.json(result);
    }

    /** GET /api/anomalies/recent?limit=100 */
    public void scanRecent(Context ctx) {
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 100, MAX_SCAN_LIMIT),
                MAX_SCAN_LIMIT);

        var result = anomalyDetector.scanRecent(limit);

        // 1.8 — audit
        auditLogger.log(AuditEvent.builder()
                .action(AuditEvent.ACTION_VIEW_ANOMALIES)
                .resourceType(AuditEvent.RT_ANOMALY)
                .userId(userId(ctx))
                .authMethod(authMethod(ctx))
                .clientIp(clientIp(ctx))
                .requestId(requestId(ctx))
                .userAgent(ctx.userAgent())
                .details(Map.of("limit", limit, "anomalyCount", result.size()))
                .build());

        ctx.json(result);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String userId(Context ctx) {
        String v = ctx.attribute("auditUserId");
        return v != null ? v : "anonymous";
    }

    private static String authMethod(Context ctx) {
        String v = ctx.attribute("auditAuthMethod");
        return v != null ? v : "anonymous";
    }

    private static String clientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int c = xff.indexOf(',');
            return (c >= 0 ? xff.substring(0, c) : xff).trim();
        }
        String xri = ctx.header("X-Real-IP");
        return xri != null && !xri.isBlank() ? xri.trim() : ctx.ip();
    }

    private static String requestId(Context ctx) {
        String v = ctx.attribute("requestId");
        return v != null ? v : "unknown";
    }
}
