package io.eventlens.api.routes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.eventlens.api.cache.QueryResultCache;
import io.eventlens.api.http.ConditionalGet;
import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.core.InputValidator;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.model.AggregateTimeline;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.pagination.CursorCodec;
import io.eventlens.core.pii.PiiMasker;
import io.eventlens.core.security.Permission;
import io.javalin.http.Context;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Timeline, replay, and state transition endpoints.
 */
public class TimelineRoutes {

    private static final int MAX_LIMIT = 1_000;

    private final SourceRegistry sourceRegistry;
    private final AuditLogger auditLogger;
    private final PiiMasker piiMasker;
    private final QueryResultCache queryCache;
    private final Duration timelineTtl;
    private final ObjectMapper mapper;
    private final RouteAuthorizer routeAuthorizer;

    public TimelineRoutes(
            SourceRegistry sourceRegistry,
            AuditLogger auditLogger,
            PiiMasker piiMasker,
            QueryResultCache queryCache,
            Duration timelineTtl,
            RouteAuthorizer routeAuthorizer) {
        this.sourceRegistry = sourceRegistry;
        this.auditLogger = auditLogger;
        this.piiMasker = piiMasker;
        this.queryCache = queryCache;
        this.timelineTtl = timelineTtl;
        this.routeAuthorizer = routeAuthorizer;
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void getTimeline(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        int limit = Math.min(
                InputValidator.validateLimit(ctx.queryParam("limit"), 500, MAX_LIMIT),
                MAX_LIMIT);
        String cursorParam = ctx.queryParam("cursor");
        int offset = InputValidator.validateOffset(ctx.queryParam("offset"));
        String fields = normalizeFields(ctx.queryParam("fields"));
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_TIMELINE, source.id(), null)) {
            return;
        }

        TimelineEnvelope envelope = queryCache.getOrCompute(
                "timeline",
                source.id() + "|" + id + "|" + limit + "|" + offset + "|" + fields + "|" + (cursorParam == null ? "" : cursorParam),
                timelineTtl,
                () -> buildTimelineEnvelope(source, id, limit, offset, cursorParam, fields));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_TIMELINE, source.id(), envelope.timeline().aggregateType())) {
            return;
        }

        auditLogger.log(SecurityContext.audit(ctx)
                .action(AuditEvent.ACTION_VIEW_TIMELINE)
                .resourceType(AuditEvent.RT_AGGREGATE)
                .resourceId(id)
                .details(Map.of(
                        "limit", limit,
                        "offset", offset,
                        "cursor", cursorParam != null ? cursorParam : "",
                        "fields", fields,
                        "source", source.id(),
                        "eventCount", envelope.timeline().events().size()))
                .build());

        if (envelope.nextCursor() != null) {
            ConditionalGet.json(ctx, Map.of(
                    "aggregateId", envelope.timeline().aggregateId(),
                    "aggregateType", envelope.timeline().aggregateType(),
                    "events", envelope.timeline().events(),
                    "totalEvents", envelope.timeline().totalEvents(),
                    "pagination", Map.of(
                            "limit", limit,
                            "hasMore", envelope.hasMore(),
                            "nextCursor", envelope.nextCursor()
                    )
            ));
            return;
        }

        ConditionalGet.json(ctx, envelope.timeline());
    }

    public void replay(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_REPLAY, source.id(), null)) {
            return;
        }
        var transitions = source.replayEngine().replayFull(id);
        String aggregateType = transitions.isEmpty() ? null : transitions.getFirst().event().aggregateType();
        if (!routeAuthorizer.require(ctx, Permission.VIEW_REPLAY, source.id(), aggregateType)) {
            return;
        }
        ctx.json(transitions);
    }

    public void replayTo(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        long seq = Long.parseLong(ctx.pathParam("seq"));
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_REPLAY, source.id(), null)) {
            return;
        }
        var replay = source.replayEngine().replayTo(id, seq);
        String aggregateType = replay.transitions().isEmpty() ? null : replay.transitions().getFirst().event().aggregateType();
        if (!routeAuthorizer.require(ctx, Permission.VIEW_REPLAY, source.id(), aggregateType)) {
            return;
        }
        ctx.json(replay);
    }

    public void transitions(Context ctx) {
        String id = InputValidator.validateAggregateId(ctx.pathParam("id"));
        var source = sourceRegistry.resolve(ctx.queryParam("source"));
        if (!routeAuthorizer.require(ctx, Permission.VIEW_REPLAY, source.id(), null)) {
            return;
        }
        var transitions = source.replayEngine().replayFull(id);
        String aggregateType = transitions.isEmpty() ? null : transitions.getFirst().event().aggregateType();
        if (!routeAuthorizer.require(ctx, Permission.VIEW_REPLAY, source.id(), aggregateType)) {
            return;
        }
        ctx.json(transitions);
    }

    private TimelineEnvelope buildTimelineEnvelope(
            SourceRegistry.ResolvedSource source,
            String aggregateId,
            int limit,
            int offset,
            String cursorParam,
            String fields) {
        AggregateTimeline timeline;
        boolean hasMore = false;
        String nextCursor = null;

        if (cursorParam != null && !cursorParam.isBlank()) {
            var cursor = CursorCodec.decode(cursorParam);
            var page = source.replayEngine().buildTimelineAfter(aggregateId, limit, cursor.sequence());
            timeline = new AggregateTimeline(page.aggregateId(), page.aggregateType(), page.events(), page.totalEvents());
            hasMore = page.hasMore();
            if (!page.events().isEmpty()) {
                var last = page.events().getLast();
                nextCursor = CursorCodec.encode(last.sequenceNumber(), last.timestamp());
            }
        } else {
            timeline = source.replayEngine().buildTimeline(aggregateId, limit, offset);
        }

        AggregateTimeline masked = maskTimeline(timeline);
        AggregateTimeline shaped = "metadata".equals(fields) ? metadataOnly(masked) : masked;
        return new TimelineEnvelope(shaped, hasMore, nextCursor);
    }

    private String normalizeFields(String fields) {
        if (fields == null || fields.isBlank()) {
            return "full";
        }
        if (!fields.equals("full") && !fields.equals("metadata")) {
            throw new IllegalArgumentException("Unsupported fields value: " + fields);
        }
        return fields;
    }

    private AggregateTimeline metadataOnly(AggregateTimeline timeline) {
        List<StoredEvent> events = timeline.events().stream()
                .map(event -> new StoredEvent(
                        event.eventId(),
                        event.aggregateId(),
                        event.aggregateType(),
                        event.sequenceNumber(),
                        event.eventType(),
                        null,
                        event.metadata(),
                        event.timestamp(),
                        event.globalPosition()))
                .toList();

        return new AggregateTimeline(
                timeline.aggregateId(),
                timeline.aggregateType(),
                events,
                timeline.totalEvents());
    }

    private AggregateTimeline maskTimeline(AggregateTimeline timeline) {
        if (timeline == null || timeline.events() == null) {
            return timeline;
        }

        List<StoredEvent> maskedEvents = timeline.events().stream()
                .map(this::maskEvent)
                .toList();

        return new AggregateTimeline(
                timeline.aggregateId(),
                timeline.aggregateType(),
                maskedEvents,
                timeline.totalEvents());
    }

    private StoredEvent maskEvent(StoredEvent event) {
        if (event == null || event.payload() == null) {
            return event;
        }
        try {
            String raw = event.payload();
            JsonNode tree = mapper.readTree(raw);
            JsonNode maskedTree = piiMasker.mask(tree, raw);
            if (maskedTree == tree) {
                return event;
            }
            return new StoredEvent(
                    event.eventId(),
                    event.aggregateId(),
                    event.aggregateType(),
                    event.sequenceNumber(),
                    event.eventType(),
                    mapper.writeValueAsString(maskedTree),
                    event.metadata(),
                    event.timestamp(),
                    event.globalPosition());
        } catch (Exception e) {
            return event;
        }
    }
    private record TimelineEnvelope(AggregateTimeline timeline, boolean hasMore, String nextCursor) {
    }
}
