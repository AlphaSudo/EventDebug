package io.eventlens.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpiTypesSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void event_query_round_trip_json() throws Exception {
        var query = EventQuery.builder(EventQuery.QueryType.SEARCH)
                .aggregateType("ORDER")
                .eventType("ORDER_PLACED")
                .from(Instant.parse("2026-01-01T00:00:00Z"))
                .to(Instant.parse("2026-01-02T00:00:00Z"))
                .limit(100)
                .cursor("abc")
                .fields(EventQuery.Fields.METADATA)
                .build();

        var json = mapper.writeValueAsString(query);
        var roundTrip = mapper.readValue(json, EventQuery.class);

        assertThat(roundTrip).isEqualTo(query);
    }

    @Test
    void health_status_factory_methods_work() {
        assertThat(HealthStatus.up().state()).isEqualTo(HealthStatus.State.UP);
        assertThat(HealthStatus.down("x").state()).isEqualTo(HealthStatus.State.DOWN);
    }

    @Test
    void capabilities_basic_factory_is_stable() {
        var capabilities = EventSourceCapabilities.basic();
        assertThat(capabilities.supportsCursorPagination()).isTrue();
        assertThat(capabilities.filterableFields()).isNotEmpty();
    }

    @Test
    void collections_are_defensively_copied() {
        var details = new java.util.HashMap<String, Object>();
        details.put("k", "v");
        var health = new HealthStatus(HealthStatus.State.UP, null, details);
        details.put("x", "y");
        assertThat(health.details()).containsOnlyKeys("k");

        var fields = new java.util.HashSet<String>();
        fields.add("aggregate_id");
        var capabilities = new EventSourceCapabilities(true, false, true, true, fields);
        fields.add("event_type");
        assertThat(capabilities.filterableFields()).containsExactly("aggregate_id");

        var result = new EventQueryResult(new java.util.ArrayList<>(), false, null);
        assertThatThrownBy(() -> result.events().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void spi_versions_compatibility_contract() {
        Optional<String> ok = SpiVersions.checkCompatibility("plugin-a", SpiVersions.CURRENT);
        Optional<String> tooOld = SpiVersions.checkCompatibility("plugin-a", SpiVersions.MINIMUM_SUPPORTED - 1);
        Optional<String> tooNew = SpiVersions.checkCompatibility("plugin-a", SpiVersions.CURRENT + 1);

        assertThat(ok).isEmpty();
        assertThat(tooOld).isPresent();
        assertThat(tooNew).isPresent();
    }
}
