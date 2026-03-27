package io.eventlens.core.security;

import io.eventlens.core.EventLensConfig;
import io.eventlens.core.metadata.SessionRecord;
import io.eventlens.core.metadata.SessionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side opaque session lifecycle helper for v5 auth flows.
 */
public final class SessionService {

    private final SessionRepository sessionRepository;
    private final EventLensConfig.SessionConfig sessionConfig;
    private final Clock clock;

    public SessionService(SessionRepository sessionRepository, EventLensConfig.SessionConfig sessionConfig) {
        this(sessionRepository, sessionConfig, Clock.systemUTC());
    }

    SessionService(SessionRepository sessionRepository, EventLensConfig.SessionConfig sessionConfig, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.sessionConfig = sessionConfig;
        this.clock = clock;
    }

    public SessionRecord create(Principal principal, Map<String, String> attributes) {
        Instant now = clock.instant();
        SessionRecord record = new SessionRecord(
                newSessionId(),
                principal.userId(),
                principal.displayName(),
                principal.authMethod(),
                List.copyOf(principal.roles()),
                attributes,
                now,
                now,
                now.plusSeconds(sessionConfig.getIdleTimeoutSeconds()),
                now.plusSeconds(sessionConfig.getAbsoluteTimeoutSeconds()));
        sessionRepository.upsert(record);
        return record;
    }

    public Optional<SessionRecord> findActive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Optional<SessionRecord> record = sessionRepository.findById(sessionId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        SessionRecord session = record.get();
        if (isExpired(session, now)) {
            sessionRepository.deleteById(sessionId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public Optional<SessionRecord> touch(String sessionId) {
        Optional<SessionRecord> record = findActive(sessionId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Instant idleExpiry = now.plusSeconds(sessionConfig.getIdleTimeoutSeconds());
        sessionRepository.touch(sessionId, now, idleExpiry);
        SessionRecord current = record.get();
        return Optional.of(new SessionRecord(
                current.sessionId(),
                current.principalUserId(),
                current.displayName(),
                current.authMethod(),
                current.roles(),
                current.attributes(),
                current.createdAt(),
                now,
                idleExpiry,
                current.absoluteExpiresAt()));
    }

    public void invalidate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        sessionRepository.deleteById(sessionId);
    }

    public int deleteExpiredSessions() {
        return sessionRepository.deleteExpired(clock.instant());
    }

    private static boolean isExpired(SessionRecord session, Instant now) {
        return !session.idleExpiresAt().isAfter(now) || !session.absoluteExpiresAt().isAfter(now);
    }

    private static String newSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
