package io.eventlens.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket live tail — streams events to connected browser clients in
 * real-time.
 *
 * <p>
 * Two modes:
 * <ul>
 * <li><b>Kafka mode</b>: forwards events from KafkaLiveTail via listener
 * callback</li>
 * <li><b>Poll mode</b>: falls back to polling PostgreSQL every second when
 * Kafka is disabled</li>
 * </ul>
 *
 * <p>
 * On connect: sends the last 20 events as backfill so clients don't join a
 * blank screen. Backfill is sent asynchronously to avoid blocking the
 * Jetty onConnect handler thread.
 *
 * <p>v2 — emits {@link AuditEvent#ACTION_VIEW_LIVE_STREAM} on WebSocket
 * connect (1.8 Audit Logging).
 */
public class LiveTailWebSocket {

    private static final Logger log = LoggerFactory.getLogger(LiveTailWebSocket.class);
    private static final int MAX_CONNECTIONS = 500;

    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final EventStoreReader reader;
    private final AuditLogger      auditLogger;
    private final ExecutorService backfillExecutor = Executors.newCachedThreadPool(
            Thread.ofVirtual().name("eventlens-backfill-", 0).factory());

    public LiveTailWebSocket(EventStoreReader reader, AuditLogger auditLogger) {
        this.reader      = reader;
        this.auditLogger = auditLogger;
    }

    /**
     * Register the WebSocket route and start polling (Kafka is wired externally).
     */
    public void configure(Javalin app) {
        app.ws("/ws/live", ws -> {
            ws.onConnect(ctx -> {
                if (sessions.size() >= MAX_CONNECTIONS) {
                    log.warn("WebSocket connection rejected: max connections ({}) reached", MAX_CONNECTIONS);
                    ctx.session.close(1008, "Too many connections");
                    return;
                }
                ctx.enableAutomaticPings(15, TimeUnit.SECONDS);
                sessions.add(ctx);
                log.debug("WebSocket client connected: {} ({} active)", ctx.sessionId(), sessions.size());

                // 1.8 — audit live-stream connection
                String userAgent = ctx.header("User-Agent");
                auditLogger.log(AuditEvent.builder()
                        .action(AuditEvent.ACTION_VIEW_LIVE_STREAM)
                        .resourceType(AuditEvent.RT_STREAM)
                        .userId("anonymous")          // WS upgrade doesn't carry the ctx attributes
                        .authMethod("anonymous")
                        .clientIp(extractIp(ctx))
                        .requestId("ws-" + ctx.sessionId())
                        .userAgent(userAgent)
                        .details(Map.of("sessionId", ctx.sessionId(),
                                "activeSessions", sessions.size()))
                        .build());

                backfillExecutor.submit(() -> backfill(ctx));
            });

            ws.onClose(ctx -> {
                sessions.remove(ctx);
                log.debug("WebSocket client disconnected: {}", ctx.sessionId());
            });

            ws.onError(ctx -> {
                sessions.remove(ctx);
                log.debug("WebSocket error for session: {}", ctx.sessionId());
            });
        });
    }

    private void backfill(WsContext ctx) {
        try {
            Thread.sleep(250);
            if (!ctx.session.isOpen()) return;
            var recent = reader.getRecentEvents(20);
            for (var event : recent) {
                if (!ctx.session.isOpen()) break;
                trySend(ctx, event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Backfill failed for {}: {}", ctx.sessionId(), e.getMessage());
        }
    }

    /** Broadcast a new event to all connected clients. */
    public void broadcast(StoredEvent event) {
        if (sessions.isEmpty())
            return;
        sessions.removeIf(session -> {
            if (!session.session.isOpen()) return true;
            return !trySend(session, event);
        });
    }

    /**
     * Start polling PostgreSQL for new events (used when Kafka is not configured).
     * Polls every 1 second using a virtual thread scheduler.
     */
    public void startPolling() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("eventlens-poll").factory());

        final AtomicLong lastPosition = new AtomicLong(0);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                var events = reader.getEventsAfter(lastPosition.get(), 50);
                for (var event : events) {
                    broadcast(event);
                    if (event.globalPosition() > lastPosition.get()) {
                        lastPosition.set(event.globalPosition());
                    }
                }
            } catch (Exception e) {
                log.warn("Live tail polling error: {}", e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);

        log.info("PostgreSQL polling live tail started (fallback mode)");
    }

    /**
     * Non-throwing send. Returns true if the message was sent successfully.
     */
    private boolean trySend(WsContext ctx, StoredEvent event) {
        if (!ctx.session.isOpen()) return false;
        try {
            ctx.send(mapper.writeValueAsString(event));
            return true;
        } catch (Exception e) {
            log.debug("WebSocket send failed for {}: {}", ctx.sessionId(), e.getMessage());
            return false;
        }
    }

    private static String extractIp(WsContext ctx) {
        // WsContext exposes the underlying HTTP upgrade headers
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int c = xff.indexOf(',');
            return (c >= 0 ? xff.substring(0, c) : xff).trim();
        }
        String xri = ctx.header("X-Real-IP");
        return xri != null && !xri.isBlank() ? xri.trim() : "unknown";
    }
}
