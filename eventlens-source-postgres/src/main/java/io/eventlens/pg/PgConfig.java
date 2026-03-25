package io.eventlens.pg;

import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
import io.eventlens.core.EventLensConfig.PoolConfig;

public record PgConfig(
        String jdbcUrl,
        String username,
        String password,
        String tableName,
        ColumnMappingConfig columnOverrides,
        PoolConfig pool,
        int queryTimeoutSeconds) {

    public PgConfig(String jdbcUrl, String username, String password, String tableName) {
        this(jdbcUrl, username, password, tableName, new ColumnMappingConfig(), new PoolConfig(), 30);
    }
}
