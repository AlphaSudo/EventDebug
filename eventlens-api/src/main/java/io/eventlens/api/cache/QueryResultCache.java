package io.eventlens.api.cache;

import io.eventlens.api.metrics.EventLensMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class QueryResultCache {

    private final boolean enabled;
    private final int maxEntries;
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final Counter hits = Counter.builder("eventlens_query_cache_hits_total")
            .description("Cache hits for query results")
            .register(EventLensMetrics.registry);
    private final Counter misses = Counter.builder("eventlens_query_cache_misses_total")
            .description("Cache misses for query results")
            .register(EventLensMetrics.registry);
    private final Counter evictions = Counter.builder("eventlens_query_cache_evictions_total")
            .description("Cache evictions for query results")
            .register(EventLensMetrics.registry);

    public QueryResultCache(boolean enabled, int maxEntries) {
        this.enabled = enabled;
        this.maxEntries = Math.max(1, maxEntries);
        Gauge.builder("eventlens_query_cache_size", size, AtomicInteger::get)
                .description("Current number of cached query entries")
                .register(EventLensMetrics.registry);
    }

    public <T> T getOrCompute(String namespace, String key, Duration ttl, Supplier<T> supplier) {
        if (!enabled) {
            return supplier.get();
        }

        String cacheKey = namespace + "::" + key;
        long now = System.nanoTime();
        CacheEntry current = entries.get(cacheKey);
        if (current != null && current.expiresAtNanos > now) {
            hits.increment();
            @SuppressWarnings("unchecked")
            T value = (T) current.value;
            return value;
        }

        misses.increment();
        T value = supplier.get();
        entries.put(cacheKey, new CacheEntry(value, now + Math.max(1L, ttl.toNanos())));
        size.set(entries.size());
        evictExpired(now);
        evictOverflow();
        return value;
    }

    public double hitRatio() {
        double hitCount = hits.count();
        double missCount = misses.count();
        double total = hitCount + missCount;
        return total == 0 ? 0.0 : hitCount / total;
    }

    private void evictExpired(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAtNanos <= now);
        size.set(entries.size());
    }

    private void evictOverflow() {
        if (entries.size() <= maxEntries) {
            return;
        }

        Iterator<String> iterator = entries.keySet().iterator();
        while (entries.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
            evictions.increment();
        }
        size.set(entries.size());
    }

    private static final class CacheEntry {
        private final Object value;
        private final long expiresAtNanos;

        private CacheEntry(Object value, long expiresAtNanos) {
            this.value = Objects.requireNonNull(value);
            this.expiresAtNanos = expiresAtNanos;
        }
    }
}
