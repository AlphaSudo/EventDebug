package io.eventlens.api.routes;

import io.javalin.http.Context;

import java.io.InputStream;

public final class OpenApiRoutes {

    /** GET /api/v1/openapi.json */
    public void spec(Context ctx) throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("openapi.json");
        if (in == null) {
            ctx.status(404).result("openapi.json not found");
            return;
        }
        ctx.contentType("application/json");
        ctx.result(in);
    }
}

