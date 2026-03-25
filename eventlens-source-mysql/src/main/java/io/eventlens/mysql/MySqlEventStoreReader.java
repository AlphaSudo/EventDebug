package io.eventlens.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
import io.eventlens.core.exception.EventStoreException;
import io.eventlens.core.exception.QueryTimeoutException;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.mysql.MySqlSchemaDetector.DetectedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class MySqlEventStoreReader implements EventStoreReader, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MySqlEventStoreReader.class);

    private final HikariDataSource dataSource;
    private final DetectedSchema schema;
    private final int queryTimeoutSeconds;

    public MySqlEventStoreReader(MySqlConfig config) {
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
        hc.setReadOnly(true);
        hc.setPoolName("eventlens-mysql");
        this.dataSource = new HikariDataSource(hc);
        this.queryTimeoutSeconds = Math.max(1, config.queryTimeoutSeconds());

        var detector = new MySqlSchemaDetector();
        var overrides = config.columnOverrides() != null ? config.columnOverrides() : new ColumnMappingConfig();
        this.schema = config.tableName() != null && !config.tableName().isBlank()
                ? detector.detectForTable(config.tableName(), dataSource, overrides)
                : detector.detect(dataSource, overrides);
    }

    @Override public List<StoredEvent> getEvents(String aggregateId) { return getEvents(aggregateId, Integer.MAX_VALUE, 0); }

    @Override
    public List<StoredEvent> getEvents(String aggregateId, int limit, int offset) {
        String sql = "SELECT * FROM %s WHERE %s = ? ORDER BY %s ASC LIMIT ? OFFSET ?".formatted(q(schema.tableName()), q(schema.aggregateIdColumn()), q(schema.sequenceColumn()));
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) { throw mapException("Failed to read events for aggregate: " + aggregateId, e); }
    }

    @Override
    public List<StoredEvent> getEventsAfterSequence(String aggregateId, long afterSequence, int limit) {
        String seqCol = q(schema.sequenceColumn());
        String sql = "SELECT * FROM %s WHERE %s = ? AND %s > ? ORDER BY %s ASC LIMIT ?".formatted(q(schema.tableName()), q(schema.aggregateIdColumn()), seqCol, seqCol);
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setLong(2, afterSequence);
            ps.setInt(3, limit);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) { throw mapException("Failed to read events after sequence " + afterSequence, e); }
    }

    @Override
    public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
        String seqCol = q(schema.sequenceColumn());
        String sql = "SELECT * FROM %s WHERE %s = ? AND %s <= ? ORDER BY %s ASC".formatted(q(schema.tableName()), q(schema.aggregateIdColumn()), seqCol, seqCol);
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setLong(2, maxSequence);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) { throw mapException("Failed to read events up to sequence " + maxSequence, e); }
    }

    @Override
    public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
        String aggCol = q(schema.aggregateIdColumn());
        final String sql;
        if (schema.aggregateTypeColumn() != null) {
            sql = "SELECT DISTINCT %s FROM %s WHERE %s = ? ORDER BY %s LIMIT ? OFFSET ?".formatted(aggCol, q(schema.tableName()), q(schema.aggregateTypeColumn()), aggCol);
        } else {
            sql = "SELECT DISTINCT %s FROM %s ORDER BY %s LIMIT ? OFFSET ?".formatted(aggCol, q(schema.tableName()), aggCol);
        }
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            if (schema.aggregateTypeColumn() != null) { ps.setString(1, aggregateType); ps.setInt(2, limit); ps.setInt(3, offset); }
            else { ps.setInt(1, limit); ps.setInt(2, offset); }
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) { throw mapException("Failed to find aggregate IDs for type: " + aggregateType, e); }
    }

    @Override
    public List<StoredEvent> getRecentEvents(int limit) {
        String orderCol = schema.globalPositionColumn() != null ? schema.globalPositionColumn() : schema.timestampColumn() != null ? schema.timestampColumn() : schema.eventIdColumn();
        String sql = "SELECT * FROM %s ORDER BY %s DESC LIMIT ?".formatted(q(schema.tableName()), q(orderCol));
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setInt(1, limit);
            List<StoredEvent> results = mapResults(ps.executeQuery());
            Collections.reverse(results);
            return results;
        } catch (SQLException e) { throw mapException("Failed to read recent events", e); }
    }

    @Override
    public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
        String positionColumn = schema.globalPositionColumn() != null ? schema.globalPositionColumn() : schema.eventIdColumn();
        String posCol = q(positionColumn);
        String sql = "SELECT * FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT ?".formatted(q(schema.tableName()), posCol, posCol);
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, globalPosition);
            ps.setInt(2, limit);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) { throw mapException("Failed to poll events after position " + globalPosition, e); }
    }

    @Override
    public long countEvents(String aggregateId) {
        String sql = "SELECT COUNT(*) FROM %s WHERE %s = ?".formatted(q(schema.tableName()), q(schema.aggregateIdColumn()));
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) { throw mapException("Failed to count events for: " + aggregateId, e); }
    }

    @Override
    public List<String> getAggregateTypes() {
        if (schema.aggregateTypeColumn() == null) return List.of();
        String typeCol = q(schema.aggregateTypeColumn());
        String sql = "SELECT DISTINCT %s FROM %s ORDER BY %s".formatted(typeCol, q(schema.tableName()), typeCol);
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) { throw mapException("Failed to get aggregate types", e); }
    }

    @Override
    public List<String> searchAggregates(String query, int limit) {
        String aggCol = q(schema.aggregateIdColumn());
        String sql = "SELECT DISTINCT %s FROM %s WHERE LOWER(%s) LIKE LOWER(?) ORDER BY %s LIMIT ?".formatted(aggCol, q(schema.tableName()), aggCol, aggCol);
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, "%" + query + "%");
            ps.setInt(2, limit);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) { throw mapException("Failed to search aggregates", e); }
    }

    @Override public void close() { if (dataSource != null && !dataSource.isClosed()) dataSource.close(); }

    private RuntimeException mapException(String message, SQLException e) {
        if ("HY000".equals(e.getSQLState()) && e.getMessage() != null && e.getMessage().contains("maximum statement execution time")) {
            return new QueryTimeoutException(queryTimeoutSeconds, "Query exceeded timeout", e);
        }
        return new EventStoreException(message, e);
    }

    private static String q(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }

    private List<StoredEvent> mapResults(ResultSet rs) throws SQLException {
        List<StoredEvent> events = new ArrayList<>();
        while (rs.next()) {
            events.add(new StoredEvent(
                    Objects.toString(rs.getObject(schema.eventIdColumn()), ""),
                    Objects.toString(rs.getObject(schema.aggregateIdColumn()), ""),
                    schema.aggregateTypeColumn() != null ? Objects.toString(rs.getObject(schema.aggregateTypeColumn()), "unknown") : "unknown",
                    rs.getLong(schema.sequenceColumn()),
                    rs.getString(schema.eventTypeColumn()),
                    rs.getString(schema.payloadColumn()),
                    safeGetString(rs, schema.metadataColumn(), "{}"),
                    safeGetInstant(rs, schema.timestampColumn()),
                    schema.globalPositionColumn() != null ? rs.getLong(schema.globalPositionColumn()) : safeGetLong(rs, schema.eventIdColumn())));
        }
        return events;
    }

    private String safeGetString(ResultSet rs, String columnName, String fallback) {
        if (columnName == null) return fallback;
        try { String value = rs.getString(columnName); return value != null ? value : fallback; }
        catch (SQLException e) { log.debug("Could not read optional column '{}': {}", columnName, e.getMessage()); return fallback; }
    }

    private long safeGetLong(ResultSet rs, String columnName) {
        try { return rs.getLong(columnName); }
        catch (SQLException e) { try { return Long.parseLong(Objects.toString(rs.getObject(columnName), "0")); } catch (Exception ignored) { return 0; } }
    }

    private Instant safeGetInstant(ResultSet rs, String columnName) {
        if (columnName == null) return Instant.EPOCH;
        try { Timestamp ts = rs.getTimestamp(columnName); return ts != null ? ts.toInstant() : Instant.EPOCH; }
        catch (SQLException e) { return Instant.EPOCH; }
    }

    private List<String> extractFirstColumn(ResultSet rs) throws SQLException {
        List<String> result = new ArrayList<>();
        while (rs.next()) result.add(Objects.toString(rs.getObject(1), ""));
        return result;
    }
}
