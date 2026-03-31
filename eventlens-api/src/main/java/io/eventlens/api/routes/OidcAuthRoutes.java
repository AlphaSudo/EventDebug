package io.eventlens.api.routes;

import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.security.CsrfTokens;
import io.eventlens.api.security.oidc.OidcIdTokenValidator;
import io.eventlens.api.security.oidc.OidcLoginStateService;
import io.eventlens.api.security.oidc.OidcProviderClient;
import io.eventlens.api.security.oidc.PendingOidcLogin;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.security.Principal;
import io.eventlens.core.security.SessionService;
import io.javalin.http.Context;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class OidcAuthRoutes {

    private static final String STATE_COOKIE = "eventlens_oidc_state";

    private final SessionService sessionService;
    private final EventLensConfig.SessionConfig sessionConfig;
    private final EventLensConfig.OidcConfig oidcConfig;
    private final OidcProviderClient providerClient;
    private final OidcIdTokenValidator tokenValidator;
    private final OidcLoginStateService loginStateService;
    private final AuditLogger auditLogger;

    public OidcAuthRoutes(
            SessionService sessionService,
            EventLensConfig.SessionConfig sessionConfig,
            EventLensConfig.OidcConfig oidcConfig,
            OidcProviderClient providerClient,
            OidcIdTokenValidator tokenValidator,
            OidcLoginStateService loginStateService,
            AuditLogger auditLogger) {
        this.sessionService = sessionService;
        this.sessionConfig = sessionConfig;
        this.oidcConfig = oidcConfig;
        this.providerClient = providerClient;
        this.tokenValidator = tokenValidator;
        this.loginStateService = loginStateService;
        this.auditLogger = auditLogger;
    }

    public void start(Context ctx) {
        String returnHash = sanitizeReturnHash(ctx.queryParam("returnHash"));
        PendingOidcLogin login = loginStateService.create(returnHash);
        var metadata = providerClient.discover(oidcConfig.getIssuer());

        setStateCookie(ctx, login.stateId());
        String redirectUri = externalBaseUrl(ctx) + oidcConfig.getRedirectPath();
        String authorizationUrl = metadata.authorizationEndpoint()
                + "?response_type=code"
                + "&client_id=" + encode(oidcConfig.getClientId())
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(String.join(" ", oidcConfig.getScopes()))
                + "&state=" + encode(login.stateId())
                + "&nonce=" + encode(login.nonce())
                + "&code_challenge=" + encode(OidcLoginStateService.codeChallenge(login.codeVerifier()))
                + "&code_challenge_method=S256";
        ctx.redirect(authorizationUrl);
    }

    public void callback(Context ctx) {
        String state = ctx.queryParam("state");
        String cookieState = ctx.cookie(STATE_COOKIE);
        expireStateCookie(ctx);

        if (!safeEquals(state, cookieState)) {
            EventLensMetrics.recordAuthAttempt("oidc", "failure");
            failCallback(ctx, "state_mismatch");
            return;
        }

        String providerError = ctx.queryParam("error");
        if (providerError != null && !providerError.isBlank()) {
            EventLensMetrics.recordAuthAttempt("oidc", "failure");
            failCallback(ctx, providerError);
            return;
        }

        Optional<PendingOidcLogin> pending = loginStateService.consume(state);
        if (pending.isEmpty()) {
            EventLensMetrics.recordAuthAttempt("oidc", "failure");
            failCallback(ctx, "missing_state");
            return;
        }

        String code = ctx.queryParam("code");
        if (code == null || code.isBlank()) {
            EventLensMetrics.recordAuthAttempt("oidc", "failure");
            failCallback(ctx, "missing_code");
            return;
        }

        PendingOidcLogin login = pending.get();
        try {
            String redirectUri = externalBaseUrl(ctx) + oidcConfig.getRedirectPath();
            var metadata = providerClient.discover(oidcConfig.getIssuer());
            var tokenResponse = providerClient.exchangeCode(metadata, oidcConfig, redirectUri, code, login.codeVerifier());
            var identity = tokenValidator.validate(tokenResponse.idToken(), metadata, oidcConfig, login.nonce());

            Principal principal = new Principal(
                    identity.subject(),
                    identity.displayName(),
                    "oidc",
                    java.util.Set.of(),
                    true
            );

            Map<String, String> attributes = new java.util.LinkedHashMap<>();
            attributes.put("issuer", metadata.issuer());
            attributes.put("csrfToken", CsrfTokens.generate());
            if (identity.email() != null && !identity.email().isBlank()) {
                attributes.put("email", identity.email());
            }

            var session = sessionService.create(principal, attributes);
            EventLensMetrics.recordAuthAttempt("oidc", "success");
            EventLensMetrics.recordSessionLifecycle("created");
            setSessionCookie(ctx, session.sessionId());

            auditLogger.log(SecurityContext.audit(ctx)
                    .action(AuditEvent.ACTION_LOGIN)
                    .resourceType(AuditEvent.RT_AUTH)
                    .userId(principal.userId())
                    .authMethod("oidc")
                    .details(Map.of("path", ctx.path(), "provider", metadata.issuer()))
                    .build());

            ctx.redirect("/" + login.returnHash());
        } catch (RuntimeException e) {
            EventLensMetrics.recordAuthAttempt("oidc", "failure");
            failCallback(ctx, "token_validation_failed");
        }
    }

    private void failCallback(Context ctx, String reason) {
        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_LOGIN_FAILED)
                .resourceType(AuditEvent.RT_AUTH)
                .authMethod("oidc")
                .details(Map.of("reason", reason, "path", ctx.path()))
                .build());
        ctx.redirect("/?authError=" + encode(reason) + "#/timeline");
    }

    private void setSessionCookie(Context ctx, String sessionId) {
        StringBuilder cookie = new StringBuilder()
                .append(sessionConfig.getCookieName()).append('=').append(sessionId)
                .append("; Path=/; HttpOnly; SameSite=").append(sessionConfig.getSameSite())
                .append("; Max-Age=").append(sessionConfig.getAbsoluteTimeoutSeconds());
        if (sessionConfig.isSecureCookie()) {
            cookie.append("; Secure");
        }
        ctx.res().addHeader("Set-Cookie", cookie.toString());
    }

    private void setStateCookie(Context ctx, String stateId) {
        StringBuilder cookie = new StringBuilder()
                .append(STATE_COOKIE).append('=').append(stateId)
                .append("; Path=/; HttpOnly; SameSite=Lax; Max-Age=300");
        if (sessionConfig.isSecureCookie()) {
            cookie.append("; Secure");
        }
        ctx.res().addHeader("Set-Cookie", cookie.toString());
    }

    private void expireStateCookie(Context ctx) {
        StringBuilder cookie = new StringBuilder()
                .append(STATE_COOKIE)
                .append("=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        if (sessionConfig.isSecureCookie()) {
            cookie.append("; Secure");
        }
        ctx.res().addHeader("Set-Cookie", cookie.toString());
    }

    private static String externalBaseUrl(Context ctx) {
        String scheme = headerOrDefault(ctx, "X-Forwarded-Proto", ctx.scheme());
        String host = headerOrDefault(ctx, "X-Forwarded-Host", ctx.host());
        return scheme + "://" + host;
    }

    private static String headerOrDefault(Context ctx, String header, String fallback) {
        String value = ctx.header(header);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String sanitizeReturnHash(String value) {
        if (value == null || value.isBlank()) {
            return "#/timeline";
        }
        return value.startsWith("#/") ? value : "#/timeline";
    }

    private static boolean safeEquals(String left, String right) {
        return left != null && left.equals(right);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
