package io.eventlens.spi;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventQueryTest {

    @Test
    void builder_sets_expected_values() {
        var query = EventQuery.builder(EventQuery.QueryType.TIMELINE)
                .aggregateId("agg-1")
                .limit(50)
                .cursor("10")
                .fields(EventQuery.Fields.METADATA)
                .build();

        assertThat(query.type()).isEqualTo(EventQuery.QueryType.TIMELINE);
        assertThat(query.aggregateId()).isEqualTo("agg-1");
        assertThat(query.limit()).isEqualTo(50);
        assertThat(query.cursor()).isEqualTo("10");
        assertThat(query.fields()).isEqualTo(EventQuery.Fields.METADATA);
    }

    @Test
    void defaults_fields_to_all_when_null() {
        var query = new EventQuery(
                EventQuery.QueryType.SEARCH,
                null, null, null, null, null,
                25, null, null
        );

        assertThat(query.fields()).isEqualTo(EventQuery.Fields.ALL);
    }

    @Test
    void rejects_non_positive_limit() {
        assertThatThrownBy(() -> new EventQuery(
                EventQuery.QueryType.SEARCH,
                null, null, null, null, null,
                0, null, EventQuery.Fields.ALL
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_invalid_time_range() {
        assertThatThrownBy(() -> EventQuery.builder(EventQuery.QueryType.SEARCH)
                .from(Instant.parse("2026-01-02T00:00:00Z"))
                .to(Instant.parse("2026-01-01T00:00:00Z"))
                .build()
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
