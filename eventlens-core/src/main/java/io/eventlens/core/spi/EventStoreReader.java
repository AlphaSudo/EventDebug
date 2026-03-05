package io.eventlens.core.spi;

import io.eventlens.core.model.StoredEvent;

import java.util.List;

/**
 * Service Provider Interface for reading events from an event store.
 * All implementations are read-only — they NEVER write to the store.
 *
 * <p>
 * Implementations: {@code PgEventStoreReader}, future: EventStoreDB, MSSQL,
 * etc.
 */
public interface EventStoreReader {

    /** Get all events for an aggregate, ordered by sequence number. */
    List<StoredEvent> getEvents(String aggregateId);

    /**
     * Get events for an aggregate with pagination.
     *
     * @param aggregateId the aggregate identifier
     * @param limit       maximum number of events to return
     * @param offset      number of events to skip from the start
     */
    default List<StoredEvent> getEvents(String aggregateId, int limit, int offset) {
        // Default implementation falls back to full history for non-paged readers.
        // Implementations like PgEventStoreReader override this to use SQL LIMIT/OFFSET.
        return getEvents(aggregateId);
    }

    /** Get events for an aggregate up to a sequence number (inclusive). */
    List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence);

    /** Search aggregates by type with pagination. */
    List<String> findAggregateIds(String aggregateType, int limit, int offset);

    /**
     * Get the most recent events across all aggregates (for live tail fallback).
     */
    List<StoredEvent> getRecentEvents(int limit);

    /** Get events after a global position (for polling-based live tail). */
    List<StoredEvent> getEventsAfter(long globalPosition, int limit);

    /** Count events for an aggregate. */
    long countEvents(String aggregateId);

    /** Get all distinct aggregate types. */
    List<String> getAggregateTypes();

    /** Search aggregates by partial ID match (ILIKE) with pagination. */
    List<String> searchAggregates(String query, int limit);
}
