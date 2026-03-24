package io.eventlens.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.AnomalyDetector;
import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.engine.DiffEngine;
import io.eventlens.core.engine.ExportEngine;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.mysql.MySqlEventSourcePlugin;
import io.eventlens.pg.PostgresEventSourcePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class V4ReadinessApiE2ETest {

    private static final String AGGREGATE_ID = "SHARED-001";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Container
    @SuppressWarnings("resource")
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("eventlens_pg");

    @Container
    @SuppressWarnings("resource")
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("eventlens_mysql");

    private RunningSystem running;

    @AfterEach
    void tearDown() {
        if (running != null) {
            running.close();
        }
    }

    @Test
    void multiSourceSwitchingReturnsDifferentTimelineData() throws Exception {
        running = startSystem();

        JsonNode postgresTimeline = getJson("/api/v1/aggregates/" + AGGREGATE_ID + "/timeline");
        JsonNode mysqlTimeline = getJson("/api/v1/aggregates/" + AGGREGATE_ID + "/timeline?source=mysql-alt");

        assertThat(postgresTimeline.path("aggregateType").asText()).isEqualTo("BankAccount");
        assertThat(postgresTimeline.path("events")).hasSize(2);
        assertThat(postgresTimeline.at("/events/0/eventType").asText()).isEqualTo("AccountCreated");
        assertThat(postgresTimeline.at("/events/1/payload").asText()).contains("postgres-note");

        assertThat(mysqlTimeline.path("aggregateType").asText()).isEqualTo("Order");
        assertThat(mysqlTimeline.path("events")).hasSize(2);
        assertThat(mysqlTimeline.at("/events/0/eventType").asText()).isEqualTo("OrderCreated");
        assertThat(mysqlTimeline.at("/events/1/payload").asText()).contains("mysql-note");

        assertThat(postgresTimeline.path("aggregateType").asText())
                .isNotEqualTo(mysqlTimeline.path("aggregateType").asText());
        assertThat(postgresTimeline.at("/events/1/payload").asText())
                .isNotEqualTo(mysqlTimeline.at("/events/1/payload").asText());
    }

    @Test
    void pluginFailureIsolationKeepsHealthySourceServing() throws Exception {
        running = startSystem();

        mysql.stop();

        JsonNode datasources = waitForDatasourceListStatus("mysql-alt", "degraded");
        assertThat(statusFor(datasources, "pg-primary")).isEqualTo("ready");
        assertThat(statusFor(datasources, "mysql-alt")).isEqualTo("degraded");

        JsonNode postgresTimeline = getJson("/api/v1/aggregates/" + AGGREGATE_ID + "/timeline");
        assertThat(postgresTimeline.path("aggregateType").asText()).isEqualTo("BankAccount");
        assertThat(postgresTimeline.at("/events/1/payload").asText()).contains("postgres-note");

        JsonNode mysqlHealth = getJson("/api/v1/datasources/mysql-alt/health");
        assertThat(mysqlHealth.path("status").asText()).isEqualTo("degraded");
        assertThat(mysqlHealth.at("/health/state").asText()).isEqualTo("down");
    }

    @Test
    void lazyPayloadRoundTripReturnsMetadataThenFullPayload() throws Exception {
        running = startSystem();

        JsonNode metadataTimeline = getJson("/api/v1/aggregates/" + AGGREGATE_ID + "/timeline?fields=metadata");
        JsonNode fullTransitions = getJson("/api/v1/aggregates/" + AGGREGATE_ID + "/transitions");

        assertThat(metadataTimeline.at("/events/0/payload").isNull()).isTrue();
        assertThat(metadataTimeline.at("/events/1/payload").isNull()).isTrue();

        JsonNode selectedEvent = transitionEvent(fullTransitions, 2);
        assertThat(selectedEvent).isNotNull();
        assertThat(selectedEvent.path("payload").isTextual()).isTrue();
        assertThat(selectedEvent.path("payload").asText()).contains("postgres-note");
    }

    private RunningSystem startSystem() throws Exception {
        createPostgresSchema();
        createMySqlSchema();
        seedPostgres();
        seedMySql();

        PluginManager pluginManager = new PluginManager(1);
        pluginManager.registerEventSource("pg-primary", new PostgresEventSourcePlugin(), Map.of(
                "jdbcUrl", postgres.getJdbcUrl(),
                "username", postgres.getUsername(),
                "password", postgres.getPassword(),
                "tableName", "event_store"
        ));
        pluginManager.registerEventSource("mysql-alt", new MySqlEventSourcePlugin(), Map.of(
                "jdbcUrl", mysql.getJdbcUrl(),
                "username", mysql.getUsername(),
                "password", mysql.getPassword(),
                "tableName", "event_store"
        ));
        pluginManager.startHealthChecks();

        EventStoreReader defaultReader = (EventStoreReader) pluginManager.getEventSource("pg-primary").orElseThrow();
        ReducerRegistry reducers = new ReducerRegistry();
        ReplayEngine replayEngine = new ReplayEngine(defaultReader, reducers);
        EventLensConfig config = new EventLensConfig();
        config.getServer().setPort(freePort());
        config.getServer().getAuth().setEnabled(false);
        config.getServer().getSecurity().getRateLimit().setEnabled(false);
        config.getAudit().setEnabled(false);

        var bisectEngine = new BisectEngine(replayEngine, defaultReader);
        var anomalyDetector = new AnomalyDetector(defaultReader, replayEngine, config.getAnomaly());
        var exportEngine = new ExportEngine(defaultReader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        EventLensServer server = new EventLensServer(
                config,
                defaultReader,
                replayEngine,
                reducers,
                pluginManager,
                "pg-primary",
                bisectEngine,
                anomalyDetector,
                exportEngine,
                diffEngine
        );
        server.start();

        return new RunningSystem(server, pluginManager, config.getServer().getPort());
    }

    private void createPostgresSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    DROP TABLE IF EXISTS event_store;
                    CREATE TABLE event_store (
                        event_id UUID PRIMARY KEY,
                        aggregate_id VARCHAR(255) NOT NULL,
                        aggregate_type VARCHAR(255) NOT NULL,
                        sequence_number BIGINT NOT NULL,
                        event_type VARCHAR(255) NOT NULL,
                        payload JSONB NOT NULL,
                        metadata JSONB NOT NULL DEFAULT '{}',
                        timestamp TIMESTAMPTZ NOT NULL,
                        global_position BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                        UNIQUE (aggregate_id, sequence_number)
                    )
                    """);
        }
    }

    private void createMySqlSchema() throws Exception {
        try (Connection conn = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS event_store");
            stmt.execute("""
                    CREATE TABLE event_store (
                        event_id VARCHAR(64) PRIMARY KEY,
                        aggregate_id VARCHAR(255) NOT NULL,
                        aggregate_type VARCHAR(255) NOT NULL,
                        sequence_number BIGINT NOT NULL,
                        event_type VARCHAR(255) NOT NULL,
                        payload JSON NOT NULL,
                        metadata JSON NULL,
                        timestamp TIMESTAMP NOT NULL,
                        global_position BIGINT NOT NULL AUTO_INCREMENT UNIQUE,
                        UNIQUE KEY uq_aggregate_sequence (aggregate_id, sequence_number)
                    )
                    """);
        }
    }

    private void seedPostgres() throws Exception {
        insertPostgres(1, "AccountCreated", """
                {"owner":"Alice","balance":0,"note":"postgres-note-created"}
                """, Instant.parse("2026-03-24T10:00:00Z"));
        insertPostgres(2, "MoneyDeposited", """
                {"amount":125,"balance":125,"note":"postgres-note-deposit"}
                """, Instant.parse("2026-03-24T10:05:00Z"));
    }

    private void seedMySql() throws Exception {
        insertMySql(1, "OrderCreated", """
                {"customer":"Bob","status":"created","note":"mysql-note-created"}
                """, Instant.parse("2026-03-24T11:00:00Z"));
        insertMySql(2, "OrderApproved", """
                {"status":"approved","approver":"ops","note":"mysql-note-approved"}
                """, Instant.parse("2026-03-24T11:05:00Z"));
    }

    private void insertPostgres(long sequence, String eventType, String payload, Instant timestamp) throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO event_store (
                         event_id, aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata, timestamp
                     ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                     """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, AGGREGATE_ID);
            ps.setString(3, "BankAccount");
            ps.setLong(4, sequence);
            ps.setString(5, eventType);
            ps.setString(6, payload.strip());
            ps.setString(7, "{\"source\":\"postgres\"}");
            ps.setObject(8, timestamp);
            ps.executeUpdate();
        }
    }

    private void insertMySql(long sequence, String eventType, String payload, Instant timestamp) throws Exception {
        try (Connection conn = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO event_store (
                         event_id, aggregate_id, aggregate_type, sequence_number, event_type, payload, metadata, timestamp
                     ) VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), ?)
                     """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, AGGREGATE_ID);
            ps.setString(3, "Order");
            ps.setLong(4, sequence);
            ps.setString(5, eventType);
            ps.setString(6, payload.strip());
            ps.setString(7, "{\"source\":\"mysql\"}");
            ps.setObject(8, timestamp);
            ps.executeUpdate();
        }
    }

    private JsonNode getJson(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(running.baseUrl() + pathAndQuery))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = running.client().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .withFailMessage("Expected 200 for %s but got %s with body %s", pathAndQuery, response.statusCode(), response.body())
                .isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode waitForDatasourceListStatus(String datasourceId, String expectedStatus) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = getJson("/api/v1/datasources");
            if (expectedStatus.equals(statusFor(last, datasourceId))) {
                return last;
            }
            Thread.sleep(250);
        }
        return last;
    }

    private String statusFor(JsonNode datasources, String datasourceId) {
        for (JsonNode datasource : datasources) {
            if (datasourceId.equals(datasource.path("id").asText())) {
                return datasource.path("status").asText();
            }
        }
        return "";
    }

    private JsonNode transitionEvent(JsonNode transitions, long sequence) {
        for (JsonNode transition : transitions) {
            JsonNode event = transition.path("event");
            if (event.path("sequenceNumber").asLong() == sequence) {
                return event;
            }
        }
        return null;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record RunningSystem(EventLensServer server, PluginManager pluginManager, int port) implements AutoCloseable {
        private HttpClient client() {
            return HttpClient.newHttpClient();
        }

        private String baseUrl() {
            return "http://localhost:" + port;
        }

        @Override
        public void close() {
            server.stop();
            pluginManager.close();
        }
    }
}
