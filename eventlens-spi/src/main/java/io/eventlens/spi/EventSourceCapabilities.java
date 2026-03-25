package io.eventlens.spi;

import java.util.Set;

public record EventSourceCapabilities(
        boolean supportsCursorPagination,
        boolean supportsFullTextSearch,
        boolean supportsGlobalOrdering,
        boolean supportsTimeRangeFilter,
        Set<String> filterableFields
) {
    public EventSourceCapabilities {
        filterableFields = Set.copyOf(filterableFields == null ? Set.of() : filterableFields);
    }

    public static EventSourceCapabilities basic() {
        return new EventSourceCapabilities(
                true,
                false,
                true,
                true,
                Set.of("aggregate_id", "aggregate_type", "event_type", "timestamp")
        );
    }
}
