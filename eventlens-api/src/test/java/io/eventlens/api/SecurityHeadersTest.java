package io.eventlens.api;

import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.*;
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
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersTest {

    private EventLensServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void healthEndpointIncludesSecurityHeadersAndRequestId() throws Exception {
        int port = freePort();

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
                return List.of();
            }

            @Override
            public List<StoredEvent> getRecentEvents(int limit) {
                return List.of(new StoredEvent(
                        "evt-1", "agg-1", "Type", 1, "Created",
                        "{}", "{}", Instant.now(), 1));
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
                return List.of("Type");
            }

            @Override
            public List<String> searchAggregates(String query, int limit) {
                return List.of();
            }
        };

        var cfg = new EventLensConfig();
        cfg.getServer().setPort(port);
        cfg.getServer().getAuth().setEnabled(false);
        cfg.getServer().getSecurity().getRateLimit().setEnabled(true);
        cfg.getServer().getSecurity().getRateLimit().setRequestsPerMinute(1);
        cfg.getServer().getSecurity().getRateLimit().setBurst(1);
        cfg.getServer().setAllowedOrigins(List.of("http://example.com"));

        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, cfg.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        server = new EventLensServer(cfg, reader, replayEngine, new ReducerRegistry(), new PluginManager(30), "default", bisectEngine, anomalyDetector, exportEngine, diffEngine);
        server.start();

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/health".formatted(port)))
                .header("Origin", "http://example.com")
                .header("X-Forwarded-For", "203.0.113.9")
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(res.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(res.headers().firstValue("Content-Security-Policy")).isPresent();
        assertThat(res.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(res.headers().firstValue("X-RateLimit-Limit")).hasValue("1");
    }

    @Test
    void forwardedHttpsAddsHsts() throws Exception {
        int port = freePort();

        EventStoreReader reader = new EventStoreReader() {
            @Override public List<StoredEvent> getEvents(String aggregateId) { return List.of(); }
            @Override public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) { return List.of(); }
            @Override public List<String> findAggregateIds(String aggregateType, int limit, int offset) { return List.of(); }
            @Override public List<StoredEvent> getRecentEvents(int limit) { return List.of(); }
            @Override public List<StoredEvent> getEventsAfter(long globalPosition, int limit) { return List.of(); }
            @Override public long countEvents(String aggregateId) { return 0; }
            @Override public List<String> getAggregateTypes() { return List.of(); }
            @Override public List<String> searchAggregates(String query, int limit) { return List.of(); }
        };

        var cfg = new EventLensConfig();
        cfg.getServer().setPort(port);
        cfg.getServer().getAuth().setEnabled(false);
        cfg.getServer().getSecurity().getRateLimit().setEnabled(false);
        cfg.getServer().setAllowedOrigins(List.of("http://example.com"));

        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, cfg.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        server = new EventLensServer(cfg, reader, replayEngine, new ReducerRegistry(), new PluginManager(30), "default", bisectEngine, anomalyDetector, exportEngine, diffEngine);
        server.start();

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/health".formatted(port)))
                .header("Origin", "http://example.com")
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(res.headers().firstValue("Strict-Transport-Security")).hasValue("max-age=31536000; includeSubDomains");
    }

    @Test
    void rateLimitReturns429AndRetryAfter() throws Exception {
        int port = freePort();

        EventStoreReader reader = new EventStoreReader() {
            @Override public List<StoredEvent> getEvents(String aggregateId) { return List.of(); }
            @Override public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) { return List.of(); }
            @Override public List<String> findAggregateIds(String aggregateType, int limit, int offset) { return List.of(); }
            @Override public List<StoredEvent> getRecentEvents(int limit) { return List.of(); }
            @Override public List<StoredEvent> getEventsAfter(long globalPosition, int limit) { return List.of(); }
            @Override public long countEvents(String aggregateId) { return 0; }
            @Override public List<String> getAggregateTypes() { return List.of(); }
            @Override public List<String> searchAggregates(String query, int limit) { return List.of(); }
        };

        var cfg = new EventLensConfig();
        cfg.getServer().setPort(port);
        cfg.getServer().getAuth().setEnabled(false);
        cfg.getServer().getSecurity().getRateLimit().setEnabled(true);
        cfg.getServer().getSecurity().getRateLimit().setRequestsPerMinute(1);
        cfg.getServer().getSecurity().getRateLimit().setBurst(1);
        cfg.getServer().setAllowedOrigins(List.of("http://example.com"));

        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, cfg.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        server = new EventLensServer(cfg, reader, replayEngine, new ReducerRegistry(), new PluginManager(30), "default", bisectEngine, anomalyDetector, exportEngine, diffEngine);
        server.start();

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/health".formatted(port)))
                .header("Origin", "http://example.com")
                .header("X-Forwarded-For", "203.0.113.77")
                .GET()
                .build();

        HttpResponse<String> first = client.send(req, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.headers().firstValue("Retry-After")).isPresent();
        assertThat(second.headers().firstValue("X-RateLimit-Remaining")).hasValue("0");
    }

    @Test
    void corsRejectsNonAllowlistedOrigin() throws Exception {
        int port = freePort();

        EventStoreReader reader = new EventStoreReader() {
            @Override public List<StoredEvent> getEvents(String aggregateId) { return List.of(); }
            @Override public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) { return List.of(); }
            @Override public List<String> findAggregateIds(String aggregateType, int limit, int offset) { return List.of(); }
            @Override public List<StoredEvent> getRecentEvents(int limit) { return List.of(); }
            @Override public List<StoredEvent> getEventsAfter(long globalPosition, int limit) { return List.of(); }
            @Override public long countEvents(String aggregateId) { return 0; }
            @Override public List<String> getAggregateTypes() { return List.of(); }
            @Override public List<String> searchAggregates(String query, int limit) { return List.of(); }
        };

        var cfg = new EventLensConfig();
        cfg.getServer().setPort(port);
        cfg.getServer().getAuth().setEnabled(false);
        cfg.getServer().getSecurity().getRateLimit().setEnabled(false);
        cfg.getServer().setAllowedOrigins(List.of("http://allowed.example"));

        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, cfg.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        server = new EventLensServer(cfg, reader, replayEngine, new ReducerRegistry(), new PluginManager(30), "default", bisectEngine, anomalyDetector, exportEngine, diffEngine);
        server.start();

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/health".formatted(port)))
                .header("Origin", "http://blocked.example")
                .GET()
                .build();

        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(403);
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}


