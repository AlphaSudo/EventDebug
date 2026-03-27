package io.eventlens.api.routes;

import io.eventlens.core.InputValidator;
import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.exception.ConditionParseException;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.util.Map;

/**
 * Bisect endpoint — binary search for the event that caused a condition.
 * Reads the condition expression from the raw request body for simplicity.
 */
public class BisectRoutes {

    private final BisectEngine bisectEngine;
    private final RouteAuthorizer routeAuthorizer;

    public BisectRoutes(BisectEngine bisectEngine, RouteAuthorizer routeAuthorizer) {
        this.bisectEngine = bisectEngine;
        this.routeAuthorizer = routeAuthorizer;
    }

    /** POST /api/aggregates/{id}/bisect — body: "balance < 0" */
    public void bisect(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        if (!routeAuthorizer.require(ctx, Permission.EXECUTE_BISECT, null, null)) {
            return;
        }
        String expression = ctx.body().trim();
        if (expression.isBlank()) {
            ctx.status(400).json(Map.of("error", "Request body must contain a condition expression"));
            return;
        }
        try {
            var predicate = BisectEngine.parseCondition(expression);
            ctx.json(bisectEngine.bisect(id, predicate));
        } catch (ConditionParseException e) {
            ctx.status(400).json(Map.of("error", e.getMessage()));
        }
    }
}
