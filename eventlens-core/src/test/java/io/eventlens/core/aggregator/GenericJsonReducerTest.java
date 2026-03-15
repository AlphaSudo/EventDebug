package io.eventlens.core.aggregator;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import io.eventlens.core.model.StoredEvent;

import static org.assertj.core.api.Assertions.*;

class GenericJsonReducerTest {

    private final GenericJsonReducer reducer = new GenericJsonReducer();

    private StoredEvent event(String type, String payload) {
        return new StoredEvent(UUID.randomUUID().toString(), "AGG-1", "BankAccount", 1,
                type, payload, "{}", Instant.now(), 1);
    }

    @Test
    void createdEventMergesAllPayloadFields() {
        var result = reducer.apply(Map.of(), event("AccountCreated",
                "{\"accountHolder\":\"Alice\",\"balance\":0}"));

        assertThat(result).containsEntry("accountHolder", "Alice")
                .containsEntry("balance", 0)
                .containsKey("_version")
                .containsKey("_lastEventType");
    }

    @Test
    void depositEventMergesPayloadIntoState() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("balance", 100.0);
        state.put("_version", 1L);
        state.put("_lastEventType", "AccountCreated");
        state.put("_lastUpdated", "2026-01-01T00:00:00Z");

        var result = reducer.apply(state, event("MoneyDeposited", "{\"balance\":50}"));
        assertThat(result.get("balance")).isEqualTo(50);
    }

    @Test
    void withdrawalEventMergesPayloadIntoState() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("balance", 200.0);
        state.put("_version", 2L);
        state.put("_lastEventType", "MoneyDeposited");
        state.put("_lastUpdated", "2026-01-01T00:00:00Z");

        var result = reducer.apply(state, event("MoneyWithdrawn", "{\"balance\":75}"));
        assertThat(result.get("balance")).isEqualTo(75);
    }

    @Test
    void closedEventSetsStatusDeleted() {
        var result = reducer.apply(Map.of(), event("AccountClosed", "{\"reason\":\"customer request\"}"));
        assertThat(result).containsEntry("status", "DELETED")
                .containsEntry("reason", "customer request");
    }

    @Test
    void genericPayloadFieldsOverwriteState() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        state.put("email", "old@test.com");
        state.put("_version", 1L);
        state.put("_lastEventType", "AccountCreated");
        state.put("_lastUpdated", "now");

        var result = reducer.apply(state, event("EmailUpdated", "{\"email\":\"new@test.com\"}"));
        assertThat(result).containsEntry("email", "new@test.com");
    }

    @Test
    void resultIsImmutable() {
        var result = reducer.apply(Map.of(), event("Something", "{\"x\":1}"));
        assertThatThrownBy(() -> result.put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
