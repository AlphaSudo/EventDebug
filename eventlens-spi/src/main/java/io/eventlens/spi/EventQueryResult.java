package io.eventlens.spi;

import java.util.List;

public record EventQueryResult(
        List<Event> events,
        boolean hasMore,
        String nextCursor
) {
    public EventQueryResult {
        events = List.copyOf(events == null ? List.of() : events);
    }

    public static EventQueryResult empty() {
        return new EventQueryResult(List.of(), false, null);
    }
}
