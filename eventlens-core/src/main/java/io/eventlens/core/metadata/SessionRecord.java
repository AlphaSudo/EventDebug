package io.eventlens.core.metadata;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SessionRecord(
        String sessionId,
        String principalUserId,
        String displayName,
        String authMethod,
        List<String> roles,
        Map<String, String> attributes,
        Instant createdAt,
        Instant lastSeenAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt) {
}
