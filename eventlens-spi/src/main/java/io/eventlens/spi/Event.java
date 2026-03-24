package io.eventlens.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record Event(
        String eventId,
        String aggregateId,
        String aggregateType,
        long sequenceNumber,
        String eventType,
        JsonNode payload,
        JsonNode metadata,
        Instant timestamp,
        long globalPosition
) {
}
