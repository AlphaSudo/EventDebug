package io.eventlens.api.routes;

import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.metadata.ApiKeyRecord;
import io.eventlens.core.security.ApiKeyService;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApiKeyRoutes {

    private final ApiKeyService apiKeyService;
    private final RouteAuthorizer routeAuthorizer;
    private final AuditLogger auditLogger;

    public ApiKeyRoutes(ApiKeyService apiKeyService, RouteAuthorizer routeAuthorizer, AuditLogger auditLogger) {
        this.apiKeyService = apiKeyService;
        this.routeAuthorizer = routeAuthorizer;
        this.auditLogger = auditLogger;
    }

    public void list(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.MANAGE_API_KEYS, null, null)) {
            return;
        }
        List<Map<String, Object>> entries = apiKeyService.list().stream()
                .map(ApiKeyRoutes::toPayload)
                .toList();
        ctx.json(Map.of("entries", entries));
    }

    public void create(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.MANAGE_API_KEYS, null, null)) {
            return;
        }

        CreateApiKeyRequest request = ctx.bodyAsClass(CreateApiKeyRequest.class);
        if (request == null || blank(request.principalUserId())) {
            ctx.status(400).json(Map.of("error", "principal_user_id_required"));
            return;
        }
        if (request.roles() == null || request.roles().isEmpty()) {
            ctx.status(400).json(Map.of("error", "roles_required"));
            return;
        }

        Instant expiresAt = null;
        if (!blank(request.expiresAt())) {
            try {
                expiresAt = Instant.parse(request.expiresAt());
            } catch (Exception e) {
                ctx.status(400).json(Map.of("error", "invalid_expires_at"));
                return;
            }
        }

        ApiKeyService.IssuedApiKey issued = apiKeyService.issue(
                request.principalUserId().trim(),
                request.roles().stream().filter(role -> role != null && !role.isBlank()).map(String::trim).toList(),
                blank(request.description()) ? null : request.description().trim(),
                expiresAt);
        EventLensMetrics.recordApiKeyLifecycle("created");

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_CREATE_API_KEY)
                .resourceType(AuditEvent.RT_AUTH)
                .resourceId(issued.apiKeyId())
                .details(Map.of(
                        "principalUserId", issued.principalUserId(),
                        "roles", issued.scopes(),
                        "keyPrefix", issued.keyPrefix()
                ))
                .build());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("apiKeyId", issued.apiKeyId());
        response.put("keyPrefix", issued.keyPrefix());
        response.put("apiKey", issued.apiKey());
        response.put("description", issued.description());
        response.put("principalUserId", issued.principalUserId());
        response.put("roles", issued.scopes());
        response.put("createdAt", issued.createdAt());
        response.put("expiresAt", issued.expiresAt());
        ctx.status(201).json(response);
    }

    public void revoke(Context ctx) {
        if (!routeAuthorizer.require(ctx, Permission.MANAGE_API_KEYS, null, null)) {
            return;
        }

        String apiKeyId = ctx.pathParam("id");
        apiKeyService.revoke(apiKeyId, Instant.now());
        EventLensMetrics.recordApiKeyLifecycle("revoked");

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_REVOKE_API_KEY)
                .resourceType(AuditEvent.RT_AUTH)
                .resourceId(apiKeyId)
                .details(Map.of("path", ctx.path()))
                .build());

        ctx.json(Map.of("apiKeyId", apiKeyId, "revoked", true));
    }

    private static Map<String, Object> toPayload(ApiKeyRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiKeyId", record.apiKeyId());
        payload.put("keyPrefix", record.keyPrefix());
        payload.put("description", record.description());
        payload.put("principalUserId", record.principalUserId());
        payload.put("roles", record.scopes());
        payload.put("createdAt", record.createdAt());
        payload.put("expiresAt", record.expiresAt());
        payload.put("revokedAt", record.revokedAt());
        payload.put("lastUsedAt", record.lastUsedAt());
        return payload;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record CreateApiKeyRequest(String principalUserId, List<String> roles, String description, String expiresAt) {
    }
}
