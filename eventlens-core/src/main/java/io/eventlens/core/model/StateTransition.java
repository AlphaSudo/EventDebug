package io.eventlens.core.model;

import java.util.Map;

/**
 * Captures a single state change triggered by one event.
 * Contains the full before/after state and a field-level diff.
 */
public record StateTransition(
        StoredEvent event,
        Map<String, Object> stateBefore,
        Map<String, Object> stateAfter,
        Map<String, FieldChange> diff) {
    /**
     * Represents a single field change: old value → new value.
     * Either side may be null (field was added or removed).
     */
    public record FieldChange(Object oldValue, Object newValue) {
        @Override
        public String toString() {
            return oldValue + " → " + newValue;
        }
    }
}
