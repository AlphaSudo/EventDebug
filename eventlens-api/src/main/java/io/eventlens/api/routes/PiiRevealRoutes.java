package io.eventlens.api.routes;

import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.util.Map;

public final class PiiRevealRoutes {

    private final SourceRegistry sourceRegistry;
    private final AuditLogger auditLogger;
    private final RouteAuthorizer routeAuthorizer;

    public PiiRevealRoutes(SourceRegistry sourceRegistry, AuditLogger auditLogger, RouteAuthorizer routeAuthorizer) {
        this.sourceRegistry = sourceRegistry;
        this.auditLogger = auditLogger;
        this.routeAuthorizer = routeAuthorizer;
    }

    public record RevealRequest(String reason) {
    }

    public void revealEvent(Context ctx) {
        String aggregateId = InputValidator.validateAggregateId(ctx.pathParam("id"));
        long sequence = Long.parseLong(ctx.pathParam("seq"));
        RevealRequest request = ctx.bodyAsClass(RevealRequest.class);
        String reason = request != null ? request.reason : null;
        if (reason == null || reason.isBlank()) {
            EventLensMetrics.recordSensitiveAction("reveal_pii", "rejected");
            ctx.status(400).json(Map.of("error", "reason_required"));
            return;
        }

        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        StoredEvent event = source.reader().getEventsUpTo(aggregateId, sequence).stream()
                .filter(candidate -> candidate.sequenceNumber() == sequence)
                .reduce((left, right) -> right)
                .orElse(null);
        if (event == null) {
            EventLensMetrics.recordSensitiveAction("reveal_pii", "not_found");
            ctx.status(404).json(Map.of("error", "not_found", "message", "Event not found"));
            return;
        }

        if (!routeAuthorizer.require(ctx, Permission.REVEAL_PII, source.id(), event.aggregateType())) {
            return;
        }

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_REVEAL_PII)
                .resourceType(AuditEvent.RT_EVENT)
                .resourceId(event.eventId())
                .details(Map.of(
                        "aggregateId", aggregateId,
                        "sequence", sequence,
                        "source", source.id(),
                        "reason", reason
                ))
                .build());
        EventLensMetrics.recordSensitiveAction("reveal_pii", "success");

        ctx.json(Map.of(
                "event", event,
                "reason", reason
        ));
    }
}
