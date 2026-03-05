package io.eventlens.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.*;

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
 * blank screen.
 */
public class LiveTailWebSocket {

    private static final Logger log = LoggerFactory.getLogger(LiveTailWebSocket.class);

    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final EventStoreReader reader;

    public LiveTailWebSocket(EventStoreReader reader) {
        this.reader = reader;
    }

    /**
     * Register the WebSocket route and start polling (Kafka is wired externally).
     */
    public void configure(Javalin app) {
        app.ws("/ws/live", ws -> {
            ws.onConnect(ctx -> {
                sessions.add(ctx);
                log.debug("WebSocket client connected: {}", ctx.sessionId());

                // Backfill last 20 events
                var recent = reader.getRecentEvents(20);
                for (var event : recent) {
                    sendTo(ctx, event);
                }
            });

            ws.onClose(ctx -> {
                sessions.remove(ctx);
                log.debug("WebSocket client disconnected: {}", ctx.sessionId());
            });

            ws.onError(ctx -> {
                sessions.remove(ctx);
            });
        });
    }

    /** Broadcast a new event to all connected clients. */
    public void broadcast(StoredEvent event) {
        if (sessions.isEmpty())
            return;
        sessions.removeIf(session -> {
            try {
                sendTo(session, event);
                return false;
            } catch (Exception e) {
                log.debug("Removed dead WebSocket session: {}", session.sessionId());
                return true;
            }
        });
    }

    /**
     * Start polling PostgreSQL for new events (used when Kafka is not configured).
     * Polls every 1 second using a virtual thread scheduler.
     */
    public void startPolling() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("eventlens-poll").factory());

        final long[] lastPosition = { 0 };

        scheduler.scheduleAtFixedRate(() -> {
            try {
                var events = reader.getEventsAfter(lastPosition[0], 50);
                for (var event : events) {
                    broadcast(event);
                    if (event.globalPosition() > lastPosition[0]) {
                        lastPosition[0] = event.globalPosition();
                    }
                }
            } catch (Exception e) {
                log.warn("Live tail polling error: {}", e.getMessage());
            }
        }, 0, 1, TimeUnit.SECONDS);

        log.info("PostgreSQL polling live tail started (fallback mode)");
    }

    private void sendTo(WsContext ctx, StoredEvent event) {
        try {
            ctx.send(mapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
