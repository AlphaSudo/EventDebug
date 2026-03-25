package io.eventlens.mysql;

import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
import io.eventlens.core.EventLensConfig.PoolConfig;

public record MySqlConfig(
        String jdbcUrl,
        String username,
        String password,
        String tableName,
        ColumnMappingConfig columnOverrides,
        PoolConfig pool,
        int queryTimeoutSeconds) {
}
