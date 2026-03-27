package io.eventlens.api.routes;

import io.eventlens.api.http.ConditionalGet;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

public final class PluginRoutes {

    private final SourceRegistry sourceRegistry;
    private final RouteAuthorizer routeAuthorizer;

    public PluginRoutes(SourceRegistry sourceRegistry, RouteAuthorizer routeAuthorizer) {
        this.sourceRegistry = sourceRegistry;
        this.routeAuthorizer = routeAuthorizer;
    }

    public void list(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.VIEW_PLUGINS, null, null)) {
            return;
        }
        ConditionalGet.json(ctx, sourceRegistry.listPlugins());
    }
}
