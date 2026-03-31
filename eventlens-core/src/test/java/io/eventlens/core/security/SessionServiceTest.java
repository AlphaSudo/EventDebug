package io.eventlens.core.security;

import io.eventlens.core.EventLensConfig;
import io.eventlens.core.metadata.MetadataDatabase;
import io.eventlens.core.metadata.SessionRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createAndTouchSessionPersistsServerSideState() {
        EventLensConfig.MetadataConfig metadataConfig = new EventLensConfig.MetadataConfig();
        metadataConfig.setEnabled(true);
        metadataConfig.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("sessions.db"));

        EventLensConfig.SessionConfig sessionConfig = new EventLensConfig.SessionConfig();
        sessionConfig.setIdleTimeoutSeconds(300);
        sessionConfig.setAbsoluteTimeoutSeconds(3600);

        try (MetadataDatabase database = MetadataDatabase.open(metadataConfig)) {
            Instant now = Instant.parse("2026-03-27T00:00:00Z");
            SessionService service = new SessionService(
                    database.repositories().sessions(),
                    sessionConfig,
                    Clock.fixed(now, ZoneOffset.UTC));

            SessionRecord created = service.create(
                    Principal.basic("alice"),
                    Map.of("returnHash", "#/timeline"));

            assertThat(service.findActive(created.sessionId())).isPresent();
            assertThat(service.touch(created.sessionId())).isPresent()
                    .get()
                    .extracting(SessionRecord::idleExpiresAt)
                    .isEqualTo(now.plusSeconds(300));
        }
    }

    @Test
    void expiredSessionIsRejectedAndDeleted() {
        EventLensConfig.MetadataConfig metadataConfig = new EventLensConfig.MetadataConfig();
        metadataConfig.setEnabled(true);
        metadataConfig.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("expired-sessions.db"));

        EventLensConfig.SessionConfig sessionConfig = new EventLensConfig.SessionConfig();
        sessionConfig.setIdleTimeoutSeconds(60);
        sessionConfig.setAbsoluteTimeoutSeconds(120);

        try (MetadataDatabase database = MetadataDatabase.open(metadataConfig)) {
            Instant issuedAt = Instant.parse("2026-03-27T00:00:00Z");
            SessionService creator = new SessionService(
                    database.repositories().sessions(),
                    sessionConfig,
                    Clock.fixed(issuedAt, ZoneOffset.UTC));
            SessionRecord created = creator.create(Principal.basic("alice"), Map.of());

            SessionService expiredReader = new SessionService(
                    database.repositories().sessions(),
                    sessionConfig,
                    Clock.fixed(issuedAt.plusSeconds(61), ZoneOffset.UTC));

            assertThat(expiredReader.findActive(created.sessionId())).isEmpty();
            assertThat(database.repositories().sessions().findById(created.sessionId())).isEmpty();
        }
    }
}
