package io.eventlens.api.routes;

import io.eventlens.api.cache.QueryResultCache;
import io.eventlens.api.http.ConditionalGet;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.http.Context;

import java.time.Duration;
import java.util.Map;

/**
 * Aggregate search, type listing, and recent event endpoints.
 *
 * <p>v2 - emits {@link AuditEvent#ACTION_SEARCH} audit entries for every
 * search request.
 */
public class AggregateRoutes {

    private static final int MAX_LIMIT = 1_000;

    private final SourceRegistry sourceRegistry;
    private final AuditLogger auditLogger;
    private final QueryResultCache queryCache;
    private final Duration searchTtl;

    public AggregateRoutes(
            SourceRegistry sourceRegistry,
            AuditLogger auditLogger,
            QueryResultCache queryCache,
            Duration searchTtl) {
        this.sourceRegistry = sourceRegistry;
        this.auditLogger = auditLogger;
        this.queryCache = queryCache;
        this.searchTtl = searchTtl;
    }

    public void search(Context ctx) {
        String query = ctx.queryParam("q");
        if (query == null || query.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing query parameter: q"));
            return;
        }

        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 20, MAX_LIMIT),
                MAX_LIMIT);
        var source = sourceRegistry.resolve(ctx.queryParam("source"));

        var result = queryCache.getOrCompute(
                "aggregate-search",
                source.id() + "|" + query + "|" + limit,
                searchTtl,
                () -> source.reader().searchAggregates(query, limit));

        auditLogger.log(AuditEvent.builder()
                .action(AuditEvent.ACTION_SEARCH)
                .resourceType(AuditEvent.RT_AGGREGATE)
                .userId(userId(ctx))
                .authMethod(authMethod(ctx))
                .clientIp(clientIp(ctx))
                .requestId(requestId(ctx))
                .userAgent(ctx.userAgent())
                .details(Map.of(
                        "q", query,
                        "limit", limit,
                        "source", source.id(),
                        "resultCount", result.size()))
                .build());

        ConditionalGet.json(ctx, result);
    }

    public void types(Context ctx) {
        EventStoreReader reader = sourceRegistry.resolve(ctx.queryParam("source")).reader();
        ConditionalGet.json(ctx, reader.getAggregateTypes());
    }

    public void recentEvents(Context ctx) {
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 50, MAX_LIMIT),
                MAX_LIMIT);
        EventStoreReader reader = sourceRegistry.resolve(ctx.queryParam("source")).reader();
        ConditionalGet.json(ctx, reader.getRecentEvents(limit));
    }

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
