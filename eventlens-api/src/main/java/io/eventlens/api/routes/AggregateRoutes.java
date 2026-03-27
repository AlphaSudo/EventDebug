package io.eventlens.api.routes;

import io.eventlens.api.cache.QueryResultCache;
import io.eventlens.api.http.ConditionalGet;
import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.security.Permission;
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
    private final RouteAuthorizer routeAuthorizer;

    public AggregateRoutes(
            SourceRegistry sourceRegistry,
            AuditLogger auditLogger,
            QueryResultCache queryCache,
            Duration searchTtl,
            RouteAuthorizer routeAuthorizer) {
        this.sourceRegistry = sourceRegistry;
        this.auditLogger = auditLogger;
        this.queryCache = queryCache;
        this.searchTtl = searchTtl;
        this.routeAuthorizer = routeAuthorizer;
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
        if (!routeAuthorizer.require(ctx, Permission.SEARCH_AGGREGATES, source.id(), null)) {
            return;
        }

        var result = queryCache.getOrCompute(
                "aggregate-search",
                source.id() + "|" + query + "|" + limit,
                searchTtl,
                () -> source.reader().searchAggregates(query, limit));

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_SEARCH)
                .resourceType(AuditEvent.RT_AGGREGATE)
                .details(Map.of(
                        "q", query,
                        "limit", limit,
                        "source", source.id(),
                        "resultCount", result.size()))
                .build());

        ConditionalGet.json(ctx, result);
    }

    public void types(Context ctx) {
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_AGGREGATE_TYPES, source.id(), null)) {
            return;
        }
        EventStoreReader reader = source.reader();
        ConditionalGet.json(ctx, reader.getAggregateTypes());
    }

    public void recentEvents(Context ctx) {
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 50, MAX_LIMIT),
                MAX_LIMIT);
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_RECENT_EVENTS, source.id(), null)) {
            return;
        }
        EventStoreReader reader = source.reader();
        ConditionalGet.json(ctx, reader.getRecentEvents(limit));
    }
}
