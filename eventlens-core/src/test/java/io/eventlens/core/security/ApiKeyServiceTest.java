package io.eventlens.core.security;

import io.eventlens.core.EventLensConfig;
import io.eventlens.core.metadata.MetadataDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void issuePersistsHashedKeyAndAuthenticateMarksUsage() {
        try (MetadataDatabase database = MetadataDatabase.open(metadataConfig("api-keys.db"))) {
            var service = new ApiKeyService(
                    database.repositories().apiKeys(),
                    apiKeyConfig());

            ApiKeyService.IssuedApiKey issued = service.issue(
                    "svc-orders",
                    List.of("api-reader"),
                    "Orders automation",
                    null);

            assertThat(issued.apiKey()).startsWith(issued.keyPrefix() + ".");
            assertThat(database.repositories().apiKeys().findByPrefix(issued.keyPrefix()))
                    .isPresent()
                    .get()
                    .satisfies(record -> {
                        assertThat(record.keyHash()).doesNotContain(issued.apiKey());
                        assertThat(record.principalUserId()).isEqualTo("svc-orders");
                        assertThat(record.scopes()).containsExactly("api-reader");
                    });

            assertThat(service.authenticate(issued.apiKey()))
                    .isPresent()
                    .get()
                    .satisfies(record -> {
                        assertThat(record.apiKeyId()).isEqualTo(issued.apiKeyId());
                        assertThat(record.lastUsedAt()).isNotNull();
                    });
        }
    }

    @Test
    void revokedAndExpiredKeysCannotAuthenticate() {
        try (MetadataDatabase database = MetadataDatabase.open(metadataConfig("api-keys-revoked.db"))) {
            var service = new ApiKeyService(
                    database.repositories().apiKeys(),
                    apiKeyConfig());

            ApiKeyService.IssuedApiKey expired = service.issue(
                    "svc-expired",
                    List.of("api-reader"),
                    "Expired key",
                    Instant.now().minusSeconds(60));
            assertThat(service.authenticate(expired.apiKey())).isEmpty();

            ApiKeyService.IssuedApiKey active = service.issue(
                    "svc-active",
                    List.of("api-reader"),
                    "Active key",
                    Instant.now().plusSeconds(3600));
            service.revoke(active.apiKeyId(), Instant.now());

            assertThat(service.authenticate(active.apiKey())).isEmpty();
        }
    }

    private EventLensConfig.MetadataConfig metadataConfig(String fileName) {
        EventLensConfig.MetadataConfig config = new EventLensConfig.MetadataConfig();
        config.setEnabled(true);
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve(fileName));
        return config;
    }

    private EventLensConfig.ApiKeysConfig apiKeyConfig() {
        EventLensConfig.ApiKeysConfig config = new EventLensConfig.ApiKeysConfig();
        config.setEnabled(true);
        config.setKeyPrefix("el_test");
        config.setHeaderName("X-API-Key");
        return config;
    }
}
