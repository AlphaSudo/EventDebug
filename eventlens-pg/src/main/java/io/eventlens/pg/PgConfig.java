package io.eventlens.pg;

/**
 * PostgreSQL connection configuration.
 *
 * @param jdbcUrl   JDBC URL, e.g. "jdbc:postgresql://localhost:5432/myapp"
 * @param username  database username
 * @param password  database password
 * @param tableName explicit table name; null = auto-detect via
 *                  {@link PgSchemaDetector}
 */
public record PgConfig(
        String jdbcUrl,
        String username,
        String password,
        String tableName) {
}
