package io.eventlens.core.aggregator;

import io.eventlens.core.model.StoredEvent;

import java.util.*;

/**
 * Default reducer that uses event-type heuristics for JSON merging.
 * Applied when no custom reducer is registered for an aggregate type.
 *
 * <p>
 * Heuristics:
 * <ul>
 * <li>{@code *Created / *Opened} — merge all payload fields into state</li>
 * <li>{@code *Deleted / *Closed} — set status=DELETED and merge</li>
 * <li>{@code *Deposit / *Credit / *Added} — accumulate numeric fields</li>
 * <li>{@code *Withdrawn / *Debit / *Subtract} — subtract numeric fields</li>
 * <li>Everything else — direct overwrite</li>
 * </ul>
 */
public class GenericJsonReducer implements AggregateReducer {

    @Override
    public Map<String, Object> apply(Map<String, Object> currentState, StoredEvent event) {
        var newState = new LinkedHashMap<>(currentState);
        var payload = event.parsedPayload();

        // Always track internal metadata
        newState.put("_version", event.sequenceNumber());
        newState.put("_lastEventType", event.eventType());
        newState.put("_lastUpdated", event.timestamp().toString());

        String type = event.eventType().toLowerCase();

        if (type.contains("created") || type.contains("opened")) {
            newState.putAll(payload);
        } else if (type.contains("deleted") || type.contains("closed")) {
            newState.put("status", "DELETED");
            newState.putAll(payload);
        } else {
            for (var entry : payload.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Number newNum && currentState.get(key) instanceof Number oldNum) {
                    if (type.contains("deposit") || type.contains("credit") || type.contains("added")) {
                        newState.put(key, oldNum.doubleValue() + newNum.doubleValue());
                    } else if (type.contains("withdraw") || type.contains("debit") || type.contains("subtract")) {
                        newState.put(key, oldNum.doubleValue() - newNum.doubleValue());
                    } else {
                        newState.put(key, value);
                    }
                } else {
                    newState.put(key, value);
                }
            }
        }

        return Collections.unmodifiableMap(newState);
    }
}
