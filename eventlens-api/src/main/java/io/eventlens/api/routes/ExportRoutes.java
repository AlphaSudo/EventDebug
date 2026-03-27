package io.eventlens.api.routes;

import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.InputValidator;
import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.ExportEngine;
import io.eventlens.core.pii.SensitiveDataProtector;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/**
 * Export endpoints — downloads aggregate event history in various formats.
 *
 * <p>v2 — emits {@link AuditEvent#ACTION_EXPORT} audit entries including the
 * requested format so download events are traceable.
 */
public class ExportRoutes {

    private final SourceRegistry sourceRegistry;
    private final ExportEngine exportEngine;
    private final AuditLogger  auditLogger;
    private final RouteAuthorizer routeAuthorizer;
    private final SensitiveDataProtector sensitiveDataProtector;

    public ExportRoutes(
            SourceRegistry sourceRegistry,
            ExportEngine exportEngine,
            AuditLogger auditLogger,
            RouteAuthorizer routeAuthorizer,
            SensitiveDataProtector sensitiveDataProtector) {
        this.sourceRegistry = sourceRegistry;
        this.exportEngine = exportEngine;
        this.auditLogger  = auditLogger;
        this.routeAuthorizer = routeAuthorizer;
        this.sensitiveDataProtector = sensitiveDataProtector;
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

        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        List<io.eventlens.core.model.StoredEvent> events = source.reader().getEvents(id);
        String aggregateType = events.isEmpty() ? null : events.getFirst().aggregateType();
        if (!routeAuthorizer.require(ctx, Permission.EXPORT_AGGREGATE, source.id(), aggregateType)) {
            return;
        }
        String content = exportEngine.export(
                id,
                events.stream().map(sensitiveDataProtector::maskEvent).toList(),
                format == ExportEngine.Format.MARKDOWN ? sensitiveDataProtector.maskTransitions(source.replayEngine().replayFull(id)) : null,
                format);
        EventLensMetrics.recordSensitiveAction("export", "success");

        String contentType = switch (format) {
            case MARKDOWN      -> "text/markdown";
            case CSV           -> "text/csv";
            case JUNIT_FIXTURE -> "text/plain";
            default            -> "application/json";
        };

        // 1.8 — audit
        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_EXPORT)
                .resourceType(AuditEvent.RT_EXPORT)
                .resourceId(id)
                .details(Map.of("format", formatStr, "byteCount", content.length(), "source", source.id()))
                .build());

        ctx.contentType(contentType).result(content);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
}
