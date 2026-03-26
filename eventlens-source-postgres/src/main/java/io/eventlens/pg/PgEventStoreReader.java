package io.eventlens.pg;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
import io.eventlens.core.exception.EventStoreException;
import io.eventlens.core.exception.QueryTimeoutException;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.pg.PgSchemaDetector.DetectedSchema;
import io.eventlens.spi.EventStatistics;
import io.eventlens.spi.EventStatisticsQuery;
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

/**
 * PostgreSQL-backed implementation of {@link EventStoreReader}.
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
        hc.setReadOnly(true);
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
        String sql = "SELECT * FROM %s WHERE %s = ? ORDER BY %s ASC LIMIT ? OFFSET ?"
                .formatted(table, aggCol, seqCol);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            throw mapException("Failed to read events for aggregate: " + aggregateId, e);
        }
    }

    @Override
    public List<StoredEvent> getEventsAfterSequence(String aggregateId, long afterSequence, int limit) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String seqCol = quoteIdentifier(schema.sequenceColumn());
        String sql = "SELECT * FROM %s WHERE %s = ? AND %s > ? ORDER BY %s ASC LIMIT ?"
                .formatted(table, aggCol, seqCol, seqCol);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setLong(2, afterSequence);
            ps.setInt(3, limit);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            throw mapException("Failed to read events after sequence " + afterSequence + " for aggregate: " + aggregateId, e);
        }
    }

    @Override
    public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String seqCol = quoteIdentifier(schema.sequenceColumn());
        String sql = "SELECT * FROM %s WHERE %s = ? AND %s <= ? ORDER BY %s ASC"
                .formatted(table, aggCol, seqCol, seqCol);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ps.setLong(2, maxSequence);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            throw mapException("Failed to read events up to sequence " + maxSequence, e);
        }
    }

    @Override
    public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        final String sql;
        if (schema.aggregateTypeColumn() != null) {
            String typeCol = quoteIdentifier(schema.aggregateTypeColumn());
            sql = "SELECT DISTINCT %s FROM %s WHERE %s = ? ORDER BY %s LIMIT ? OFFSET ?"
                    .formatted(aggCol, table, typeCol, aggCol);
        } else {
            sql = "SELECT DISTINCT %s FROM %s ORDER BY %s LIMIT ? OFFSET ?"
                    .formatted(aggCol, table, aggCol);
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
            throw mapException("Failed to find aggregate IDs for type: " + aggregateType, e);
        }
    }

    @Override
    public List<StoredEvent> getRecentEvents(int limit) {
        String orderCol = schema.globalPositionColumn() != null
                ? schema.globalPositionColumn()
                : schema.timestampColumn() != null
                ? schema.timestampColumn()
                : schema.eventIdColumn();
        String sql = "SELECT * FROM %s ORDER BY %s DESC LIMIT ?"
                .formatted(quoteIdentifier(schema.tableName()), quoteIdentifier(orderCol));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setInt(1, limit);
            List<StoredEvent> results = mapResults(ps.executeQuery());
            Collections.reverse(results);
            return results;
        } catch (SQLException e) {
            throw mapException("Failed to read recent events", e);
        }
    }

    @Override
    public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
        String posColumn = schema.globalPositionColumn() != null
                ? schema.globalPositionColumn()
                : schema.eventIdColumn();
        String posColQ = quoteIdentifier(posColumn);
        String sql = "SELECT * FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT ?"
                .formatted(quoteIdentifier(schema.tableName()), posColQ, posColQ);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setLong(1, globalPosition);
            ps.setInt(2, limit);
            return mapResults(ps.executeQuery());
        } catch (SQLException e) {
            throw mapException("Failed to poll events after position " + globalPosition, e);
        }
    }

    @Override
    public long countEvents(String aggregateId) {
        String sql = "SELECT COUNT(*) FROM %s WHERE %s = ?"
                .formatted(quoteIdentifier(schema.tableName()), quoteIdentifier(schema.aggregateIdColumn()));
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, aggregateId);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw mapException("Failed to count events for: " + aggregateId, e);
        }
    }

    @Override
    public List<String> getAggregateTypes() {
        if (schema.aggregateTypeColumn() == null) {
            return List.of();
        }
        String typeCol = quoteIdentifier(schema.aggregateTypeColumn());
        String sql = "SELECT DISTINCT %s FROM %s ORDER BY %s"
                .formatted(typeCol, quoteIdentifier(schema.tableName()), typeCol);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            throw mapException("Failed to get aggregate types", e);
        }
    }

    @Override
    public List<String> searchAggregates(String query, int limit) {
        String table = quoteIdentifier(schema.tableName());
        String aggCol = quoteIdentifier(schema.aggregateIdColumn());
        String sql = "SELECT DISTINCT %s FROM %s WHERE %s ILIKE ? ORDER BY %s LIMIT ?"
                .formatted(aggCol, table, aggCol, aggCol);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setString(1, "%" + query + "%");
            ps.setInt(2, limit);
            return extractFirstColumn(ps.executeQuery());
        } catch (SQLException e) {
            throw mapException("Failed to search aggregates", e);
        }
    }

    public EventStatistics statistics(EventStatisticsQuery query) {
        if (schema.timestampColumn() == null) {
            return EventStatistics.unavailable("Statistics require a timestamp column");
        }
        try (Connection conn = dataSource.getConnection()) {
            long totalEvents = singleLong(conn,
                    "SELECT COUNT(*) FROM %s".formatted(quoteIdentifier(schema.tableName())));
            long distinctAggregates = singleLong(conn,
                    "SELECT COUNT(DISTINCT %s) FROM %s".formatted(
                            quoteIdentifier(schema.aggregateIdColumn()),
                            quoteIdentifier(schema.tableName())));

            List<EventStatistics.TypeCount> eventTypes = typeCounts(conn, schema.eventTypeColumn(), 10);
            List<EventStatistics.TypeCount> aggregateTypes = schema.aggregateTypeColumn() == null
                    ? List.of()
                    : typeCounts(conn, schema.aggregateTypeColumn(), 10);
            List<EventStatistics.ThroughputPoint> throughput = throughput(conn, query);
            return new EventStatistics(totalEvents, distinctAggregates, eventTypes, aggregateTypes, throughput, true, null);
        } catch (SQLException e) {
            throw mapException("Failed to calculate statistics", e);
        }
    }

    private static boolean isQueryCanceled(SQLException e) {
        return "57014".equals(e.getSQLState());
    }

    private RuntimeException mapException(String message, SQLException e) {
        if (isQueryCanceled(e)) {
            return new QueryTimeoutException(
                    queryTimeoutSeconds,
                    "Query exceeded %ds timeout. Consider narrowing your search or adding indexes."
                            .formatted(queryTimeoutSeconds),
                    e);
        }
        return new EventStoreException(message, e);
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("PostgreSQL connection pool closed");
        }
    }

    private static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("Identifier cannot be null or empty");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private List<StoredEvent> mapResults(ResultSet rs) throws SQLException {
        List<StoredEvent> events = new ArrayList<>();
        while (rs.next()) {
            events.add(new StoredEvent(
                    Objects.toString(rs.getObject(schema.eventIdColumn()), ""),
                    Objects.toString(rs.getObject(schema.aggregateIdColumn()), ""),
                    schema.aggregateTypeColumn() != null
                            ? Objects.toString(rs.getObject(schema.aggregateTypeColumn()), "unknown")
                            : "unknown",
                    rs.getLong(schema.sequenceColumn()),
                    rs.getString(schema.eventTypeColumn()),
                    rs.getString(schema.payloadColumn()),
                    safeGetString(rs, schema.metadataColumn(), "{}"),
                    safeGetInstant(rs, schema.timestampColumn()),
                    schema.globalPositionColumn() != null
                            ? rs.getLong(schema.globalPositionColumn())
                            : safeGetLong(rs, schema.eventIdColumn())));
        }
        return events;
    }

    private String safeGetString(ResultSet rs, String colName, String fallback) {
        if (colName == null) {
            return fallback;
        }
        try {
            String val = rs.getString(colName);
            return val != null ? val : fallback;
        } catch (SQLException e) {
            log.debug("Could not read optional column '{}': {}", colName, e.getMessage());
            return fallback;
        }
    }

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

    private Instant safeGetInstant(ResultSet rs, String colName) {
        if (colName == null) {
            return Instant.EPOCH;
        }
        try {
            Timestamp ts = rs.getTimestamp(colName);
            return ts != null ? ts.toInstant() : Instant.EPOCH;
        } catch (SQLException e) {
            log.debug("Could not read timestamp column '{}': {}", colName, e.getMessage());
            return Instant.EPOCH;
        }
    }

    private long singleLong(Connection conn, String sql) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private List<EventStatistics.TypeCount> typeCounts(Connection conn, String column, int limit) throws SQLException {
        String col = quoteIdentifier(column);
        String sql = "SELECT %s, COUNT(*) FROM %s GROUP BY %s ORDER BY COUNT(*) DESC, %s ASC LIMIT ?"
                .formatted(col, quoteIdentifier(schema.tableName()), col, col);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setInt(1, limit);
            ResultSet rs = ps.executeQuery();
            List<EventStatistics.TypeCount> counts = new ArrayList<>();
            while (rs.next()) {
                counts.add(new EventStatistics.TypeCount(Objects.toString(rs.getObject(1), "unknown"), rs.getLong(2)));
            }
            return counts;
        }
    }

    private List<EventStatistics.ThroughputPoint> throughput(Connection conn, EventStatisticsQuery query) throws SQLException {
        String ts = quoteIdentifier(schema.timestampColumn());
        String sql = """
                SELECT to_char(date_trunc('hour', %s), 'YYYY-MM-DD"T"HH24:00:00"Z"') AS bucket,
                       COUNT(*) AS bucket_count
                FROM %s
                WHERE %s >= NOW() - (? * INTERVAL '1 hour')
                GROUP BY date_trunc('hour', %s)
                ORDER BY date_trunc('hour', %s) ASC
                LIMIT ?
                """.formatted(ts, quoteIdentifier(schema.tableName()), ts, ts, ts);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            ps.setInt(1, query.bucketHours() * query.maxBuckets());
            ps.setInt(2, query.maxBuckets());
            ResultSet rs = ps.executeQuery();
            List<EventStatistics.ThroughputPoint> points = new ArrayList<>();
            while (rs.next()) {
                points.add(new EventStatistics.ThroughputPoint(rs.getString(1), rs.getLong(2)));
            }
            return points;
        }
    }

    private List<String> extractFirstColumn(ResultSet rs) throws SQLException {
        List<String> result = new ArrayList<>();
        while (rs.next()) {
            result.add(Objects.toString(rs.getObject(1), ""));
        }
        return result;
    }
}
