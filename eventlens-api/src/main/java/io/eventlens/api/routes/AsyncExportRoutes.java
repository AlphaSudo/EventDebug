package io.eventlens.api.routes;

import io.eventlens.api.export.ExportJob;
import io.eventlens.api.export.ExportService;
import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.core.InputValidator;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

public final class AsyncExportRoutes {

    private final ExportService exportService;
    private final RouteAuthorizer routeAuthorizer;

    public AsyncExportRoutes(ExportService exportService, RouteAuthorizer routeAuthorizer) {
        this.exportService = exportService;
        this.routeAuthorizer = routeAuthorizer;
    }

    public record ExportRequest(String aggregateId, String format, Integer limit) {
    }

    /** POST /api/events/export */
    public void start(Context ctx) {
        ExportRequest req = ctx.bodyAsClass(ExportRequest.class);
        if (req == null || req.aggregateId == null || req.aggregateId.isBlank()) {
            ctx.status(400).json(Map.of("error", "Missing field: aggregateId"));
            return;
        }

        String aggregateId = InputValidator.validateAggregateId(req.aggregateId);
        int limit = req.limit != null ? Math.max(1, req.limit) : 50_000;
        if (!routeAuthorizer.require(ctx, Permission.START_EXPORT, null, null)) {
            return;
        }

        ExportJob job = exportService.startAggregateExport(
                aggregateId,
                req.format,
                limit,
                Map.of(
                        "userId", SecurityContext.principal(ctx).userId(),
                        "authMethod", SecurityContext.principal(ctx).authMethod(),
                        "clientIp", SecurityContext.clientIp(ctx),
                        "requestId", SecurityContext.requestId(ctx),
                        "userAgent", ctx.userAgent() != null ? ctx.userAgent() : "unknown"
                ));
        EventLensMetrics.recordSensitiveAction("export_async", "started");

        ctx.status(202).json(Map.of(
                "exportId", job.exportId(),
                "status", job.status().name(),
                "pollUrl", "/api/events/export/" + job.exportId()
        ));
    }

    /** GET /api/events/export/{exportId} */
    public void status(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.START_EXPORT, null, null)) {
            return;
        }
        String exportId = ctx.pathParam("exportId");
        ExportJob job = exportService.get(exportId);
        if (job == null) {
            ctx.status(404).json(Map.of("error", "not_found", "message", "Export not found or expired"));
            return;
        }

        if (job.status() == ExportJob.Status.COMPLETED) {
            long fileSize = 0L;
            try {
                var f = job.file();
                if (f != null && Files.exists(f)) {
                    fileSize = Files.size(f);
                }
            } catch (Exception ignored) {
            }

            ctx.json(Map.of(
                    "exportId", job.exportId(),
                    "status", job.status().name(),
                    "downloadUrl", "/api/events/export/" + job.exportId() + "/download",
                    "eventCount", job.processedEvents(),
                    "fileSize", fileSize,
                    "format", job.format(),
                    "expiresAt", job.expiresAt().toString()
            ));
            return;
        }

        if (job.status() == ExportJob.Status.FAILED) {
            ctx.status(500).json(Map.of(
                    "exportId", job.exportId(),
                    "status", job.status().name(),
                    "error", job.error()
            ));
            return;
        }

        ctx.json(Map.of(
                "exportId", job.exportId(),
                "status", job.status().name(),
                "progress", job.progress(),
                "processedEvents", job.processedEvents(),
                "totalEstimate", job.totalEstimate(),
                "expiresAt", job.expiresAt().toString()
        ));
    }

    /** GET /api/events/export/{exportId}/download */
    public void download(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.START_EXPORT, null, null)) {
            return;
        }
        String exportId = ctx.pathParam("exportId");
        var file = exportService.getDownloadFile(exportId);
        if (file == null) {
            ctx.status(404).json(Map.of("error", "not_found", "message", "Export not ready or expired"));
            return;
        }

        String contentType = file.toString().endsWith(".csv") ? "text/csv" : "application/json";
        String filename = "eventlens-export-" + Instant.now().toString().replace(":", "-")
                + (file.toString().endsWith(".csv") ? ".csv" : ".json");

        ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        ctx.contentType(contentType);
        try {
            ctx.result(Files.newInputStream(file));
            EventLensMetrics.recordSensitiveAction("export_async_download", "success");
        } catch (Exception e) {
            EventLensMetrics.recordSensitiveAction("export_async_download", "failure");
            ctx.status(500).json(Map.of("error", "download_failed", "message", e.getMessage()));
        }
    }
}
