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
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.spi.Event;
import io.eventlens.spi.EventQuery;
import io.eventlens.spi.EventQueryResult;
import io.eventlens.spi.EventSourcePlugin;
import io.eventlens.spi.HealthStatus;
import io.eventlens.spi.StreamAdapterPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAwarePanelsIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private EventLensServer server;
    private PluginManager pluginManager;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (pluginManager != null) {
            pluginManager.close();
        }
    }

    @Test
    void anomalyEndpointUsesSelectedSource() throws Exception {
        TestEventSourcePlugin primary = new TestEventSourcePlugin("pg-primary", anomalyEvents("PG-AGG"));
        TestEventSourcePlugin legacy = new TestEventSourcePlugin("mysql-alt", anomalyEvents("MYSQL-AGG"));
        startServer(primary, legacy, null, Map.of());

        JsonNode defaultAnomalies = getJson("/api/v1/anomalies/recent?limit=100");
        JsonNode mysqlAnomalies = getJson("/api/v1/anomalies/recent?limit=100&source=mysql-alt");

        assertThat(defaultAnomalies).isNotEmpty();
        assertThat(mysqlAnomalies).isNotEmpty();
        assertThat(defaultAnomalies.get(0).path("aggregateId").asText()).isEqualTo("PG-AGG");
        assertThat(mysqlAnomalies.get(0).path("aggregateId").asText()).isEqualTo("MYSQL-AGG");
    }

    @Test
    void websocketStreamsMappedSourceAndShowsPlaceholderForSourceWithoutStream() throws Exception {
        TestEventSourcePlugin primary = new TestEventSourcePlugin("pg-primary", List.of());
        TestEventSourcePlugin legacy = new TestEventSourcePlugin("mysql-alt", List.of());
        TestStreamAdapterPlugin pgStream = new TestStreamAdapterPlugin();
        startServer(primary, legacy, pgStream, Map.of("pg-primary", "pg-stream", "mysql-alt", ""));

        CompletableFuture<String> liveMessage = new CompletableFuture<>();
        WebSocket liveSocket = openSocket("/ws/live?source=pg-primary", new MatchingMessageListener(
                liveMessage,
                payload -> payload.contains("\"eventType\":\"LiveArrived\"")));
        waitForSubscriber(pgStream);
        pgStream.emit(new Event(
                "evt-live",
                "PG-AGG",
                "BankAccount",
                99,
                "LiveArrived",
                JSON.readTree("{\"note\":\"streamed\"}"),
                JSON.readTree("{\"source\":\"pg\"}"),
                Instant.parse("2026-03-24T12:00:00Z"),
                99
        ));

        String streamed = liveMessage.get(5, TimeUnit.SECONDS);
        assertThat(streamed).contains("\"eventType\":\"LiveArrived\"");

        CompletableFuture<String> placeholderMessage = new CompletableFuture<>();
        WebSocket noStreamSocket = openSocket("/ws/live?source=mysql-alt", new MatchingMessageListener(
                placeholderMessage,
                payload -> payload.contains("\"type\":\"NO_LIVE_STREAM\"")));
        String placeholder = placeholderMessage.get(5, TimeUnit.SECONDS);
        assertThat(placeholder).contains("\"type\":\"NO_LIVE_STREAM\"");
        assertThat(placeholder).contains("\"source\":\"mysql-alt\"");

        liveSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        noStreamSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void websocketBurstKeepsLatestBufferedEventsForSlowClients() throws Exception {
        TestEventSourcePlugin primary = new TestEventSourcePlugin("pg-primary", List.of());
        TestEventSourcePlugin legacy = new TestEventSourcePlugin("mysql-alt", List.of());
        TestStreamAdapterPlugin pgStream = new TestStreamAdapterPlugin();
        startServer(primary, legacy, pgStream, Map.of("pg-primary", "pg-stream"));

        CompletableFuture<List<String>> burstMessages = new CompletableFuture<>();
        WebSocket burstSocket = openSocket("/ws/live?source=pg-primary", new CollectingListener(
                burstMessages,
                payloads -> payloads.stream().anyMatch(payload -> payload.contains("\"sequenceNumber\":260"))));

        waitForSubscriber(pgStream);

        for (int sequence = 1; sequence <= 260; sequence++) {
            pgStream.emit(new Event(
                    "evt-live-" + sequence,
                    "PG-AGG",
                    "BankAccount",
                    sequence,
                    "LiveArrived",
                    JSON.readTree("{\"sequence\":" + sequence + "}"),
                    JSON.readTree("{\"source\":\"pg\"}"),
                    Instant.parse("2026-03-24T12:00:00Z").plusSeconds(sequence),
                    sequence
            ));
        }

        List<String> messages = burstMessages.get(10, TimeUnit.SECONDS);
        List<Long> sequences = messages.stream()
                .map(payload -> {
                    try {
                        return JSON.readTree(payload).path("sequenceNumber").asLong();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .toList();

        assertThat(sequences).isNotEmpty();
        assertThat(sequences.get(sequences.size() - 1)).isEqualTo(260);
        assertThat(sequences).allMatch(sequence -> sequence > 0 && sequence <= 260);

        burstSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private void startServer(
            TestEventSourcePlugin primary,
            TestEventSourcePlugin legacy,
            TestStreamAdapterPlugin stream,
            Map<String, String> bindings) throws Exception {
        pluginManager = new PluginManager(30);
        pluginManager.registerEventSource("pg-primary", primary, Map.of());
        pluginManager.registerEventSource("mysql-alt", legacy, Map.of());
        if (stream != null) {
            pluginManager.registerStreamAdapter("pg-stream", stream, Map.of("bootstrapServers", "unused", "topic", "unused"));
        }

        EventStoreReader defaultReader = primary;
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

        server = new EventLensServer(
                config,
                defaultReader,
                replayEngine,
                reducers,
                pluginManager,
                "pg-primary",
                bisectEngine,
                anomalyDetector,
                exportEngine,
                diffEngine,
                bindings
        );
        server.start();
    }

    private JsonNode getJson(String pathAndQuery) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getApp().port() + pathAndQuery))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private WebSocket openSocket(String pathAndQuery, CompletableFuture<String> firstMessage) {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create("ws://localhost:" + server.getApp().port() + pathAndQuery),
                        new MatchingMessageListener(firstMessage, payload -> true))
                .join();
    }

    private WebSocket openSocket(String pathAndQuery, WebSocket.Listener listener) {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + server.getApp().port() + pathAndQuery), listener)
                .join();
    }

    private void waitForSubscriber(TestStreamAdapterPlugin plugin) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!plugin.hasSubscriber() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        assertThat(plugin.hasSubscriber()).isTrue();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static List<StoredEvent> anomalyEvents(String aggregateId) {
        List<StoredEvent> events = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            events.add(new StoredEvent(
                    "evt-" + aggregateId + "-" + i,
                    aggregateId,
                    "BankAccount",
                    i,
                    i == 1 ? "AccountCreated" : "MoneyDeposited",
                    "{\"balance\":" + i + "}",
                    "{}",
                    Instant.parse("2026-03-24T10:00:00Z").plusSeconds(i),
                    i
            ));
        }
        return events;
    }

    private static final class TestEventSourcePlugin implements EventSourcePlugin, EventStoreReader {
        private final String instanceId;
        private final List<StoredEvent> events;

        private TestEventSourcePlugin(String instanceId, List<StoredEvent> events) {
            this.instanceId = instanceId;
            this.events = events.stream()
                    .sorted(Comparator.comparingLong(StoredEvent::sequenceNumber))
                    .toList();
        }

        @Override
        public String typeId() {
            return "test-source";
        }

        @Override
        public String displayName() {
            return "Test Source " + instanceId;
        }

        @Override
        public void initialize(String instanceId, Map<String, Object> config) {
        }

        @Override
        public EventQueryResult query(EventQuery query) {
            return new EventQueryResult(List.of(), false, null);
        }

        @Override
        public HealthStatus healthCheck() {
            return HealthStatus.up();
        }

        @Override
        public List<StoredEvent> getEvents(String aggregateId) {
            return events.stream().filter(event -> event.aggregateId().equals(aggregateId)).toList();
        }

        @Override
        public List<StoredEvent> getEvents(String aggregateId, int limit, int offset) {
            return getEvents(aggregateId).stream().skip(offset).limit(limit).toList();
        }

        @Override
        public List<StoredEvent> getEventsAfterSequence(String aggregateId, long afterSequence, int limit) {
            return getEvents(aggregateId).stream()
                    .filter(event -> event.sequenceNumber() > afterSequence)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
            return getEvents(aggregateId).stream().filter(event -> event.sequenceNumber() <= maxSequence).toList();
        }

        @Override
        public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
            return events.stream()
                    .filter(event -> event.aggregateType().equals(aggregateType))
                    .map(StoredEvent::aggregateId)
                    .distinct()
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StoredEvent> getRecentEvents(int limit) {
            return events.stream()
                    .sorted(Comparator.comparingLong(StoredEvent::globalPosition).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
            return events.stream()
                    .filter(event -> event.globalPosition() > globalPosition)
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countEvents(String aggregateId) {
            return getEvents(aggregateId).size();
        }

        @Override
        public List<String> getAggregateTypes() {
            return events.stream().map(StoredEvent::aggregateType).distinct().toList();
        }

        @Override
        public List<String> searchAggregates(String query, int limit) {
            return events.stream()
                    .map(StoredEvent::aggregateId)
                    .filter(id -> id.contains(query))
                    .distinct()
                    .limit(limit)
                    .toList();
        }
    }

    private static final class TestStreamAdapterPlugin implements StreamAdapterPlugin {
        private final AtomicReference<java.util.function.Consumer<Event>> listener = new AtomicReference<>();

        @Override
        public String typeId() {
            return "test-stream";
        }

        @Override
        public String displayName() {
            return "Test Stream";
        }

        @Override
        public void initialize(String instanceId, Map<String, Object> config) {
        }

        @Override
        public void subscribe(java.util.function.Consumer<Event> listener) {
            this.listener.set(listener);
        }

        @Override
        public void unsubscribe() {
            listener.set(null);
        }

        @Override
        public HealthStatus healthCheck() {
            return HealthStatus.up();
        }

        private boolean hasSubscriber() {
            return listener.get() != null;
        }

        private void emit(Event event) {
            var activeListener = listener.get();
            if (activeListener != null) {
                activeListener.accept(event);
            }
        }
    }

    private static final class MatchingMessageListener implements WebSocket.Listener {
        private final CompletableFuture<String> matchingMessage;
        private final Predicate<String> predicate;
        private final StringBuilder text = new StringBuilder();

        private MatchingMessageListener(CompletableFuture<String> matchingMessage, Predicate<String> predicate) {
            this.matchingMessage = matchingMessage;
            this.predicate = predicate;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                String payload = text.toString();
                text.setLength(0);
                if (!matchingMessage.isDone() && predicate.test(payload)) {
                    matchingMessage.complete(payload);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class CollectingListener implements WebSocket.Listener {
        private final CompletableFuture<List<String>> messagesFuture;
        private final Predicate<List<String>> completionPredicate;
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final StringBuilder text = new StringBuilder();

        private CollectingListener(CompletableFuture<List<String>> messagesFuture, Predicate<List<String>> completionPredicate) {
            this.messagesFuture = messagesFuture;
            this.completionPredicate = completionPredicate;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
                if (!messagesFuture.isDone() && completionPredicate.test(List.copyOf(messages))) {
                    messagesFuture.complete(List.copyOf(messages));
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
