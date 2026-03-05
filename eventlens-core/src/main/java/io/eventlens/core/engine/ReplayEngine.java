package io.eventlens.core.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.exception.ReplayException;
import io.eventlens.core.model.*;
import io.eventlens.core.spi.EventStoreReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.time.Duration;

/**
 * The heart of EventLens — replays events through a reducer to produce
 * a complete state timeline with field-level diffs at each step.
 */
public class ReplayEngine {

    private static final Logger log = LoggerFactory.getLogger(ReplayEngine.class);

    private final EventStoreReader reader;
    private final ReducerRegistry reducerRegistry;
    private final Cache<String, ReplayResult> fullReplayCache;

    public ReplayEngine(EventStoreReader reader, ReducerRegistry reducerRegistry) {
        this.reader = reader;
        this.reducerRegistry = reducerRegistry;
        this.fullReplayCache = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(Duration.ofMinutes(1))
                .build();
    }

    /**
     * Replay all events for an aggregate, returning state transitions.
     * Each transition contains before/after state and a field-level diff.
     */
    public List<StateTransition> replayFull(String aggregateId) {
        log.debug("Full replay for aggregate: {}", aggregateId);
        ReplayResult result = fullReplayCache.get(aggregateId, this::replayFullInternal);
        return result.transitions();
    }

    /**
     * Replay events up to a specific sequence number (inclusive).
     */
    public ReplayResult replayTo(String aggregateId, long targetSequence) {
        log.debug("Replaying '{}' to sequence #{}", aggregateId, targetSequence);
        List<StoredEvent> events = reader.getEventsUpTo(aggregateId, targetSequence);
        List<StateTransition> transitions = computeTransitions(aggregateId, events);

        Map<String, Object> finalState = transitions.isEmpty()
                ? Map.of()
                : transitions.getLast().stateAfter();

        return new ReplayResult(aggregateId, targetSequence, finalState, transitions);
    }

    /**
     * Build a timeline containing all events for the aggregate (no state
     * computation).
     * Lightweight — used by the UI timeline component.
     */
    public AggregateTimeline buildTimeline(String aggregateId) {
        // Backwards-compatible: default to full history
        return buildTimeline(aggregateId, Integer.MAX_VALUE, 0);
    }

    /**
     * Build a paginated timeline containing a window of events for the aggregate.
     * The {@code totalEvents} field always reflects the full count for the
     * aggregate, even when a subset of events is returned.
     */
    public AggregateTimeline buildTimeline(String aggregateId, int limit, int offset) {
        List<StoredEvent> events = reader.getEvents(aggregateId, limit, offset);
        long total = reader.countEvents(aggregateId);
        String aggregateType = events.isEmpty() ? "unknown" : events.getFirst().aggregateType();
        return new AggregateTimeline(aggregateId, aggregateType, events, (int) Math.min(Integer.MAX_VALUE, total));
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Core computation: fold events through the reducer, capturing diff at each
     * step.
     */
    List<StateTransition> computeTransitions(String aggregateId, List<StoredEvent> events) {
        if (events.isEmpty())
            return List.of();

        String aggregateType = events.getFirst().aggregateType();
        var reducer = reducerRegistry.getReducer(aggregateType);

        List<StateTransition> transitions = new ArrayList<>();
        Map<String, Object> currentState = new LinkedHashMap<>();

        for (StoredEvent event : events) {
            Map<String, Object> stateBefore = Map.copyOf(currentState);
            Map<String, Object> stateAfter;
            try {
                stateAfter = reducer.apply(currentState, event);
            } catch (Exception e) {
                throw new ReplayException(
                        String.format("Reducer failed at sequence #%d (%s) for aggregate '%s'",
                                event.sequenceNumber(), event.eventType(), aggregateId),
                        e);
            }
            Map<String, StateTransition.FieldChange> diff = computeDiff(stateBefore, stateAfter);
            transitions.add(new StateTransition(event, stateBefore, stateAfter, diff));
            currentState = new LinkedHashMap<>(stateAfter);
        }

        return transitions;
    }

    /**
     * Field-level diff between two state snapshots.
     * Detects changes, additions, and removals.
     */
    Map<String, StateTransition.FieldChange> computeDiff(
            Map<String, Object> before, Map<String, Object> after) {

        Map<String, StateTransition.FieldChange> diff = new LinkedHashMap<>();

        // Changed or added fields
        for (var entry : after.entrySet()) {
            Object oldVal = before.get(entry.getKey());
            Object newVal = entry.getValue();
            if (!Objects.equals(oldVal, newVal)) {
                diff.put(entry.getKey(), new StateTransition.FieldChange(oldVal, newVal));
            }
        }
        // Removed fields
        for (var key : before.keySet()) {
            if (!after.containsKey(key)) {
                diff.put(key, new StateTransition.FieldChange(before.get(key), null));
            }
        }
        return diff;
    }

    private ReplayResult replayFullInternal(String aggregateId) {
        List<StoredEvent> events = reader.getEvents(aggregateId);
        List<StateTransition> transitions = computeTransitions(aggregateId, events);
        long lastSequence = transitions.isEmpty()
                ? 0
                : transitions.getLast().event().sequenceNumber();
        Map<String, Object> finalState = transitions.isEmpty()
                ? Map.of()
                : transitions.getLast().stateAfter();
        return new ReplayResult(aggregateId, lastSequence, finalState, transitions);
    }

    public record ReplayResult(
            String aggregateId,
            long atSequence,
            Map<String, Object> state,
            List<StateTransition> transitions) {
    }
}
