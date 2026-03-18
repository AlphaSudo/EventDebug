package io.eventlens.core.spi;

import io.eventlens.core.exception.EventStoreException;
import io.eventlens.core.model.StoredEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight circuit-breaker wrapper around {@link EventStoreReader}.
 *
 * <p>No external dependencies: after a burst of consecutive failures the
 * circuit opens for a cooldown period and subsequent calls fail fast.</p>
 */
public final class ResilientEventStoreReader implements EventStoreReader, AutoCloseable {

    private final EventStoreReader delegate;
    private final int failureThreshold;
    private final long openDurationNanos;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openUntilNanos = new AtomicLong(0);

    public ResilientEventStoreReader(EventStoreReader delegate) {
        this(delegate, 5, 30_000_000_000L); // 5 failures, 30s open
    }

    public ResilientEventStoreReader(EventStoreReader delegate, int failureThreshold, long openDurationNanos) {
        this.delegate = delegate;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationNanos = Math.max(1_000_000_000L, openDurationNanos);
    }

    private void ensureClosed() {
        long now = System.nanoTime();
        long until = openUntilNanos.get();
        if (until > 0 && now < until) {
            throw new EventStoreException("Database circuit is OPEN. Failing fast.");
        }
        if (until > 0 && now >= until) {
            // Cooldown elapsed — allow new attempts.
            openUntilNanos.compareAndSet(until, 0);
            consecutiveFailures.set(0);
        }
    }

    private <T> T withCircuitBreaker(SupplierWithException<T> supplier) {
        ensureClosed();
        try {
            T result = supplier.get();
            consecutiveFailures.set(0);
            return result;
        } catch (Exception e) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= failureThreshold) {
                openUntilNanos.compareAndSet(0, System.nanoTime() + openDurationNanos);
            }
            if (e instanceof EventStoreException ese) {
                throw ese;
            }
            throw new EventStoreException("Event store operation failed", e);
        }
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }

    @Override
    public List<StoredEvent> getEvents(String aggregateId) {
        return withCircuitBreaker(() -> delegate.getEvents(aggregateId));
    }

    @Override
    public List<StoredEvent> getEvents(String aggregateId, int limit, int offset) {
        return withCircuitBreaker(() -> delegate.getEvents(aggregateId, limit, offset));
    }

    @Override
    public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
        return withCircuitBreaker(() -> delegate.getEventsUpTo(aggregateId, maxSequence));
    }

    @Override
    public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
        return withCircuitBreaker(() -> delegate.findAggregateIds(aggregateType, limit, offset));
    }

    @Override
    public List<StoredEvent> getRecentEvents(int limit) {
        return withCircuitBreaker(() -> delegate.getRecentEvents(limit));
    }

    @Override
    public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
        return withCircuitBreaker(() -> delegate.getEventsAfter(globalPosition, limit));
    }

    @Override
    public long countEvents(String aggregateId) {
        return withCircuitBreaker(() -> delegate.countEvents(aggregateId));
    }

    @Override
    public List<String> getAggregateTypes() {
        return withCircuitBreaker(delegate::getAggregateTypes);
    }

    @Override
    public List<String> searchAggregates(String query, int limit) {
        return withCircuitBreaker(() -> delegate.searchAggregates(query, limit));
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}

