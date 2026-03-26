package io.eventlens.api;

import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.AnomalyDetector;
import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.engine.DiffEngine;
import io.eventlens.core.engine.ExportEngine;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationIntegrationTest {

    private EventLensServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void protectedApiRejectsMissingBasicCredentials() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"EventLens\"");
    }

    @Test
    void protectedApiAcceptsValidBasicCredentials() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ORD-1001");
    }

    @Test
    void authDisabledStillAllowsExistingReadFlow() throws Exception {
        int port = freePort();
        server = startServer(port, false);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ORD-1001");
    }

    private EventLensServer startServer(int port, boolean authEnabled) {
        EventStoreReader reader = new EventStoreReader() {
            @Override
            public List<StoredEvent> getEvents(String aggregateId) {
                return List.of();
            }

            @Override
            public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
                return List.of();
            }

            @Override
            public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
                return List.of("ORD-1001");
            }

            @Override
            public List<StoredEvent> getRecentEvents(int limit) {
                return List.of(new StoredEvent(
                        "evt-1", "ORD-1001", "Order", 1, "ORDER_CREATED",
                        "{}", "{}", Instant.now(), 1L));
            }

            @Override
            public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
                return List.of();
            }

            @Override
            public long countEvents(String aggregateId) {
                return 0;
            }

            @Override
            public List<String> getAggregateTypes() {
                return List.of("Order");
            }

            @Override
            public List<String> searchAggregates(String query, int limit) {
                return List.of("ORD-1001");
            }
        };

        var cfg = new EventLensConfig();
        cfg.getServer().setPort(port);
        cfg.getServer().getAuth().setEnabled(authEnabled);
        cfg.getServer().getAuth().setUsername("admin");
        cfg.getServer().getAuth().setPassword("correct horse battery");
        cfg.getServer().getSecurity().getRateLimit().setEnabled(false);

        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, cfg.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        EventLensServer eventLensServer = new EventLensServer(
                cfg,
                reader,
                replayEngine,
                new ReducerRegistry(),
                new PluginManager(30),
                "default",
                bisectEngine,
                anomalyDetector,
                exportEngine,
                diffEngine);
        eventLensServer.start();
        return eventLensServer;
    }

    private static String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
