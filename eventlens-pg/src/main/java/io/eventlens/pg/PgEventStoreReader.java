package io.eventlens.pg;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
import io.eventlens.core.exception.QueryTimeoutException;
import io.eventlens.core.exception.EventStoreException;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.pg.PgSchemaDetector.DetectedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/**
 * PostgreSQL-backed implementation of {@link EventStoreReader}.
 *
 * <p>
 * Uses HikariCP in <b>read-only</b> mode — it will never write to your event
 * store. Schema is auto-detected from database metadata unless overridden in
 * config. Column name mappings can be explicitly set via
 * {@code datasource.columns} in eventlens.yaml.
 */
public class PgEventStoreReader implements EventStoreReader, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgEventStoreReader.class);

    private final HikariDataSource dataSource;
    private final DetectedSchema schema;
    private final int queryTimeoutSeconds;

    public PgEventStoreReader(PgConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        var pool = config.pool();
        if (pool != null) {
            hc.setMaximumPoolSize(pool.getMaximumPoolSize());
            hc.setMinimumIdle(pool.getMinimumIdle());
            hc.setConnectionTimeout(pool.getConnectionTimeoutMs());
            hc.setIdleTimeout(pool.getIdleTimeoutMs());
            hc.setMaxLifetime(pool.getMaxLifetimeMs());
            hc.setLeakDetectionThreshold(pool.getLeakDetectionThresholdMs());
        }
        hc.setReadOnly(true); // CRITICAL: read-only
        if (pool == null) {
            hc.setConnectionTimeout(5_000);
        }
        hc.setPoolName("eventlens-pg");
        this.dataSource = new HikariDataSource(hc);
        this.queryTimeoutSeconds = Math.max(1, config.queryTimeoutSeconds());
        log.info("Connected to PostgreSQL: {}", config.jdbcUrl());

        var detector = new PgSchemaDetector();
        var overrides = config.columnOverrides() != null ? config.columnOverrides() : new ColumnMappingConfig();

        if (config.tableName() != null && !config.tableName().isBlank()) {
            // Fix 2: use detectForTable instead of the hardcoded buildManualSchema()
            // so we still read real DB metadata even when the table is manually specified.
            this.schema = detector.detectForTable(config.tableName(), dataSource, overrides);
        } else {
            this.schema = detector.detect(dataSource, overrides);
        }
    }

    @Override
    public List<StoredEvent> getEvents(String aggregateId) {
        return getEvents(aggregateId, Integer.MAX_VALUE, 0);
    }

    @Override
    public List<StoredEvent> getEvents(String aggregateId, int limit, int offset) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String seqCol = quoteIdentifier(schema.sequenceColumn());
        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ? ORDER BY %s ASC LIMIT ? OFFSET ?",
                table, aggCol, seqCol);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to read events for aggregate: " + aggregateId, e);
        }
    }

    @Override
    public List<StoredEvent> getEventsAfterSequence(String aggregateId, long afterSequence, int limit) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String seqCol = quoteIdentifier(schema.sequenceColumn());
        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ? AND %s > ? ORDER BY %s ASC LIMIT ?",
                table, aggCol, seqCol, seqCol);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setLong(2, afterSequence);
            ps.setInt(3, limit);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to read events after sequence " + afterSequence + " for aggregate: " + aggregateId, e);
        }
    }

    @Override
    public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String seqCol = quoteIdentifier(schema.sequenceColumn());
        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ? AND %s <= ? ORDER BY %s ASC",
                table, aggCol, seqCol, seqCol);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setLong(2, maxSequence);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to read events up to sequence " + maxSequence, e);
        }
    }

    @Override
    public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        final String sql;
        if (schema.aggregateTypeColumn() != null) {
            String typeCol = quoteIdentifier(schema.aggregateTypeColumn());
            sql = String.format(
                    "SELECT DISTINCT %s FROM %s WHERE %s = ? ORDER BY %s LIMIT ? OFFSET ?",
                    aggCol, table, typeCol, aggCol);
        } else {
            log.debug("No aggregate type column detected; returning all aggregate IDs (type filter ignored)");
            sql = String.format(
                    "SELECT DISTINCT %s FROM %s ORDER BY %s LIMIT ? OFFSET ?",
                    aggCol, table, aggCol);
        }

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            if (schema.aggregateTypeColumn() != null) {
                ps.setString(1, aggregateType);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
            } else {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
            }
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to find aggregate IDs for type: " + aggregateType, e);
        }
    }

    @Override
    public List<StoredEvent> getRecentEvents(int limit) {
        String orderCol = schema.globalPositionColumn() != null
                ? schema.globalPositionColumn()
                : schema.timestampColumn() != null
                        ? schema.timestampColumn()
                        : schema.eventIdColumn();
        String table = quoteIdentifier(schema.tableName());
        String orderColQ = quoteIdentifier(orderCol);
        String sql = String.format(
                "SELECT * FROM %s ORDER BY %s DESC LIMIT ?",
                table, orderColQ);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setInt(1, limit);
            List<StoredEvent> results = mapResults(ps.executeQuery());
            Collections.reverse(results); // oldest first for display
            return results;
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to read recent events", e);
        }
    }

    @Override
    public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
        String posColumn = schema.globalPositionColumn() != null
                ? schema.globalPositionColumn()
                : schema.eventIdColumn();
        String table = quoteIdentifier(schema.tableName());
        String posColQ = quoteIdentifier(posColumn);
        String sql = String.format(
                "SELECT * FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT ?",
                table, posColQ, posColQ);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, globalPosition);
            ps.setInt(2, limit);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to poll events after position " + globalPosition, e);
        }
    }

    @Override
    public long countEvents(String aggregateId) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String sql = String.format(
                "SELECT COUNT(*) FROM %s WHERE %s = ?",
                table, aggCol);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to count events for: " + aggregateId, e);
        }
    }

    @Override
    public List<String> getAggregateTypes() {
        if (schema.aggregateTypeColumn() == null)
            return List.of();
        String table = quoteIdentifier(schema.tableName());
        String typeCol = quoteIdentifier(schema.aggregateTypeColumn());
        String sql = String.format(
                "SELECT DISTINCT %s FROM %s ORDER BY %s",
                typeCol, table, typeCol);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to get aggregate types", e);
        }
    }

    @Override
    public List<String> searchAggregates(String query, int limit) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String sql = String.format(
                "SELECT DISTINCT %s FROM %s WHERE %s ILIKE ? ORDER BY %s LIMIT ?",
                aggCol, table, aggCol, aggCol);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, "%" + query + "%");
            ps.setInt(2, limit);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            if (isQueryCanceled(e)) {
                throw new QueryTimeoutException(
                        queryTimeoutSeconds,
                        "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                                .formatted(queryTimeoutSeconds),
                        e);
            }
            throw new EventStoreException("Failed to search aggregates", e);
        }
    }

    private static boolean isQueryCanceled(SQLException e) {
        return "57014".equals(e.getSQLState());
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("PostgreSQL connection pool closed");
        }
    }

    /**
     * Quote a PostgreSQL identifier to prevent SQL injection and handle reserved words.
     * Escapes double quotes inside the identifier by doubling them.
     */
    private static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty())
            throw new IllegalArgumentException("Identifier cannot be null or empty");
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private List<StoredEvent> mapResults(ResultSet rs) throws SQLException {
        List<StoredEvent> events = new ArrayList<>();
        while (rs.next()) {
            events.add(new StoredEvent(
                    // Fix 4 + Fix 6: use Objects.toString(getObject()) instead of
                    // UUID.fromString(getString()). Handles UUID, BIGSERIAL, ULID, String equally.
                    Objects.toString(rs.getObject(schema.eventIdColumn()), ""),
                    Objects.toString(rs.getObject(schema.aggregateIdColumn()), ""),
                    schema.aggregateTypeColumn() != null
                            ? Objects.toString(rs.getObject(schema.aggregateTypeColumn()), "unknown")
                            : "unknown",
                    rs.getLong(schema.sequenceColumn()),
                    rs.getString(schema.eventTypeColumn()),
                    rs.getString(schema.payloadColumn()),
                    // Fix 8: gracefully handle missing or null metadata column
                    safeGetString(rs, schema.metadataColumn(), "{}"),
                    // Fix 8: gracefully handle null timestamp (toInstant on null NPE)
                    safeGetInstant(rs, schema.timestampColumn()),
                    schema.globalPositionColumn() != null
                            ? rs.getLong(schema.globalPositionColumn())
                            : safeGetLong(rs, schema.eventIdColumn())));
        }
        return events;
    }

    /**
     * Fix 8: safely read a string column — returns fallback if column is null,
     * column name is null (optional column not detected), or value is SQL NULL.
     */
    private String safeGetString(ResultSet rs, String colName, String fallback) {
        if (colName == null)
            return fallback;
        try {
            String val = rs.getString(colName);
            return val != null ? val : fallback;
        } catch (SQLException e) {
            log.debug("Could not read optional column '{}': {}", colName, e.getMessage());
            return fallback;
        }
    }

    /**
     * Safely read a long column — returns 0 if the column value cannot be
     * parsed as a long (e.g. UUID primary keys). Used as fallback global
     * position when the event_id is BIGSERIAL.
     */
    private long safeGetLong(ResultSet rs, String colName) {
        try {
            return rs.getLong(colName);
        } catch (SQLException e) {
            try {
                return Long.parseLong(Objects.toString(rs.getObject(colName), "0"));
            } catch (Exception ignored) {
                return 0;
            }
        }
    }

    /**
     * Safely read a timestamp column — returns Instant.EPOCH if the column
     * name is null (not detected), the value is SQL NULL, or the read fails.
     */
    private Instant safeGetInstant(ResultSet rs, String colName) {
        if (colName == null) return Instant.EPOCH;
        try {
            Timestamp ts = rs.getTimestamp(colName);
            return ts != null ? ts.toInstant() : Instant.EPOCH;
        } catch (SQLException e) {
            log.debug("Could not read timestamp column '{}': {}", colName, e.getMessage());
            return Instant.EPOCH;
        }
    }

    private List<String> extractFirstColumn(ResultSet rs) throws SQLException {
        List<String> result = new ArrayList<>();
        while (rs.next())
            result.add(Objects.toString(rs.getObject(1), ""));
        return result;
    }
}
