package io.eventlens.core.metadata;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/**
 * Lightweight SQL migration runner for the embedded metadata database.
 *
 * <p>We keep this intentionally small instead of introducing a full migration
 * framework in the first metadata epic. If metadata portability expands later,
 * this seam can be swapped without changing repository code.</p>
 */
final class MetadataMigrationRunner {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration("1", "create_metadata_schema", "/db/metadata/V1__create_metadata_schema.sql")
    );

    private MetadataMigrationRunner() {
    }

    static void migrate(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            ensureSchemaHistoryTable(connection);

            for (Migration migration : MIGRATIONS) {
                if (isApplied(connection, migration.version())) {
                    continue;
                }
                applyMigration(connection, migration);
            }

            connection.commit();
        }
    }

    private static void ensureSchemaHistoryTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS metadata_schema_history (
                        version TEXT PRIMARY KEY,
                        description TEXT NOT NULL,
                        installed_at TEXT NOT NULL
                    )
                    """);
        }
    }

    private static boolean isApplied(Connection connection, String version) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM metadata_schema_history WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void applyMigration(Connection connection, Migration migration) throws Exception {
        String sql = loadSql(migration.resourcePath());
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO metadata_schema_history(version, description, installed_at) VALUES (?, ?, ?)")) {
            ps.setString(1, migration.version());
            ps.setString(2, migration.description());
            ps.setString(3, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    private static String loadSql(String resourcePath) throws Exception {
        try (InputStream input = MetadataMigrationRunner.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Missing migration resource: " + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Migration(String version, String description, String resourcePath) {
    }
}
