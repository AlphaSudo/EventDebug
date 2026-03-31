package io.eventlens.api.routes;

import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.io.InputStream;

public final class OpenApiRoutes {

    private final RouteAuthorizer routeAuthorizer;

    public OpenApiRoutes(RouteAuthorizer routeAuthorizer) {
        this.routeAuthorizer = routeAuthorizer;
    }

    /** GET /api/v1/openapi.json */
    public void spec(Context ctx) throws Exception {
        if (!routeAuthorizer.require(ctx, Permission.VIEW_OPENAPI, null, null)) {
            return;
        }
        InputStream in = getClass().getClassLoader().getResourceAsStream("openapi.json");
        if (in == null) {
            ctx.status(404).result("openapi.json not found");
            return;
        }
        ctx.contentType("application/json");
        ctx.result(in);
    }
}
