package io.eventlens.pg;

import io.eventlens.core.exception.SchemaDetectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Auto-detects the event store table structure from PostgreSQL metadata.
 * Works with multiple common naming conventions out of the box.
 *
 * <p>
 * Override with {@code --table} CLI flag or {@code datasource.table} in config
 * when auto-detection fails.
 */
public class PgSchemaDetector {

    private static final Logger log = LoggerFactory.getLogger(PgSchemaDetector.class);

    // Well-known event store table names, in order of preference
    private static final List<String> CANDIDATE_TABLES = List.of(
            "event_store", "events", "domain_events", "stored_events",
            "event_log", "aggregate_events", "es_events");

    public record DetectedSchema(
            String tableName,
            String eventIdColumn,
            String aggregateIdColumn,
            String aggregateTypeColumn, // nullable
            String sequenceColumn,
            String eventTypeColumn,
            String payloadColumn,
            String metadataColumn, // nullable
            String timestampColumn,
            String globalPositionColumn // nullable
    ) {
    }

    public DetectedSchema detect(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String candidate : CANDIDATE_TABLES) {
                try (ResultSet rs = meta.getColumns(null, null, candidate, null)) {
                    if (rs.next()) {
                        log.info("Auto-detected event store table: '{}'", candidate);
                        return detectColumns(candidate, conn);
                    }
                }
            }
            throw new SchemaDetectionException(
                    "No event store table found. Searched: " + CANDIDATE_TABLES +
                            ". Use --table flag or datasource.table config to specify manually.");
        } catch (SQLException e) {
            throw new SchemaDetectionException("Schema detection failed", e);
        }
    }

    private DetectedSchema detectColumns(String table, Connection conn) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                columns.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
            }
        }
        log.debug("Columns for table '{}': {}", table, columns.keySet());

        return new DetectedSchema(
                table,
                findColumn(columns, table, "event_id", "id", "uid"),
                findColumn(columns, table, "aggregate_id", "stream_id", "entity_id"),
                findColumnOrNull(columns, "aggregate_type", "stream_type", "entity_type", "type"),
                findColumn(columns, table, "sequence_number", "version", "seq", "position", "event_number"),
                findColumn(columns, table, "event_type", "type_name", "event_name"),
                findColumn(columns, table, "payload", "data", "event_data", "body"),
                findColumnOrNull(columns, "metadata", "meta", "headers"),
                findColumn(columns, table, "timestamp", "created_at", "occurred_at", "event_timestamp"),
                findColumnOrNull(columns, "global_position", "global_seq", "log_position"));
    }

    private String findColumn(Map<String, String> columns, String table, String... candidates) {
        for (String c : candidates) {
            if (columns.containsKey(c))
                return c;
        }
        throw new SchemaDetectionException(
                "Cannot detect required column in table '" + table + "'. " +
                        "Tried: " + Arrays.toString(candidates) + ". " +
                        "Use --table or override column mapping in config.");
    }

    private String findColumnOrNull(Map<String, String> columns, String... candidates) {
        for (String c : candidates) {
            if (columns.containsKey(c))
                return c;
        }
        return null;
    }
}
