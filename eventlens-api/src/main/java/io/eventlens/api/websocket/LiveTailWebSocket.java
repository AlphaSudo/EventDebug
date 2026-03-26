package io.eventlens.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.spi.Event;
import io.eventlens.spi.StreamAdapterPlugin;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket live tail with bounded buffering and batched flushes.
 */
public class LiveTailWebSocket {

    private static final Logger log = LoggerFactory.getLogger(LiveTailWebSocket.class);
    private static final int MAX_CONNECTIONS = 500;
    private static final int BACKFILL_EVENT_COUNT = 100;
    private static final int MAX_BUFFERED_MESSAGES = 200;
    private static final int MAX_MESSAGES_PER_FLUSH = 50;
    private static final long FLUSH_DELAY_MS = 100;

    private final Map<String, Set<WsContext>> sessionsBySource = new ConcurrentHashMap<>();
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();
    private final Set<String> subscribedSources = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final SourceRegistry sourceRegistry;
    private final PluginManager pluginManager;
    private final AuditLogger auditLogger;
    private final String defaultSourceId;
    private final Map<String, String> sourceStreamBindings;
    private final ExecutorService backfillExecutor = Executors.newCachedThreadPool(
            Thread.ofVirtual().name("eventlens-backfill-", 0).factory());
    private final ScheduledExecutorService flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "eventlens-live-flush");
        thread.setDaemon(true);
        return thread;
    });

    public LiveTailWebSocket(
            SourceRegistry sourceRegistry,
            PluginManager pluginManager,
            AuditLogger auditLogger,
            String defaultSourceId,
            Map<String, String> sourceStreamBindings) {
        this.sourceRegistry = sourceRegistry;
        this.pluginManager = pluginManager;
        this.auditLogger = auditLogger;
        this.defaultSourceId = defaultSourceId;
        this.sourceStreamBindings = sourceStreamBindings == null ? Map.of() : Map.copyOf(sourceStreamBindings);
    }

    public void configureHandlers(WsConfig ws) {
        ws.onConnect(ctx -> {
            if (totalSessions() >= MAX_CONNECTIONS) {
                log.warn("WebSocket connection rejected: max connections ({}) reached", MAX_CONNECTIONS);
                ctx.closeSession(1008, "Too many connections");
                return;
            }

            String sourceId = requestedSource(ctx);
            ctx.attribute("eventlensSourceId", sourceId);
            ctx.enableAutomaticPings();
            sessionsBySource.computeIfAbsent(sourceId, ignored -> ConcurrentHashMap.newKeySet()).add(ctx);
            sessionStates.put(ctx.sessionId(), new SessionState(ctx));
            EventLensMetrics.setWebsocketConnections(totalSessions());
            log.debug("WebSocket client connected: {} on source {} ({} active)", ctx.sessionId(), sourceId, totalSessions());

            auditLogger.log(AuditEvent.builder()
                    .action(AuditEvent.ACTION_VIEW_LIVE_STREAM)
                    .resourceType(AuditEvent.RT_STREAM)
                    .userId("anonymous")
                    .authMethod("anonymous")
                    .clientIp(extractIp(ctx))
                    .requestId("ws-" + ctx.sessionId())
                    .userAgent(ctx.header("User-Agent"))
                    .details(Map.of(
                            "sessionId", ctx.sessionId(),
                            "activeSessions", totalSessions(),
                            "source", sourceId))
                    .build());

            Optional<StreamAdapterPlugin> streamAdapter = streamForSource(sourceId);
            if (streamAdapter.isPresent()) {
                ensureSubscribed(sourceId, streamAdapter.get());
                backfillExecutor.submit(() -> backfill(ctx, sourceId));
            } else {
                enqueueControl(ctx, new ControlMessage("NO_LIVE_STREAM", sourceId));
            }
        });

        ws.onClose(this::removeSession);
        ws.onError(this::removeSession);
    }

    private void backfill(WsContext ctx, String sourceId) {
        try {
            Thread.sleep(250);
            var recent = sourceRegistry.resolve(sourceId).reader().getRecentEvents(BACKFILL_EVENT_COUNT);
            for (var event : recent) {
                if (!enqueue(ctx, event)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Backfill failed for {} on source {}: {}", ctx.sessionId(), sourceId, e.getMessage());
        }
    }

    private void ensureSubscribed(String sourceId, StreamAdapterPlugin adapter) {
        if (!subscribedSources.add(sourceId)) {
            return;
        }
        adapter.subscribe(event -> broadcast(sourceId, toStoredEvent(event)));
    }

    public void broadcast(String sourceId, StoredEvent event) {
        Set<WsContext> sessions = sessionsBySource.get(sourceId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        sessions.removeIf(session -> !enqueue(session, event));
        EventLensMetrics.setWebsocketConnections(totalSessions());
    }

    private boolean enqueue(WsContext ctx, Object payload) {
        SessionState state = sessionStates.get(ctx.sessionId());
        if (state == null || state.closed.get()) {
            return false;
        }
        try {
            String json = mapper.writeValueAsString(payload);
            while (state.queue.size() >= MAX_BUFFERED_MESSAGES) {
                state.queue.pollFirst();
                state.droppedCount++;
            }
            state.queue.addLast(json);
            scheduleFlush(state);
            return true;
        } catch (Exception e) {
            log.debug("WebSocket enqueue failed for {}: {}", ctx.sessionId(), e.getMessage());
            return false;
        }
    }

    private void enqueueControl(WsContext ctx, ControlMessage message) {
        enqueue(ctx, message);
    }

    private void scheduleFlush(SessionState state) {
        if (!state.flushScheduled.compareAndSet(false, true)) {
            return;
        }
        flushExecutor.schedule(() -> flush(state), FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void flush(SessionState state) {
        state.flushScheduled.set(false);
        if (state.closed.get()) {
            return;
        }
        int sent = 0;
        while (sent < MAX_MESSAGES_PER_FLUSH) {
            String next = state.queue.pollFirst();
            if (next == null) {
                break;
            }
            if (!trySendRaw(state.ctx, next)) {
                removeSession(state.ctx);
                return;
            }
            sent++;
        }
        if (!state.queue.isEmpty()) {
            scheduleFlush(state);
        }
    }

    private Optional<StreamAdapterPlugin> streamForSource(String sourceId) {
        String explicit = sourceStreamBindings.get(sourceId);
        if (explicit != null) {
            if (explicit.isBlank()) {
                return Optional.empty();
            }
            return pluginManager.getStreamAdapter(explicit);
        }

        Optional<StreamAdapterPlugin> sameId = pluginManager.getStreamAdapter(sourceId);
        if (sameId.isPresent()) {
            return sameId;
        }

        if (defaultSourceId.equals(sourceId)) {
            return pluginManager.getFirstReadyStreamAdapter();
        }

        return Optional.empty();
    }

    private String requestedSource(WsContext ctx) {
        String requested = ctx.queryParam("source");
        if (requested == null || requested.isBlank()) {
            return defaultSourceId;
        }
        return sourceRegistry.resolve(requested).id();
    }

    private void removeSession(WsContext ctx) {
        Object sourceAttr = ctx.attribute("eventlensSourceId");
        if (sourceAttr instanceof String sourceId) {
            Set<WsContext> sessions = sessionsBySource.get(sourceId);
            if (sessions != null) {
                sessions.remove(ctx);
                if (sessions.isEmpty()) {
                    sessionsBySource.remove(sourceId);
                }
            }
        } else {
            sessionsBySource.values().forEach(sessions -> sessions.remove(ctx));
        }
        SessionState state = sessionStates.remove(ctx.sessionId());
        if (state != null) {
            state.closed.set(true);
            state.queue.clear();
        }
        EventLensMetrics.setWebsocketConnections(totalSessions());
        log.debug("WebSocket session ended: {}", ctx.sessionId());
    }

    private int totalSessions() {
        return sessionsBySource.values().stream().mapToInt(Set::size).sum();
    }

    private boolean trySendRaw(WsContext ctx, String json) {
        try {
            ctx.send(json);
            return true;
        } catch (Exception e) {
            log.debug("WebSocket send failed for {}: {}", ctx.sessionId(), e.getMessage());
            return false;
        }
    }

    private StoredEvent toStoredEvent(Event event) {
        return new StoredEvent(
                event.eventId(),
                event.aggregateId(),
                event.aggregateType(),
                event.sequenceNumber(),
                event.eventType(),
                io.eventlens.core.JsonUtil.toJson(event.payload()),
                io.eventlens.core.JsonUtil.toJson(event.metadata()),
                event.timestamp(),
                event.globalPosition());
    }

    private static String extractIp(WsContext ctx) {
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int c = xff.indexOf(',');
            return (c >= 0 ? xff.substring(0, c) : xff).trim();
        }
        String xri = ctx.header("X-Real-IP");
        return xri != null && !xri.isBlank() ? xri.trim() : "unknown";
    }

    private record ControlMessage(String type, String source) {
    }

    private static final class SessionState {
        private final WsContext ctx;
        private final ConcurrentLinkedDeque<String> queue = new ConcurrentLinkedDeque<>();
        private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile long droppedCount = 0;

        private SessionState(WsContext ctx) {
            this.ctx = ctx;
        }
    }
}
