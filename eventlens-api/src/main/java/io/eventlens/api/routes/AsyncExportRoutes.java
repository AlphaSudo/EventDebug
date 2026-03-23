package io.eventlens.api.routes;

import io.eventlens.api.export.ExportJob;
import io.eventlens.api.export.ExportService;
import io.eventlens.core.InputValidator;
import io.javalin.http.Context;

import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

public final class AsyncExportRoutes {

    private final ExportService exportService;

    public AsyncExportRoutes(ExportService exportService) {
        this.exportService = exportService;
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

        ExportJob job = exportService.startAggregateExport(
                aggregateId,
                req.format,
                limit,
                Map.of(
                        "userId", userId(ctx),
                        "authMethod", authMethod(ctx),
                        "clientIp", clientIp(ctx),
                        "requestId", requestId(ctx),
                        "userAgent", ctx.userAgent() != null ? ctx.userAgent() : "unknown"
                ));

        ctx.status(202).json(Map.of(
                "exportId", job.exportId(),
                "status", job.status().name(),
                "pollUrl", "/api/events/export/" + job.exportId()
        ));
    }

    /** GET /api/events/export/{exportId} */
    public void status(Context ctx) {
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
                if (f != null && Files.exists(f)) fileSize = Files.size(f);
            } catch (Exception ignored) {}

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
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "download_failed", "message", e.getMessage()));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String userId(Context ctx) {
        String v = ctx.attribute("auditUserId");
        return v != null ? v : "anonymous";
    }

    private static String authMethod(Context ctx) {
        String v = ctx.attribute("auditAuthMethod");
        return v != null ? v : "anonymous";
    }

    private static String clientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int c = xff.indexOf(',');
            return (c >= 0 ? xff.substring(0, c) : xff).trim();
        }
        String xri = ctx.header("X-Real-IP");
        return xri != null && !xri.isBlank() ? xri.trim() : ctx.ip();
    }

    private static String requestId(Context ctx) {
        String v = ctx.attribute("requestId");
        return v != null ? v : "unknown";
    }
}

