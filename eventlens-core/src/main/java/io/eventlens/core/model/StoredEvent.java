package io.eventlens.core.model;

import io.eventlens.core.JsonUtil;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Universal event record — the core data unit flowing through all engines.
 * Immutable, comparable by sequence number within an aggregate.
 */
public record StoredEvent(
        UUID eventId,
        String aggregateId,
        String aggregateType,
        long sequenceNumber, // Position within aggregate
        String eventType, // e.g., "MoneyDeposited"
        String payload, // Raw JSON
        String metadata, // Raw JSON (correlationId, userId, etc.)
        Instant timestamp,
        long globalPosition // Position in entire store
) implements Comparable<StoredEvent> {

    @Override
    public int compareTo(StoredEvent other) {
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    public Map<String, Object> parsedPayload() {
        return JsonUtil.parseMap(payload);
    }

    public Map<String, Object> parsedMetadata() {
        return JsonUtil.parseMap(metadata);
    }
}
