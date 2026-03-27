package io.eventlens.api.routes;

import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.security.BasicAuthenticator;
import io.eventlens.api.security.CsrfTokens;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.security.Principal;
import io.eventlens.core.security.SessionService;
import io.javalin.http.Context;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal auth/session endpoints for the v5 auth transition.
 */
public final class AuthRoutes {

    private final SessionService sessionService;
    private final EventLensConfig.SessionConfig sessionConfig;
    private final BasicAuthenticator basicAuthenticator;
    private final AuditLogger auditLogger;
    private final String authProvider;
    private final boolean basicLoginEnabled;

    public AuthRoutes(
            SessionService sessionService,
            EventLensConfig.SessionConfig sessionConfig,
            BasicAuthenticator basicAuthenticator,
            AuditLogger auditLogger,
            String authProvider,
            boolean basicLoginEnabled) {
        this.sessionService = sessionService;
        this.sessionConfig = sessionConfig;
        this.basicAuthenticator = basicAuthenticator;
        this.auditLogger = auditLogger;
        this.authProvider = authProvider;
        this.basicLoginEnabled = basicLoginEnabled;
    }

    public void session(Context ctx) {
        Principal principal = SecurityContext.principal(ctx);
        if (!principal.authenticated()) {
            ctx.json(Map.of(
                    "authenticated", false,
                    "provider", authProvider,
                    "basicLoginEnabled", basicLoginEnabled
            ));
            return;
        }

        ctx.json(Map.of(
                "authenticated", true,
                "provider", authProvider,
                "basicLoginEnabled", basicLoginEnabled,
                "csrfToken", SecurityContext.csrfToken(ctx),
                "principal", principalPayload(principal)
        ));
    }

    public void logout(Context ctx) {
        String expectedCsrf = SecurityContext.csrfToken(ctx);
        String suppliedCsrf = ctx.header("X-CSRF-Token");
        if (expectedCsrf != null && !expectedCsrf.equals(suppliedCsrf)) {
            EventLensMetrics.recordSessionLifecycle("logout_csrf_rejected");
            ctx.status(403).json(Map.of("error", "csrf_required"));
            return;
        }

        String sessionId = ctx.cookie(sessionConfig.getCookieName());
        if (sessionId != null && !sessionId.isBlank()) {
            sessionService.invalidate(sessionId);
            EventLensMetrics.recordSessionLifecycle("invalidated");
        }
        expireCookie(ctx);

        auditLogger.log(SecurityContext.audit(ctx)
                .action("LOGOUT")
                .resourceType(AuditEvent.RT_AUTH)
                .details(Map.of("path", ctx.path()))
                .build());

        ctx.json(Map.of("authenticated", false));
    }

    public void createBasicSession(Context ctx) {
        var authResult = basicAuthenticator.authenticate(ctx);
        if (!authResult.success()) {
            EventLensMetrics.recordAuthAttempt("basic", "failure");
            auditLogger.log(SecurityContext.audit(ctx)
                    .action(AuditEvent.ACTION_LOGIN_FAILED)
                    .resourceType(AuditEvent.RT_AUTH)
                    .userId(authResult.attemptedUserId() != null ? authResult.attemptedUserId() : "anonymous")
                    .authMethod("basic")
                    .details(Map.of("reason", authResult.failureReason(), "path", ctx.path()))
                    .build());

            ctx.status(401)
                    .header("WWW-Authenticate", authResult.challengeHeader())
                    .json(Map.of("error", "Unauthorized"));
            return;
        }

        EventLensMetrics.recordAuthAttempt("basic", "success");
        Principal principal = authResult.principal();
        String returnHash = sanitizeReturnHash(ctx.bodyAsClass(BasicSessionRequest.class).returnHash());
        String csrfToken = CsrfTokens.generate();
        var session = sessionService.create(principal, Map.of(
                "returnHash", returnHash,
                "csrfToken", csrfToken
        ));
        EventLensMetrics.recordSessionLifecycle("created");
        setSessionCookie(ctx, session.sessionId());
        SecurityContext.setPrincipal(ctx, principal);
        SecurityContext.setSession(ctx, session);

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_LOGIN)
                .resourceType(AuditEvent.RT_AUTH)
                .details(Map.of("path", ctx.path(), "session", true))
                .build());

        ctx.json(Map.of(
                "authenticated", true,
                "principal", principalPayload(principal),
                "csrfToken", csrfToken,
                "returnHash", returnHash
        ));
    }

    private void setSessionCookie(Context ctx, String sessionId) {
        String sameSite = sessionConfig.getSameSite();
        long maxAge = Duration.ofSeconds(sessionConfig.getAbsoluteTimeoutSeconds()).toSeconds();
        StringBuilder cookie = new StringBuilder()
                .append(sessionConfig.getCookieName()).append('=').append(sessionId)
                .append("; Path=/; HttpOnly; SameSite=").append(sameSite)
                .append("; Max-Age=").append(maxAge);
        if (sessionConfig.isSecureCookie()) {
            cookie.append("; Secure");
        }
        ctx.header("Set-Cookie", cookie.toString());
    }

    private void expireCookie(Context ctx) {
        StringBuilder cookie = new StringBuilder()
                .append(sessionConfig.getCookieName()).append("=; Path=/; HttpOnly; SameSite=")
                .append(sessionConfig.getSameSite())
                .append("; Max-Age=0");
        if (sessionConfig.isSecureCookie()) {
            cookie.append("; Secure");
        }
        ctx.header("Set-Cookie", cookie.toString());
    }

    private static Map<String, Object> principalPayload(Principal principal) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", principal.userId());
        payload.put("displayName", principal.displayName());
        payload.put("authMethod", principal.authMethod());
        payload.put("roles", principal.roles());
        return payload;
    }

    private static String sanitizeReturnHash(String value) {
        if (value == null || value.isBlank()) {
            return "#/timeline";
        }
        return value.startsWith("#/") ? value : "#/timeline";
    }

    public record BasicSessionRequest(String returnHash) {
    }
}
