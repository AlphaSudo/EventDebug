package io.eventlens.api.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class QueryResultCacheBenchmarkTest {

    @Test
    void repeatedQueriesProduceCacheHitRatioAboveFiftyPercent() {
        QueryResultCache cache = new QueryResultCache(true, 128);
        AtomicInteger supplierCalls = new AtomicInteger();

        for (int i = 0; i < 100; i++) {
            String key = i < 80 ? "timeline:ACC-001" : "timeline:ACC-002";
            cache.getOrCompute("timeline", key, Duration.ofSeconds(30), () -> {
                supplierCalls.incrementAndGet();
                return "value:" + key;
            });
        }

        assertThat(supplierCalls.get()).isEqualTo(2);
        assertThat(cache.hitRatio())
                .withFailMessage("Expected cache hit ratio > 0.50 but was %.2f", cache.hitRatio())
                .isGreaterThan(0.50);
    }
}
