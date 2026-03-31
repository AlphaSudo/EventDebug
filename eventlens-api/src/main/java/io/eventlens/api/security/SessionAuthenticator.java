package io.eventlens.api.security;

import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.core.metadata.SessionRecord;
import io.eventlens.core.security.Principal;
import io.eventlens.core.security.SessionService;
import io.javalin.http.Context;

import java.util.Optional;
import java.util.Set;

/**
 * Cookie-backed session authenticator for browser/UI requests.
 */
public final class SessionAuthenticator implements RequestAuthenticator {

    private final SessionService sessionService;
    private final String cookieName;

    public SessionAuthenticator(SessionService sessionService, String cookieName) {
        this.sessionService = sessionService;
        this.cookieName = cookieName;
    }

    @Override
    public AuthenticationResult authenticate(Context ctx) {
        String sessionId = ctx.cookie(cookieName);
        if (sessionId == null || sessionId.isBlank()) {
            return AuthenticationResult.failure(null, "missing_session", null);
        }

        Optional<SessionRecord> record = sessionService.touch(sessionId);
        if (record.isEmpty()) {
            EventLensMetrics.recordSessionLifecycle("rejected");
            return AuthenticationResult.failure(null, "invalid_session", null);
        }

        SessionRecord session = record.get();
        SecurityContext.setSession(ctx, session);
        Principal principal = new Principal(
                session.principalUserId(),
                session.displayName(),
                session.authMethod(),
                Set.copyOf(session.roles()),
                true);
        return AuthenticationResult.success(principal);
    }
}
