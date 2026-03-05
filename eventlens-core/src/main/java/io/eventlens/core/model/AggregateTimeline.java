package io.eventlens.core.model;

import java.util.List;
import java.util.Optional;

/**
 * An ordered sequence of events for a single aggregate.
 * Provides convenience query methods used by the Replay and Bisect engines.
 */
public record AggregateTimeline(
        String aggregateId,
        String aggregateType,
        List<StoredEvent> events,
        int totalEvents) {
    public Optional<StoredEvent> eventAt(long sequenceNumber) {
        return events.stream()
                .filter(e -> e.sequenceNumber() == sequenceNumber)
                .findFirst();
    }

    public List<StoredEvent> eventsUpTo(long sequenceNumber) {
        return events.stream()
                .filter(e -> e.sequenceNumber() <= sequenceNumber)
                .toList();
    }

    public Optional<StoredEvent> first() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.getFirst());
    }

    public Optional<StoredEvent> last() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.getLast());
    }
}
