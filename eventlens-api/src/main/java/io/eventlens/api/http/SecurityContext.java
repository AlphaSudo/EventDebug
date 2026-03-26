package io.eventlens.api.http;

import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.security.Principal;
import io.javalin.http.Context;

/**
 * Shared request-security and audit-context helper.
 */
public final class SecurityContext {

    public static final String ATTR_PRINCIPAL = "eventlensPrincipal";
    private static final String LEGACY_AUDIT_USER_ID = "auditUserId";
    private static final String LEGACY_AUDIT_AUTH_METHOD = "auditAuthMethod";

    private SecurityContext() {
    }

    public static void setPrincipal(Context ctx, Principal principal) {
        Principal effective = principal == null ? Principal.anonymous() : principal;
        ctx.attribute(ATTR_PRINCIPAL, effective);
        // Preserve legacy attributes while the rest of the codebase is still migrating.
        ctx.attribute(LEGACY_AUDIT_USER_ID, effective.userId());
        ctx.attribute(LEGACY_AUDIT_AUTH_METHOD, effective.authMethod());
    }

    public static Principal principal(Context ctx) {
        Principal principal = ctx.attribute(ATTR_PRINCIPAL);
        return principal != null ? principal : Principal.anonymous();
    }

    public static AuditEvent.Builder audit(Context ctx) {
        Principal principal = principal(ctx);
        return AuditEvent.builder()
                .userId(principal.userId())
                .authMethod(principal.authMethod())
                .clientIp(clientIp(ctx))
                .requestId(requestId(ctx))
                .userAgent(ctx.userAgent());
    }

    public static String clientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int c = xff.indexOf(',');
            return (c >= 0 ? xff.substring(0, c) : xff).trim();
        }
        String xri = ctx.header("X-Real-IP");
        return xri != null && !xri.isBlank() ? xri.trim() : ctx.ip();
    }

    public static String requestId(Context ctx) {
        String requestId = ctx.attribute("requestId");
        return requestId != null ? requestId : "unknown";
    }
}
