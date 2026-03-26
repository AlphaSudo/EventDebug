package io.eventlens.core.metadata;

import java.time.Instant;
import java.util.List;

public record ApiKeyRecord(
        String apiKeyId,
        String keyPrefix,
        String keyHash,
        String description,
        String principalUserId,
        List<String> scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt) {
}
