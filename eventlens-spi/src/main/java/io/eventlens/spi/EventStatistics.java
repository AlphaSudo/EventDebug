package io.eventlens.spi;

import java.util.List;
import java.util.Map;

public record EventStatistics(
        long totalEvents,
        long distinctAggregates,
        List<TypeCount> eventTypes,
        List<TypeCount> aggregateTypes,
        List<ThroughputPoint> throughput,
        boolean available,
        String message) {

    public EventStatistics {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        aggregateTypes = aggregateTypes == null ? List.of() : List.copyOf(aggregateTypes);
        throughput = throughput == null ? List.of() : List.copyOf(throughput);
    }

    public static EventStatistics unavailable(String message) {
        return new EventStatistics(0, 0, List.of(), List.of(), List.of(), false, message);
    }

    public static EventStatistics emptyAvailable() {
        return new EventStatistics(0, 0, List.of(), List.of(), List.of(), true, null);
    }

    public record TypeCount(String type, long count) {
    }

    public record ThroughputPoint(String bucket, long count) {
    }
}
