package io.eventlens.core.aggregator;

import io.eventlens.core.model.StoredEvent;

import java.util.Map;

/**
 * Pure function that applies a single event to the current aggregate state,
 * producing a new immutable state map.
 *
 * <p>
 * Implementations MUST be pure functions — no side effects, no I/O.
 *
 * <p>
 * Users can provide custom implementations by:
 * <ol>
 * <li>Dropping a JAR with a
 * {@code META-INF/services/io.eventlens.core.aggregator.AggregateReducer}
 * entry on the classpath via {@code --classpath}.</li>
 * <li>Referencing the class in {@code eventlens.yaml} under
 * {@code replay.reducers}.</li>
 * </ol>
 */
@FunctionalInterface
public interface AggregateReducer {
    /**
     * @param currentState an immutable view of the current state
     * @param event        the event to apply
     * @return the new state after applying the event (must be a new Map, not a
     *         mutation)
     */
    Map<String, Object> apply(Map<String, Object> currentState, StoredEvent event);

    /**
     * Optional: return the aggregate type this reducer handles.
     * Used by {@link ReducerRegistry} for auto-registration from classpath JARs.
     */
    default String aggregateType() {
        return null; // null = not auto-registered by type
    }
}
