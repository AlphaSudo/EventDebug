package io.eventlens.api.routes;

import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.exception.ConditionParseException;
import io.javalin.http.Context;

import java.util.Map;

/**
 * Bisect endpoint — binary search for the event that caused a condition.
 * Reads the condition expression from the raw request body for simplicity.
 */
public class BisectRoutes {

    private final BisectEngine bisectEngine;

    public BisectRoutes(BisectEngine bisectEngine) {
        this.bisectEngine = bisectEngine;
    }

    /** POST /api/aggregates/{id}/bisect — body: "balance < 0" */
    public void bisect(Context ctx) {
        String expression = ctx.body().trim();
        if (expression.isBlank()) {
            ctx.status(400).json(Map.of("error", "Request body must contain a condition expression"));
            return;
        }
        try {
            var predicate = BisectEngine.parseCondition(expression);
            ctx.json(bisectEngine.bisect(ctx.pathParam("id"), predicate));
        } catch (ConditionParseException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }
}
