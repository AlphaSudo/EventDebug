package io.eventlens.mysql;

import io.eventlens.spi.EventSourcePlugin;
import io.eventlens.test.CanonicalEventSet;
import io.eventlens.test.EventSourcePluginTestKit;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

@Testcontainers(disabledWithoutDocker = true)
class MySqlEventSourcePluginContractTest extends EventSourcePluginTestKit {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("eventlens_contract_test");

    @Override
    protected EventSourcePlugin createPlugin() throws Exception {
        ensureSchema();
        var plugin = new MySqlEventSourcePlugin();
        plugin.initialize("contract-mysql", Map.of(
                "jdbcUrl", mysql.getJdbcUrl(),
                "username", mysql.getUsername(),
                "password", mysql.getPassword(),
                "tableName", "event_store"
        ));
        return plugin;
    }

    @Override
    protected void seedCanonicalEvents() throws Exception {
        for (var event : CanonicalEventSet.defaultEvents()) {
            try (Connection conn = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO event_store (event_id, aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata)
                         VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))
                         """)) {
                ps.setString(1, UUID.randomUUID().toString());
                ps.setString(2, event.aggregateId());
                ps.setString(3, event.aggregateType());
                ps.setLong(4, event.sequenceNumber());
                ps.setString(5, event.eventType());
                ps.setString(6, event.payload());
                ps.setString(7, event.metadata());
                ps.executeUpdate();
            }
        }
    }

    @Override
    protected void cleanupStore() throws Exception {
        try (Connection conn = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE event_store");
        }
    }

    private static void ensureSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS event_store (
                        event_id VARCHAR(64) PRIMARY KEY,
                        aggregate_id VARCHAR(255) NOT NULL,
                        aggregate_type VARCHAR(255) NOT NULL,
                        sequence_number BIGINT NOT NULL,
                        event_type VARCHAR(255) NOT NULL,
                        payload JSON NOT NULL,
                        metadata JSON NULL,
                        timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        global_position BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
                        UNIQUE KEY uq_aggregate_sequence (aggregate_id, sequence_number)
                    )
                    """);
            stmt.execute("TRUNCATE TABLE event_store");
        }
    }
}
