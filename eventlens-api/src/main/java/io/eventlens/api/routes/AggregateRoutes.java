package io.eventlens.api.routes;

import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/** Aggregate search, type listing, and recent event endpoints. */
public class AggregateRoutes {

    private final EventStoreReader reader;
    private final ReplayEngine replayEngine;

    public AggregateRoutes(EventStoreReader reader, ReplayEngine replayEngine) {
        this.reader = reader;
        this.replayEngine = replayEngine;
    }

    /** GET /api/aggregates/search?q=ACC&limit=20 */
    public void search(Context ctx) {
        String query = ctx.queryParam("q");
        if (query == null || query.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing query parameter: q"));
            return;
        }
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
        ctx.json(reader.searchAggregates(query, limit));
    }

    /** GET /api/meta/types */
    public void types(Context ctx) {
        ctx.json(reader.getAggregateTypes());
    }

    /** GET /api/events/recent?limit=50 */
    public void recentEvents(Context ctx) {
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(50);
        ctx.json(reader.getRecentEvents(limit));
    }
}
