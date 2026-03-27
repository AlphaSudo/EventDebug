package io.eventlens.api.routes;

import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.core.InputValidator;
import io.eventlens.core.JsonUtil;
import io.eventlens.core.metadata.AuditLogRecord;
import io.eventlens.core.metadata.AuditLogRepository;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AuditRoutes {

    private final AuditLogRepository auditLogRepository;
    private final RouteAuthorizer routeAuthorizer;

    public AuditRoutes(AuditLogRepository auditLogRepository, RouteAuthorizer routeAuthorizer) {
        this.auditLogRepository = auditLogRepository;
        this.routeAuthorizer = routeAuthorizer;
    }

    public void recent(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.VIEW_AUDIT_LOG, null, null)) {
            return;
        }
        if (auditLogRepository == null) {
            ctx.status(503).json(Map.of(
                    "error", "audit_unavailable",
                    "message", "Metadata-backed audit storage is disabled"
            ));
            return;
        }

        int limit = InputValidator.validateLimit(ctx.queryParam("limit"), 50, 500);
        String action = blankToNull(ctx.queryParam("action"));
        String userId = blankToNull(ctx.queryParam("userId"));

        List<Map<String, Object>> entries = auditLogRepository.findRecent(limit, action, userId).stream()
                .map(AuditRoutes::toPayload)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("entries", entries);
        response.put("limit", limit);
        response.put("action", action);
        response.put("userId", userId);
        ctx.json(response);
    }

    private static Map<String, Object> toPayload(AuditLogRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("auditId", record.auditId());
        payload.put("action", record.action());
        payload.put("resourceType", record.resourceType());
        payload.put("resourceId", record.resourceId());
        payload.put("userId", record.userId());
        payload.put("authMethod", record.authMethod());
        payload.put("clientIp", record.clientIp());
        payload.put("requestId", record.requestId());
        payload.put("userAgent", record.userAgent());
        payload.put("details", JsonUtil.parseMap(record.detailsJson()));
        payload.put("createdAt", record.createdAt());
        return payload;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
