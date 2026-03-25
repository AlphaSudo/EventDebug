package io.eventlens.test;

import io.eventlens.spi.Event;
import io.eventlens.spi.EventQueryResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public final class PluginAssertions {

    private PluginAssertions() {
    }

    public static void assertOrderedSequence(EventQueryResult result, long... expectedSequenceNumbers) {
        Long[] boxed = java.util.Arrays.stream(expectedSequenceNumbers).boxed().toArray(Long[]::new);
        assertThat(result.events()).extracting(Event::sequenceNumber).containsExactly(boxed);
    }

    public static void assertMetadataOnly(Event event) {
        assertThat(event.payload()).isNotNull();
        assertThat(event.payload().isObject()).isTrue();
        assertThat(event.payload().size()).isZero();
        assertThat(event.metadata()).isNotNull();
        assertThat(event.metadata().isObject()).isTrue();
    }

    public static void assertAggregateIdsPresent(EventQueryResult result, String... aggregateIds) {
        assertThat(result.events()).extracting(Event::aggregateId).contains(aggregateIds);
    }

    public static void assertEventTypes(List<Event> events, String... eventTypes) {
        assertThat(events).extracting(Event::eventType).contains(eventTypes);
    }
}

