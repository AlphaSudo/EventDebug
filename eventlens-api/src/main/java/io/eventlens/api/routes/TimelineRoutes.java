package io.eventlens.api.routes;

import io.eventlens.core.engine.ReplayEngine;
import io.javalin.http.Context;

/** Timeline, replay, and state transition endpoints. */
public class TimelineRoutes {

    /** Hard cap on any client-supplied limit to prevent unbounded DB queries. */
    private static final int MAX_LIMIT = 1_000;

    private final ReplayEngine replayEngine;

    public TimelineRoutes(ReplayEngine replayEngine) {
        this.replayEngine = replayEngine;
    }

    /** GET /api/aggregates/{id}/timeline */
    public void getTimeline(Context ctx) {
        int limit = Math.min(ctx.queryParamAsClass("limit", Integer.class).getOrDefault(500), MAX_LIMIT);
        int offset = Math.max(ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0), 0);
        ctx.json(replayEngine.buildTimeline(ctx.pathParam("id"), limit, offset));
    }

    /** GET /api/aggregates/{id}/replay */
    public void replay(Context ctx) {
        ctx.json(replayEngine.replayFull(ctx.pathParam("id")));
    }

    /** GET /api/aggregates/{id}/replay/{seq} */
    public void replayTo(Context ctx) {
        long seq = Long.parseLong(ctx.pathParam("seq"));
        ctx.json(replayEngine.replayTo(ctx.pathParam("id"), seq));
    }

    /** GET /api/aggregates/{id}/transitions */
    public void transitions(Context ctx) {
        ctx.json(replayEngine.replayFull(ctx.pathParam("id")));
    }
}
