package io.eventlens.api.routes;

import io.eventlens.core.InputValidator;
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
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 500, MAX_LIMIT),
                MAX_LIMIT);
        int offset = InputValidator.validateOffset(ctx.queryParam("offset"));
        ctx.json(replayEngine.buildTimeline(id, limit, offset));
    }

    /** GET /api/aggregates/{id}/replay */
    public void replay(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        ctx.json(replayEngine.replayFull(id));
    }

    /** GET /api/aggregates/{id}/replay/{seq} */
    public void replayTo(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        long seq = Long.parseLong(ctx.pathParam("seq"));
        ctx.json(replayEngine.replayTo(id, seq));
    }

    /** GET /api/aggregates/{id}/transitions */
    public void transitions(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        ctx.json(replayEngine.replayFull(id));
    }
}
