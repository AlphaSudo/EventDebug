package io.eventlens.api.security;

import io.eventlens.api.http.SecurityContext;
import io.eventlens.core.security.AuthorizationDecision;
import io.eventlens.core.security.AuthorizationDecisionReason;
import io.eventlens.core.security.AuthorizationService;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RouteAuthorizer {

    private final AuthorizationService authorizationService;

    public RouteAuthorizer(AuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    public boolean require(Context ctx, Permission permission, String sourceId, String aggregateType) {
        AuthorizationDecision decision = authorizationService.authorize(
                SecurityContext.principal(ctx),
                permission,
                sourceId,
                aggregateType
        );
        if (decision.allowed()) {
            return true;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", decision.reason() == AuthorizationDecisionReason.DENY_AUTH_REQUIRED ? "auth_required" : "forbidden");
        payload.put("reason", decision.reason().name());
        payload.put("permission", permission.name());
        if (sourceId != null && !sourceId.isBlank()) {
            payload.put("source", sourceId);
        }
        if (aggregateType != null && !aggregateType.isBlank()) {
            payload.put("aggregateType", aggregateType);
        }
        payload.put("roles", decision.resolvedRoles());

        ctx.status(decision.reason() == AuthorizationDecisionReason.DENY_AUTH_REQUIRED ? 401 : 403).json(payload);
        return false;
    }
}
