package io.eventlens.mysql;

import io.eventlens.core.model.StoredEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MySqlEventStoreReaderIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("eventlens_test");

    private MySqlEventStoreReader reader;

    @BeforeEach
    void setUp() throws Exception {
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
        reader = new MySqlEventStoreReader(new MySqlConfig(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), "event_store", null, null, 30));
    }

    @AfterEach
    void tearDown() {
        if (reader != null) {
            reader.close();
        }
    }

    @Test
    void getEventsReturnsOrderedEvents() throws Exception {
        insert("ACC-001", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ACC-001", "BankAccount", 2, "MoneyDeposited", "{\"amount\":100}");
        insert("ACC-001", "BankAccount", 3, "MoneyDeposited", "{\"amount\":50}");

        List<StoredEvent> events = reader.getEvents("ACC-001");
        assertThat(events).hasSize(3);
        assertThat(events.get(0).sequenceNumber()).isEqualTo(1);
        assertThat(events.get(2).sequenceNumber()).isEqualTo(3);
    }

    @Test
    void searchAggregatesFindsPartialMatch() throws Exception {
        insert("ACC-001", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ACC-002", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ORD-001", "Order", 1, "OrderCreated", "{\"total\":50}");

        assertThat(reader.searchAggregates("acc", 10)).containsExactlyInAnyOrder("ACC-001", "ACC-002");
    }

    private void insert(String aggId, String aggType, long seq, String eventType, String payload) throws Exception {
        try (Connection conn = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO event_store (event_id, aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata)
                     VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON))
                     """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, aggId);
            ps.setString(3, aggType);
            ps.setLong(4, seq);
            ps.setString(5, eventType);
            ps.setString(6, payload);
            ps.setString(7, "{}");
            ps.executeUpdate();
        }
    }
}
