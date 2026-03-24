package io.eventlens.pg;

import io.eventlens.spi.EventSourcePlugin;
import io.eventlens.test.CanonicalEventSet;
import io.eventlens.test.EventSourcePluginTestKit;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;

@Testcontainers(disabledWithoutDocker = true)
class PostgresEventSourcePluginContractTest extends EventSourcePluginTestKit {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eventlens_contract_test");

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS event_store (
                        event_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                        aggregate_id     VARCHAR(255) NOT NULL,
                        aggregate_type   VARCHAR(255) NOT NULL,
                        sequence_number  BIGINT NOT NULL,
                        event_type       VARCHAR(255) NOT NULL,
                        payload          JSONB NOT NULL,
                        metadata         JSONB NOT NULL DEFAULT '{}',
                        timestamp        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        global_position  BIGSERIAL,
                        UNIQUE (aggregate_id, sequence_number)
                    )
                    """);
        }
    }

    @Override
    protected EventSourcePlugin createPlugin() {
        var plugin = new PostgresEventSourcePlugin();
        plugin.initialize("contract-postgres", Map.of(
                "jdbcUrl", postgres.getJdbcUrl(),
                "username", postgres.getUsername(),
                "password", postgres.getPassword(),
                "tableName", "event_store"
        ));
        return plugin;
    }

    @Override
    protected void seedCanonicalEvents() throws Exception {
        for (var event : CanonicalEventSet.defaultEvents()) {
            try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO event_store (aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata)
                         VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                         """)) {
                ps.setString(1, event.aggregateId());
                ps.setString(2, event.aggregateType());
                ps.setLong(3, event.sequenceNumber());
                ps.setString(4, event.eventType());
                ps.setString(5, event.payload());
                ps.setString(6, event.metadata());
                ps.executeUpdate();
            }
        }
    }

    @Override
    protected void cleanupStore() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE event_store RESTART IDENTITY");
        }
    }
}
