package io.eventlens.spi;

public record EventStatisticsQuery(int bucketHours, int maxBuckets) {
    public EventStatisticsQuery {
        if (bucketHours <= 0) {
            throw new IllegalArgumentException("bucketHours must be > 0");
        }
        if (maxBuckets <= 0) {
            throw new IllegalArgumentException("maxBuckets must be > 0");
        }
    }

    public static EventStatisticsQuery defaults() {
        return new EventStatisticsQuery(1, 24);
    }
}
