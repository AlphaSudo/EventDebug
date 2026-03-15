package io.eventlens.pg;

import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
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
 * when auto-detection fails. For full control, use the
 * {@code datasource.columns}
 * block in eventlens.yaml to map any column name explicitly.
 */
public class PgSchemaDetector {

    private static final Logger log = LoggerFactory.getLogger(PgSchemaDetector.class);

    // Well-known event store table names, in order of preference.
    // Covers: generic, Axon, Marten, Akka Persistence, eugene-khyst, outbox pattern
    private static final List<String> CANDIDATE_TABLES = List.of(
            "event_store", "events", "domain_events", "stored_events",
            "event_log", "aggregate_events", "es_events",
            "es_event", "mt_events", "domain_event_entry",
            "event_journal", "outbox_event");

    public record DetectedSchema(
            String tableName,
            String eventIdColumn,
            String aggregateIdColumn,
            String aggregateTypeColumn, // nullable
            String sequenceColumn,
            String eventTypeColumn,
            String payloadColumn,
            String metadataColumn, // nullable
            String timestampColumn, // nullable — epoch fallback when absent
            String globalPositionColumn // nullable — event_id fallback when absent
    ) {
    }

    /**
     * Auto-detect the event store table and columns from the database.
     * Applies any {@link ColumnMappingConfig} overrides on top of the detected
     * values.
     */
    public DetectedSchema detect(DataSource dataSource, ColumnMappingConfig overrides) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            for (String candidate : CANDIDATE_TABLES) {
                try (ResultSet rs = meta.getColumns(null, null, candidate, null)) {
                    if (rs.next()) {
                        log.info("Auto-detected event store table: '{}'", candidate);
                        return detectColumns(candidate, conn, overrides);
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

    /** Backwards-compat overload: detect with no column overrides. */
    public DetectedSchema detect(DataSource dataSource) {
        return detect(dataSource, new ColumnMappingConfig());
    }

    /**
     * Detect columns for a specific, user-named table.
     * Still reads real DB metadata for column names — does NOT use the old
     * hardcoded buildManualSchema() approach. Fix 2.
     */
    public DetectedSchema detectForTable(String tableName, DataSource dataSource,
            ColumnMappingConfig overrides) {
        try (Connection conn = dataSource.getConnection()) {
            // Verify the table/view exists
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null)) {
                if (!rs.next()) {
                    throw new SchemaDetectionException(
                            "Table or view '" + tableName + "' not found in the database. " +
                                    "Check the datasource.table config value.");
                }
            }
            log.info("Using manually configured table/view: '{}'", tableName);
            return detectColumns(tableName, conn, overrides);
        } catch (SchemaDetectionException e) {
            throw e;
        } catch (SQLException e) {
            throw new SchemaDetectionException(
                    "Schema detection failed for table '" + tableName + "'", e);
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private DetectedSchema detectColumns(String table, Connection conn,
            ColumnMappingConfig overrides) throws SQLException {
        Map<String, String> columns = new LinkedHashMap<>();
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                columns.put(rs.getString("COLUMN_NAME").toLowerCase(), rs.getString("TYPE_NAME"));
            }
        }
        log.debug("Columns for table '{}': {}", table, columns.keySet());

        var detected = new DetectedSchema(
                table,
                overrides.getEventId() != null
                        ? overrides.getEventId()
                        : findColumn(columns, table, "event_id", "id", "uid"),
                overrides.getAggregateId() != null
                        ? overrides.getAggregateId()
                        : findColumn(columns, table, "aggregate_id", "stream_id", "entity_id", "stream_key"),
                overrides.getAggregateType() != null
                        ? overrides.getAggregateType()
                        : findColumnOrNull(columns, "aggregate_type", "stream_type", "entity_type"),
                overrides.getSequence() != null
                        ? overrides.getSequence()
                        : findColumn(columns, table, "sequence_number", "version", "seq", "position", "event_number", "revision"),
                overrides.getEventType() != null
                        ? overrides.getEventType()
                        : findColumn(columns, table, "event_type", "type_name", "event_name", "type"),
                overrides.getPayload() != null
                        ? overrides.getPayload()
                        : findColumn(columns, table, "payload", "data", "event_data", "body",
                                "json_data", "json_payload", "event_body", "event_payload"),
                overrides.getMetadata() != null
                        ? overrides.getMetadata()
                        : findColumnOrNull(columns, "metadata", "meta", "headers"),
                overrides.getTimestamp() != null
                        ? overrides.getTimestamp()
                        : findColumnOrNull(columns, "timestamp", "occurred_at", "event_timestamp",
                                "created_at", "inserted_at"),
                overrides.getGlobalPosition() != null
                        ? overrides.getGlobalPosition()
                        : findColumnOrNull(columns, "global_position", "global_seq", "log_position",
                                "seq_id", "transaction_id"));

        if (detected.timestampColumn() == null) {
            log.warn("No timestamp column detected in '{}'; events will use epoch as timestamp. "
                    + "Override with datasource.columns.timestamp in eventlens.yaml.", table);
        }
        if (detected.globalPositionColumn() == null) {
            log.info("No global_position column in '{}'; live tail will use '{}' as surrogate.",
                    table, detected.eventIdColumn());
        }
        return detected;
    }

    private String findColumn(Map<String, String> columns, String table, String... candidates) {
        for (String c : candidates) {
            if (columns.containsKey(c))
                return c;
        }
        throw new SchemaDetectionException(
                "Cannot detect required column in table '" + table + "'. " +
                        "Tried: " + Arrays.toString(candidates) + ". " +
                        "Use datasource.columns config to specify the column name explicitly.");
    }

    private String findColumnOrNull(Map<String, String> columns, String... candidates) {
        for (String c : candidates) {
            if (columns.containsKey(c))
                return c;
        }
        return null;
    }
}
