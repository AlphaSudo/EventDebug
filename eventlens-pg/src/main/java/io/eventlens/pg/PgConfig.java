package io.eventlens.pg;

import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
import io.eventlens.core.EventLensConfig.PoolConfig;

/**
 * PostgreSQL connection configuration.
 *
 * @param jdbcUrl         JDBC URL, e.g.
 *                        "jdbc:postgresql://localhost:5432/myapp"
 * @param username        database username
 * @param password        database password
 * @param tableName       explicit table name; null = auto-detect via
 *                        {@link PgSchemaDetector}
 * @param columnOverrides optional column name overrides; null = use
 *                        auto-detection for all columns
 */
public record PgConfig(
                String jdbcUrl,
                String username,
                String password,
                String tableName,
                ColumnMappingConfig columnOverrides,
                PoolConfig pool,
                int queryTimeoutSeconds) {

        /** Convenience constructor without column overrides (backwards compat). */
        public PgConfig(String jdbcUrl, String username, String password, String tableName) {
                this(jdbcUrl, username, password, tableName, new ColumnMappingConfig(), new PoolConfig(), 30);
        }
}
