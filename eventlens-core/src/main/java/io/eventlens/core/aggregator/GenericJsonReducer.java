package io.eventlens.core.aggregator;

import io.eventlens.core.model.StoredEvent;

import java.util.*;

/**
 * Generic reducer that merges event payloads into state. Works with any domain
 * (orders, tickets, accounts, etc.). Applied when no custom reducer is registered.
 *
 * <p>
 * Heuristics (domain-agnostic):
 * <ul>
 * <li>{@code *Created / *Opened / *Placed / *Submitted} — merge full payload into state</li>
 * <li>{@code *Deleted / *Closed / *Cancelled / *Rejected} — set status and merge payload</li>
 * <li>Everything else — merge payload fields into state (overwrite per key)</li>
 * </ul>
 * No domain-specific arithmetic (e.g. deposit/withdraw); state is built from event payloads only.
 */
public class GenericJsonReducer implements AggregateReducer {

    @Override
    public Map<String, Object> apply(Map<String, Object> currentState, StoredEvent event) {
        var newState = new LinkedHashMap<>(currentState);
        var payload = event.parsedPayload();

        newState.put("_version", event.sequenceNumber());
        newState.put("_lastEventType", event.eventType());
        newState.put("_lastUpdated", event.timestamp().toString());

        String type = event.eventType().toLowerCase();

        if (type.contains("created") || type.contains("opened") || type.contains("placed") || type.contains("submitted")) {
            newState.putAll(payload);
        } else if (type.contains("deleted") || type.contains("closed") || type.contains("cancelled") || type.contains("rejected")) {
            newState.put("status", "DELETED");
            newState.putAll(payload);
        } else {
            newState.putAll(payload);
        }

        return Collections.unmodifiableMap(newState);
    }
}
