package io.eventlens.api.routes;

import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.http.Context;

import java.util.Map;

/**
 * Aggregate search, type listing, and recent event endpoints.
 *
 * <p>v2 — emits {@link AuditEvent#ACTION_SEARCH} audit entries for every
 * search request.
 */
public class AggregateRoutes {

    /** Hard cap on any client-supplied limit to prevent unbounded DB queries. */
    private static final int MAX_LIMIT = 1_000;

    private final EventStoreReader reader;
    private final AuditLogger auditLogger;

    public AggregateRoutes(EventStoreReader reader, AuditLogger auditLogger) {
        this.reader      = reader;
        this.auditLogger = auditLogger;
    }

    /** GET /api/aggregates/search?q=ACC&limit=20 */
    public void search(Context ctx) {
        String query = ctx.queryParam("q");
        if (query == null || query.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing query parameter: q"));
            return;
        }
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 20, MAX_LIMIT),
                MAX_LIMIT);

        var result = reader.searchAggregates(query, limit);

        // 1.8 — audit
        auditLogger.log(AuditEvent.builder()
                .action(AuditEvent.ACTION_SEARCH)
                .resourceType(AuditEvent.RT_AGGREGATE)
                .userId(userId(ctx))
                .authMethod(authMethod(ctx))
                .clientIp(clientIp(ctx))
                .requestId(requestId(ctx))
                .userAgent(ctx.userAgent())
                .details(Map.of("q", query, "limit", limit, "resultCount", result.size()))
                .build());

        ctx.json(result);
    }

    /** GET /api/meta/types */
    public void types(Context ctx) {
        ctx.json(reader.getAggregateTypes());
    }

    /** GET /api/events/recent?limit=50 */
    public void recentEvents(Context ctx) {
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 50, MAX_LIMIT),
                MAX_LIMIT);
        ctx.json(reader.getRecentEvents(limit));
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
