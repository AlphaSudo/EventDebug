package io.eventlens.api.routes;

import io.eventlens.api.http.ConditionalGet;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

public final class DatasourceRoutes {

    private final SourceRegistry sourceRegistry;
    private final RouteAuthorizer routeAuthorizer;

    public DatasourceRoutes(SourceRegistry sourceRegistry, RouteAuthorizer routeAuthorizer) {
        this.sourceRegistry = sourceRegistry;
        this.routeAuthorizer = routeAuthorizer;
    }

    public void list(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.VIEW_DATASOURCES, null, null)) {
            return;
        }
        ConditionalGet.json(ctx, sourceRegistry.listDatasources());
    }

    public void health(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.VIEW_DATASOURCES, ctx.pathParam("id"), null)) {
            return;
        }
        ConditionalGet.json(ctx, sourceRegistry.datasourceHealth(ctx.pathParam("id")));
    }
}
