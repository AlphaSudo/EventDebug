package io.eventlens.api.security.oidc;

import io.eventlens.core.metadata.SessionRecord;
import io.eventlens.core.metadata.SessionRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class OidcLoginStateService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TTL_SECONDS = 300;
    private static final String STATE_AUTH_METHOD = "oidc_state";

    private final SessionRepository sessionRepository;
    private final Clock clock;

    public OidcLoginStateService(SessionRepository sessionRepository) {
        this(sessionRepository, Clock.systemUTC());
    }

    OidcLoginStateService(SessionRepository sessionRepository, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    public PendingOidcLogin create(String returnHash) {
        Instant now = clock.instant();
        String stateId = newOpaqueToken();
        String nonce = newOpaqueToken();
        String codeVerifier = newOpaqueToken();
        SessionRecord record = new SessionRecord(
                stateId,
                "oidc-state",
                "oidc-state",
                STATE_AUTH_METHOD,
                List.of(),
                Map.of(
                        "nonce", nonce,
                        "codeVerifier", codeVerifier,
                        "returnHash", returnHash
                ),
                now,
                now,
                now.plusSeconds(TTL_SECONDS),
                now.plusSeconds(TTL_SECONDS)
        );
        sessionRepository.upsert(record);
        return new PendingOidcLogin(stateId, nonce, codeVerifier, returnHash);
    }

    public Optional<PendingOidcLogin> consume(String stateId) {
        if (stateId == null || stateId.isBlank()) {
            return Optional.empty();
        }
        Optional<SessionRecord> record = sessionRepository.findById(stateId);
        sessionRepository.deleteById(stateId);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        SessionRecord session = record.get();
        if (!STATE_AUTH_METHOD.equals(session.authMethod()) || isExpired(session, clock.instant())) {
            return Optional.empty();
        }
        String nonce = session.attributes().get("nonce");
        String codeVerifier = session.attributes().get("codeVerifier");
        String returnHash = session.attributes().getOrDefault("returnHash", "#/timeline");
        if (isBlank(nonce) || isBlank(codeVerifier)) {
            return Optional.empty();
        }
        return Optional.of(new PendingOidcLogin(session.sessionId(), nonce, codeVerifier, returnHash));
    }

    public static String codeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive PKCE code challenge", e);
        }
    }

    private static boolean isExpired(SessionRecord session, Instant now) {
        return !session.idleExpiresAt().isAfter(now) || !session.absoluteExpiresAt().isAfter(now);
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
