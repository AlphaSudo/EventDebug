package io.eventlens.core.model;

import java.util.Map;

/**
 * A snapshot of aggregate state at a point in time.
 * Used by the REST API and export engine.
 */
public record AggregateState(
        String aggregateId,
        String aggregateType,
        long atSequence,
        Map<String, Object> state) {
}
