package io.eventlens.pg;

import io.eventlens.core.model.StoredEvent;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PgEventStoreReaderIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eventlens_test");

    private PgEventStoreReader reader;

    @BeforeAll
    static void createSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute("""
                        CREATE TABLE event_store (
                            event_id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                            aggregate_id     VARCHAR(255) NOT NULL,
                            aggregate_type   VARCHAR(255) NOT NULL,
                            sequence_number  BIGINT       NOT NULL,
                            event_type       VARCHAR(255) NOT NULL,
                            payload          JSONB        NOT NULL,
                            metadata         JSONB        NOT NULL DEFAULT '{}',
                            timestamp        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            global_position  BIGSERIAL,
                            UNIQUE (aggregate_id, sequence_number)
                        )
                    """);
        }
    }

    @BeforeEach
    void setUp() {
        var config = new PgConfig(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword(), "event_store");
        reader = new PgEventStoreReader(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE event_store RESTART IDENTITY");
        }
        reader.close();
    }

    @Test
    void schemaDetectorFindsEventStoreTable() {
        // Just constructing the reader exercises the schema detector
        assertThat(reader).isNotNull();
    }

    @Test
    void getEventsReturnsOrderedEvents() throws Exception {
        insert("ACC-001", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ACC-001", "BankAccount", 2, "MoneyDeposited", "{\"amount\":100}");
        insert("ACC-001", "BankAccount", 3, "MoneyDeposited", "{\"amount\":50}");

        List<StoredEvent> events = reader.getEvents("ACC-001");

        assertThat(events).hasSize(3);
        assertThat(events.get(0).sequenceNumber()).isEqualTo(1);
        assertThat(events.get(0).eventType()).isEqualTo("AccountCreated");
        assertThat(events.get(2).sequenceNumber()).isEqualTo(3);
    }

    @Test
    void getEventsUpToRespectsMaxSequence() throws Exception {
        insert("ACC-002", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ACC-002", "BankAccount", 2, "MoneyDeposited", "{\"amount\":100}");
        insert("ACC-002", "BankAccount", 3, "MoneyWithdrawn", "{\"amount\":30}");

        List<StoredEvent> events = reader.getEventsUpTo("ACC-002", 2);

        assertThat(events).hasSize(2);
        assertThat(events.getLast().sequenceNumber()).isEqualTo(2);
    }

    @Test
    void searchAggregatesFindsPartialMatch() throws Exception {
        insert("ACC-001", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ACC-002", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ORD-001", "Order", 1, "OrderCreated", "{\"total\":50}");

        List<String> results = reader.searchAggregates("ACC", 10);

        assertThat(results).containsExactlyInAnyOrder("ACC-001", "ACC-002");
    }

    @Test
    void countEventsReturnsCorrectCount() throws Exception {
        insert("ACC-003", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ACC-003", "BankAccount", 2, "MoneyDeposited", "{\"amount\":200}");

        assertThat(reader.countEvents("ACC-003")).isEqualTo(2);
        assertThat(reader.countEvents("NON-EXISTENT")).isEqualTo(0);
    }

    @Test
    void getEventsWithPaginationReturnsWindowedSlice() throws Exception {
        insert("ACC-004", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ACC-004", "BankAccount", 2, "MoneyDeposited", "{\"amount\":100}");
        insert("ACC-004", "BankAccount", 3, "MoneyDeposited", "{\"amount\":50}");
        insert("ACC-004", "BankAccount", 4, "MoneyWithdrawn", "{\"amount\":20}");

        // limit=2, offset=1 -> should return sequence 2 and 3
        List<StoredEvent> window = reader.getEvents("ACC-004", 2, 1);

        assertThat(window).hasSize(2);
        assertThat(window.get(0).sequenceNumber()).isEqualTo(2);
        assertThat(window.get(1).sequenceNumber()).isEqualTo(3);
    }

    @Test
    void getAggregateTypesReturnsDistinctTypes() throws Exception {
        insert("ACC-001", "BankAccount", 1, "AccountCreated", "{\"balance\":0}");
        insert("ORD-001", "Order", 1, "OrderCreated", "{\"total\":50}");

        List<String> types = reader.getAggregateTypes();

        assertThat(types).containsExactlyInAnyOrder("BankAccount", "Order");
    }

    // ── Test helper ──────────────────────────────────────────────────────────

    private void insert(String aggId, String aggType, long seq,
            String eventType, String payload) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement ps = conn.prepareStatement("""
                           INSERT INTO event_store (aggregate_id, aggregate_type, sequence_number,
                               event_type, payload)
                           VALUES (?, ?, ?, ?, ?::jsonb)
                        """)) {
            ps.setString(1, aggId);
            ps.setString(2, aggType);
            ps.setLong(3, seq);
            ps.setString(4, eventType);
            ps.setString(5, payload);
            ps.executeUpdate();
        }
    }
}

