package io.eventlens.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Simple per-key token bucket rate limiter.
 *
 * Designed for HTTP rate limiting keyed by client IP.
 */
public final class RateLimiter {

    private final Cache<String, Bucket> buckets;
    private final int requestsPerMinute;
    private final int burstCapacity;
    private final double tokensPerNano;

    public RateLimiter(EventLensConfig.RateLimitConfig config) {
        this.requestsPerMinute = Math.max(1, config.getRequestsPerMinute());
        this.burstCapacity = Math.max(1, config.getBurst());
        this.tokensPerNano = this.requestsPerMinute / (60.0 * 1_000_000_000.0);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofMinutes(5))
                .build();
    }

    public RateLimitResult tryConsume(String key) {
        Bucket b = buckets.get(key, ignored -> new Bucket(burstCapacity));
        return b.tryConsume(tokensPerNano, burstCapacity);
    }

    public record RateLimitResult(
            boolean allowed,
            long remainingTokens,
            long retryAfterSeconds,
            long resetEpochSeconds,
            int limitPerMinute
    ) {
        public static RateLimitResult allowed(long remainingTokens, int limitPerMinute) {
            return new RateLimitResult(true, remainingTokens, 0, 0, limitPerMinute);
        }

        public static RateLimitResult blocked(long retryAfterSeconds, long resetEpochSeconds, int limitPerMinute) {
            return new RateLimitResult(false, 0, retryAfterSeconds, resetEpochSeconds, limitPerMinute);
        }
    }

    private final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        private Bucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized RateLimitResult tryConsume(double refillRatePerNano, int capacity) {
            long now = System.nanoTime();
            long elapsed = Math.max(0, now - lastRefillNanos);
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + (elapsed * refillRatePerNano));
                lastRefillNanos = now;
            }

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return RateLimitResult.allowed((long) Math.floor(tokens), requestsPerMinute);
            }

            double deficit = 1.0 - tokens;
            long nanosToWait = (long) Math.ceil(deficit / refillRatePerNano);
            long waitSeconds = TimeUnit.NANOSECONDS.toSeconds(nanosToWait) + 1;
            long resetEpochSeconds = Instant.now().getEpochSecond() + waitSeconds;
            return RateLimitResult.blocked(waitSeconds, resetEpochSeconds, requestsPerMinute);
        }
    }
}

