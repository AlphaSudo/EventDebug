package io.eventlens.api.routes;

import io.eventlens.core.engine.ReplayEngine;
import io.javalin.http.Context;

/** Timeline, replay, and state transition endpoints. */
public class TimelineRoutes {

    private final ReplayEngine replayEngine;

    public TimelineRoutes(ReplayEngine replayEngine) {
        this.replayEngine = replayEngine;
    }

    /** GET /api/aggregates/{id}/timeline */
    public void getTimeline(Context ctx) {
        ctx.json(replayEngine.buildTimeline(ctx.pathParam("id")));
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
