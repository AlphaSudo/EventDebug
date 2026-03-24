package io.eventlens.api.routes;

import io.eventlens.api.http.ConditionalGet;
import io.eventlens.api.source.SourceRegistry;
import io.javalin.http.Context;

public final class PluginRoutes {

    private final SourceRegistry sourceRegistry;

    public PluginRoutes(SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
    }

    public void list(Context ctx) {
        ConditionalGet.json(ctx, sourceRegistry.listPlugins());
    }
}
