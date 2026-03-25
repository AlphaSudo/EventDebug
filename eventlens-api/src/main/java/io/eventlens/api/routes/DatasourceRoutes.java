package io.eventlens.api.routes;

import io.eventlens.api.http.ConditionalGet;
import io.eventlens.api.source.SourceRegistry;
import io.javalin.http.Context;

public final class DatasourceRoutes {

    private final SourceRegistry sourceRegistry;

    public DatasourceRoutes(SourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
    }

    public void list(Context ctx) {
        ConditionalGet.json(ctx, sourceRegistry.listDatasources());
    }

    public void health(Context ctx) {
        ConditionalGet.json(ctx, sourceRegistry.datasourceHealth(ctx.pathParam("id")));
    }
}
