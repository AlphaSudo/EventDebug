package io.eventlens.core.metadata;

import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.EventLensConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataDatabaseTest {

    @TempDir
    Path tempDir;

    @Test
    void openInitializesSqliteMetadataSchemaAndPragmas() throws Exception {
        EventLensConfig.MetadataConfig config = new EventLensConfig.MetadataConfig();
        config.setEnabled(true);
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("metadata.db"));

        try (MetadataDatabase database = MetadataDatabase.open(config);
             Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {

            assertThat(database.isEnabled()).isTrue();
            assertThat(tableExists(statement, "metadata_schema_history")).isTrue();
            assertThat(tableExists(statement, "sessions")).isTrue();
            assertThat(tableExists(statement, "api_keys")).isTrue();
            assertThat(tableExists(statement, "audit_log")).isTrue();
            assertThat(singleValue(statement, "PRAGMA journal_mode")).isEqualToIgnoringCase("wal");
        }
    }

    @Test
    void repositoriesPersistAndReloadMetadataRecords() {
        EventLensConfig.MetadataConfig config = new EventLensConfig.MetadataConfig();
        config.setEnabled(true);
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("metadata-repos.db"));

        Instant now = Instant.parse("2026-03-26T19:00:00Z");
        try (MetadataDatabase database = MetadataDatabase.open(config)) {
            var repositories = database.repositories();

            repositories.sessions().upsert(new SessionRecord(
                    "sess-1",
                    "alice",
                    "Alice",
                    "oidc",
                    List.of("admin"),
                    Map.of("source", "entra"),
                    now,
                    now,
                    now.plusSeconds(300),
                    now.plusSeconds(3600)));

            repositories.apiKeys().insert(new ApiKeyRecord(
                    "key-1",
                    "el_live_demo",
                    "argon2$hash",
                    "demo key",
                    "alice",
                    List.of("EXPORT_READ"),
                    now,
                    null,
                    null,
                    null));

            long auditId = repositories.auditLogs().append(AuditEvent.builder()
                    .action(AuditEvent.ACTION_EXPORT)
                    .resourceType(AuditEvent.RT_EXPORT)
                    .resourceId("export-1")
                    .userId("alice")
                    .authMethod("oidc")
                    .clientIp("127.0.0.1")
                    .requestId("req-1")
                    .userAgent("JUnit")
                    .details(Map.of("format", "json"))
                    .build(), now);

            assertThat(repositories.sessions().findById("sess-1")).isPresent();
            assertThat(repositories.apiKeys().findByPrefix("el_live_demo")).isPresent();
            assertThat(repositories.auditLogs().findRecent(10))
                    .extracting(AuditLogRecord::auditId)
                    .contains(auditId);
        }
    }

    private static boolean tableExists(Statement statement, String tableName) throws Exception {
        try (ResultSet rs = statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '" + tableName + "'")) {
            return rs.next();
        }
    }

    private static String singleValue(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
}
