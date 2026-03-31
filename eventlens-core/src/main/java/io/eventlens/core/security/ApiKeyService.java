package io.eventlens.core.security;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.metadata.ApiKeyRecord;
import io.eventlens.core.metadata.ApiKeyRepository;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ApiKeyService {

    private static final int ARGON2_ITERATIONS = 2;
    private static final int ARGON2_MEMORY_KB = 19_456;
    private static final int ARGON2_PARALLELISM = 1;

    private final ApiKeyRepository repository;
    private final EventLensConfig.ApiKeysConfig config;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(ApiKeyRepository repository, EventLensConfig.ApiKeysConfig config) {
        this.repository = repository;
        this.config = config == null ? new EventLensConfig.ApiKeysConfig() : config;
    }

    public IssuedApiKey issue(String principalUserId, List<String> roleScopes, String description, Instant expiresAt) {
        Instant now = Instant.now();
        String apiKeyId = UUID.randomUUID().toString();
        String keyPrefix = nextKeyPrefix();
        String secret = randomToken(32);
        String rawApiKey = keyPrefix + "." + secret;
        String keyHash = hash(rawApiKey);

        ApiKeyRecord record = new ApiKeyRecord(
                apiKeyId,
                keyPrefix,
                keyHash,
                description,
                principalUserId,
                roleScopes == null ? List.of() : List.copyOf(roleScopes),
                now,
                expiresAt,
                null,
                null);
        repository.insert(record);
        return new IssuedApiKey(
                apiKeyId,
                keyPrefix,
                rawApiKey,
                description,
                principalUserId,
                record.scopes(),
                now,
                expiresAt);
    }

    public Optional<ApiKeyRecord> authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Optional.empty();
        }

        String prefix = extractPrefix(rawApiKey);
        if (prefix == null) {
            return Optional.empty();
        }

        Optional<ApiKeyRecord> loaded = repository.findByPrefix(prefix);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }

        ApiKeyRecord record = loaded.get();
        if (record.revokedAt() != null) {
            return Optional.empty();
        }
        if (record.expiresAt() != null && record.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (!verify(record.keyHash(), rawApiKey)) {
            return Optional.empty();
        }

        Instant lastUsedAt = Instant.now();
        repository.markUsed(record.apiKeyId(), lastUsedAt);
        return Optional.of(new ApiKeyRecord(
                record.apiKeyId(),
                record.keyPrefix(),
                record.keyHash(),
                record.description(),
                record.principalUserId(),
                record.scopes(),
                record.createdAt(),
                record.expiresAt(),
                record.revokedAt(),
                lastUsedAt));
    }

    public List<ApiKeyRecord> list() {
        return repository.list();
    }

    public void revoke(String apiKeyId, Instant revokedAt) {
        repository.revoke(apiKeyId, revokedAt);
    }

    private String nextKeyPrefix() {
        return config.getKeyPrefix() + "_" + randomToken(9);
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        secureRandom.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String extractPrefix(String rawApiKey) {
        int separator = rawApiKey.indexOf('.');
        if (separator <= 0) {
            return null;
        }
        return rawApiKey.substring(0, separator);
    }

    private static String hash(String rawApiKey) {
        Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
        char[] chars = rawApiKey.toCharArray();
        try {
            return argon2.hash(ARGON2_ITERATIONS, ARGON2_MEMORY_KB, ARGON2_PARALLELISM, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    private static boolean verify(String encodedHash, String rawApiKey) {
        Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);
        char[] chars = rawApiKey.toCharArray();
        try {
            return argon2.verify(encodedHash, chars);
        } finally {
            argon2.wipeArray(chars);
        }
    }

    public record IssuedApiKey(
            String apiKeyId,
            String keyPrefix,
            String apiKey,
            String description,
            String principalUserId,
            List<String> scopes,
            Instant createdAt,
            Instant expiresAt) {
    }
}
