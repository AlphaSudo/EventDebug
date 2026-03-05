package io.eventlens.pg;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.eventlens.core.exception.EventStoreException;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.pg.PgSchemaDetector.DetectedSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL-backed implementation of {@link EventStoreReader}.
 *
 * <p>
 * Uses HikariCP in <b>read-only</b> mode — it will never write to your event
 * store.
 * Schema is auto-detected unless overridden in config.
 */
public class PgEventStoreReader implements EventStoreReader {

    private static final Logger log = LoggerFactory.getLogger(PgEventStoreReader.class);

    private final HikariDataSource dataSource;
    private final DetectedSchema schema;

    public PgEventStoreReader(PgConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.jdbcUrl());
        hc.setUsername(config.username());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(5);
        hc.setReadOnly(true); // CRITICAL: read-only
        hc.setConnectionTimeout(5_000);
        hc.setPoolName("eventlens-pg");
        this.dataSource = new HikariDataSource(hc);
        log.info("Connected to PostgreSQL: {}", config.jdbcUrl());

        if (config.tableName() != null && !config.tableName().isBlank()) {
            this.schema = buildManualSchema(config.tableName());
            log.info("Using manually configured table: '{}'", config.tableName());
        } else {
            this.schema = new PgSchemaDetector().detect(dataSource);
        }
    }

    @Override
    public List<StoredEvent> getEvents(String aggregateId) {
        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ? ORDER BY %s ASC",
                schema.tableName(), schema.aggregateIdColumn(), schema.sequenceColumn());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            throw new EventStoreException("Failed to read events for aggregate: " + aggregateId, e);
        }
    }

    @Override
    public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
        String sql = String.format(
                "SELECT * FROM %s WHERE %s = ? AND %s <= ? ORDER BY %s ASC",
                schema.tableName(), schema.aggregateIdColumn(),
                schema.sequenceColumn(), schema.sequenceColumn());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            ps.setLong(2, maxSequence);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            throw new EventStoreException("Failed to read events up to sequence " + maxSequence, e);
        }
    }

    @Override
    public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
        String sql = String.format(
                "SELECT DISTINCT %s FROM %s WHERE %s = ? ORDER BY %s LIMIT ? OFFSET ?",
                schema.aggregateIdColumn(), schema.tableName(),
                schema.aggregateTypeColumn() != null ? schema.aggregateTypeColumn() : "1=1",
                schema.aggregateIdColumn());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateType);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            throw new EventStoreException("Failed to find aggregate IDs for type: " + aggregateType, e);
        }
    }

    @Override
    public List<StoredEvent> getRecentEvents(int limit) {
        String orderCol = schema.globalPositionColumn() != null
                ? schema.globalPositionColumn()
                : schema.timestampColumn();
        String sql = String.format(
                "SELECT * FROM %s ORDER BY %s DESC LIMIT ?",
                schema.tableName(), orderCol);
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            List<StoredEvent> results = mapResults(ps.executeQuery());
            Collections.reverse(results); // oldest first for display
            return results;
        } catch (SQLException e) {
            throw new EventStoreException("Failed to read recent events", e);
        }
    }

    @Override
    public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
        if (schema.globalPositionColumn() == null) {
            log.warn("globalPositionColumn not detected — live tail polling is degraded");
            return List.of();
        }
        String sql = String.format(
                "SELECT * FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT ?",
                schema.tableName(), schema.globalPositionColumn(), schema.globalPositionColumn());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, globalPosition);
            ps.setInt(2, limit);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            throw new EventStoreException("Failed to poll events after position " + globalPosition, e);
        }
    }

    @Override
    public long countEvents(String aggregateId) {
        String sql = String.format(
                "SELECT COUNT(*) FROM %s WHERE %s = ?",
                schema.tableName(), schema.aggregateIdColumn());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, aggregateId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new EventStoreException("Failed to count events for: " + aggregateId, e);
        }
    }

    @Override
    public List<String> getAggregateTypes() {
        if (schema.aggregateTypeColumn() == null)
            return List.of();
        String sql = String.format(
                "SELECT DISTINCT %s FROM %s ORDER BY %s",
                schema.aggregateTypeColumn(), schema.tableName(), schema.aggregateTypeColumn());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            throw new EventStoreException("Failed to get aggregate types", e);
        }
    }

    @Override
    public List<String> searchAggregates(String query, int limit) {
        String sql = String.format(
                "SELECT DISTINCT %s FROM %s WHERE %s ILIKE ? ORDER BY %s LIMIT ?",
                schema.aggregateIdColumn(), schema.tableName(),
                schema.aggregateIdColumn(), schema.aggregateIdColumn());
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + query + "%");
            ps.setInt(2, limit);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            throw new EventStoreException("Failed to search aggregates", e);
        }
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("PostgreSQL connection pool closed");
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private List<StoredEvent> mapResults(ResultSet rs) throws SQLException {
        List<StoredEvent> events = new ArrayList<>();
        while (rs.next()) {
            events.add(new StoredEvent(
                    UUID.fromString(rs.getString(schema.eventIdColumn())),
                    rs.getString(schema.aggregateIdColumn()),
                    schema.aggregateTypeColumn() != null
                            ? rs.getString(schema.aggregateTypeColumn())
                            : "unknown",
                    rs.getLong(schema.sequenceColumn()),
                    rs.getString(schema.eventTypeColumn()),
                    rs.getString(schema.payloadColumn()),
                    schema.metadataColumn() != null
                            ? rs.getString(schema.metadataColumn())
                            : "{}",
                    rs.getTimestamp(schema.timestampColumn()).toInstant(),
                    schema.globalPositionColumn() != null
                            ? rs.getLong(schema.globalPositionColumn())
                            : 0));
        }
        return events;
    }

    private List<String> extractFirstColumn(ResultSet rs) throws SQLException {
        List<String> result = new ArrayList<>();
        while (rs.next())
            result.add(rs.getString(1));
        return result;
    }

    /**
     * Build schema from a manually specified table name, using known column
     * defaults.
     * Used when user provides {@code --table} to skip auto-detection.
     */
    private DetectedSchema buildManualSchema(String tableName) {
        return new DetectedSchema(
                tableName,
                "event_id", "aggregate_id", "aggregate_type",
                "sequence_number", "event_type", "payload",
                "metadata", "timestamp", "global_position");
    }
}
