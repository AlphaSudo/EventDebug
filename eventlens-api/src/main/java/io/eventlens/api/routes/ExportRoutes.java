package io.eventlens.api.routes;

import io.eventlens.core.engine.ExportEngine;
import io.javalin.http.Context;

import java.util.Map;

/** Export endpoints — downloads aggregate event history in various formats. */
public class ExportRoutes {

    private final ExportEngine exportEngine;

    public ExportRoutes(ExportEngine exportEngine) {
        this.exportEngine = exportEngine;
    }

    /** GET /api/aggregates/{id}/export?format=json|markdown|csv|junit */
    public void export(Context ctx) {
        String formatStr = ctx.queryParamAsClass("format", String.class).getOrDefault("json");
        ExportEngine.Format format = switch (formatStr.toLowerCase()) {
            case "markdown" -> ExportEngine.Format.MARKDOWN;
            case "csv" -> ExportEngine.Format.CSV;
            case "junit" -> ExportEngine.Format.JUNIT_FIXTURE;
            default -> ExportEngine.Format.JSON;
        };

        String content = exportEngine.export(ctx.pathParam("id"), format);

        String contentType = switch (format) {
            case MARKDOWN -> "text/markdown";
            case CSV -> "text/csv";
            case JUNIT_FIXTURE -> "text/plain";
            default -> "application/json";
        };

        ctx.contentType(contentType).result(content);
    }
}
