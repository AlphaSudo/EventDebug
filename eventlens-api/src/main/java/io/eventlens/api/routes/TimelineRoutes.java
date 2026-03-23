package io.eventlens.api.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.eventlens.core.InputValidator;
import io.eventlens.api.http.ConditionalGet;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.pagination.CursorCodec;
import io.eventlens.core.model.AggregateTimeline;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.pii.PiiMasker;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

/**
 * Timeline, replay, and state transition endpoints.
 *
 * <p>v2 additions:
 * <ul>
 *   <li><b>1.8</b> — emits {@link AuditEvent#ACTION_VIEW_TIMELINE} on every
 *       timeline fetch.</li>
 *   <li><b>1.9</b> — passes event payloads through {@link PiiMasker} before
 *       returning them to the client.</li>
 * </ul>
 */
public class TimelineRoutes {

    /** Hard cap on any client-supplied limit to prevent unbounded DB queries. */
    private static final int MAX_LIMIT = 1_000;

    private final ReplayEngine replayEngine;
    private final AuditLogger  auditLogger;
    private final PiiMasker    piiMasker;
    private final ObjectMapper mapper;

    public TimelineRoutes(ReplayEngine replayEngine,
                          AuditLogger auditLogger,
                          PiiMasker piiMasker) {
        this.replayEngine = replayEngine;
        this.auditLogger  = auditLogger;
        this.piiMasker    = piiMasker;
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** GET /api/aggregates/{id}/timeline */
    public void getTimeline(Context ctx) {
        String id    = InputValidator.validateAggregateId(ctx.pathParam("id"));
        int    limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 500, MAX_LIMIT),
                MAX_LIMIT);
        String cursorParam = ctx.queryParam("cursor");
        int offset = InputValidator.validateOffset(ctx.queryParam("offset"));

        AggregateTimeline timeline;
        boolean hasMore = false;
        String nextCursor = null;

        if (cursorParam != null && !cursorParam.isBlank()) {
            var cursor = CursorCodec.decode(cursorParam);
            var page = replayEngine.buildTimelineAfter(id, limit, cursor.sequence());
            timeline = new AggregateTimeline(page.aggregateId(), page.aggregateType(), page.events(), page.totalEvents());
            hasMore = page.hasMore();
            if (!page.events().isEmpty()) {
                var last = page.events().getLast();
                nextCursor = CursorCodec.encode(last.sequenceNumber(), last.timestamp());
            }
        } else {
            timeline = replayEngine.buildTimeline(id, limit, offset);
        }

        // 1.9 — apply PII masking on each event payload
        AggregateTimeline masked = maskTimeline(timeline);

        // 1.8 — audit
        auditLogger.log(AuditEvent.builder()
                .action(AuditEvent.ACTION_VIEW_TIMELINE)
                .resourceType(AuditEvent.RT_AGGREGATE)
                .resourceId(id)
                .userId(userId(ctx))
                .authMethod(authMethod(ctx))
                .clientIp(clientIp(ctx))
                .requestId(requestId(ctx))
                .userAgent(ctx.userAgent())
                .details(Map.of(
                        "limit", limit,
                        "offset", offset,
                        "cursor", cursorParam != null ? cursorParam : "",
                        "eventCount", masked.events().size()))
                .build());

        Object response = nextCursor != null
                ? Map.of(
                "aggregateId", masked.aggregateId(),
                "aggregateType", masked.aggregateType(),
                "events", masked.events(),
                "totalEvents", masked.totalEvents(),
                "pagination", Map.of(
                        "limit", limit,
                        "hasMore", hasMore,
                        "nextCursor", nextCursor
                ))
                : masked;
        ConditionalGet.json(ctx, response);
    }

    /** GET /api/aggregates/{id}/replay */
    public void replay(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        ctx.json(replayEngine.replayFull(id));
    }

    /** GET /api/aggregates/{id}/replay/{seq} */
    public void replayTo(Context ctx) {
        String id  = InputValidator.validateAggregateId(ctx.pathParam("id"));
        long   seq = Long.parseLong(ctx.pathParam("seq"));
        ctx.json(replayEngine.replayTo(id, seq));
    }

    /** GET /api/aggregates/{id}/transitions */
    public void transitions(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        ctx.json(replayEngine.replayFull(id));
    }

    // ── PII masking ──────────────────────────────────────────────────────────

    /**
     * Returns a new {@link AggregateTimeline} whose events have their payload
     * fields masked.  If masking is disabled the original object is returned
     * unchanged (no copy).
     */
    private AggregateTimeline maskTimeline(AggregateTimeline timeline) {
        if (timeline == null || timeline.events() == null) return timeline;

        List<StoredEvent> maskedEvents = timeline.events().stream()
                .map(this::maskEvent)
                .toList();

        return new AggregateTimeline(
                timeline.aggregateId(),
                timeline.aggregateType(),
                maskedEvents,
                timeline.totalEvents()
        );
    }

    private StoredEvent maskEvent(StoredEvent event) {
        if (event == null || event.payload() == null) return event;
        try {
            String raw = event.payload();
            JsonNode tree = mapper.readTree(raw);
            JsonNode maskedTree = piiMasker.mask(tree, raw);
            if (maskedTree == tree) return event; // nothing changed — return original
            return new StoredEvent(
                    event.eventId(),
                    event.aggregateId(),
                    event.aggregateType(),
                    event.sequenceNumber(),
                    event.eventType(),
                    mapper.writeValueAsString(maskedTree),
                    event.metadata(),
                    event.timestamp(),
                    event.globalPosition()
            );
        } catch (Exception e) {
            // If JSON parsing fails, return the event unmasked rather than failing the request
            return event;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String userId(Context ctx) {
        String v = ctx.attribute("auditUserId");
        return v != null ? v : "anonymous";
    }

    private static String authMethod(Context ctx) {
        String v = ctx.attribute("auditAuthMethod");
        return v != null ? v : "anonymous";
    }

    private static String clientIp(Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int c = xff.indexOf(',');
            return (c >= 0 ? xff.substring(0, c) : xff).trim();
        }
        String xri = ctx.header("X-Real-IP");
        return xri != null && !xri.isBlank() ? xri.trim() : ctx.ip();
    }

    private static String requestId(Context ctx) {
        String v = ctx.attribute("requestId");
        return v != null ? v : "unknown";
    }
}
