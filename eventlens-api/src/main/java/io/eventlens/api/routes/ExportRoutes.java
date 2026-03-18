package io.eventlens.api.routes;

import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.ExportEngine;
import io.javalin.http.Context;

import java.util.Map;

/**
 * Export endpoints — downloads aggregate event history in various formats.
 *
 * <p>v2 — emits {@link AuditEvent#ACTION_EXPORT} audit entries including the
 * requested format so download events are traceable.
 */
public class ExportRoutes {

    private final ExportEngine exportEngine;
    private final AuditLogger  auditLogger;

    public ExportRoutes(ExportEngine exportEngine, AuditLogger auditLogger) {
        this.exportEngine = exportEngine;
        this.auditLogger  = auditLogger;
    }

    /** GET /api/aggregates/{id}/export?format=json|markdown|csv|junit */
    public void export(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));

        String formatStr = ctx.queryParamAsClass("format", String.class).getOrDefault("json");
        ExportEngine.Format format = switch (formatStr.toLowerCase()) {
            case "markdown" -> ExportEngine.Format.MARKDOWN;
            case "csv"      -> ExportEngine.Format.CSV;
            case "junit"    -> ExportEngine.Format.JUNIT_FIXTURE;
            default         -> ExportEngine.Format.JSON;
        };

        String content = exportEngine.export(id, format);

        String contentType = switch (format) {
            case MARKDOWN      -> "text/markdown";
            case CSV           -> "text/csv";
            case JUNIT_FIXTURE -> "text/plain";
            default            -> "application/json";
        };

        // 1.8 — audit
        auditLogger.log(AuditEvent.builder()
                .action(AuditEvent.ACTION_EXPORT)
                .resourceType(AuditEvent.RT_EXPORT)
                .resourceId(id)
                .userId(userId(ctx))
                .authMethod(authMethod(ctx))
                .clientIp(clientIp(ctx))
                .requestId(requestId(ctx))
                .userAgent(ctx.userAgent())
                .details(Map.of("format", formatStr, "byteCount", content.length()))
                .build());

        ctx.contentType(contentType).result(content);
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
