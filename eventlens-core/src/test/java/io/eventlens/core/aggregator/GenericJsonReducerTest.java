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
        return new StoredEvent(UUID.randomUUID(), "AGG-1", "BankAccount", 1,
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
    void depositAddsToExistingBalance() {
        var state = Map.of("balance", 100.0, "_version", 1L, "_lastEventType", "AccountCreated",
                "_lastUpdated", "2026-01-01T00:00:00Z");
        var result = reducer.apply(state, event("MoneyDeposited", "{\"balance\":50}"));

        assertThat(result.get("balance")).isEqualTo(150.0);
    }

    @Test
    void withdrawalSubtractsFromExistingBalance() {
        var state = Map.of("balance", 200.0, "_version", 2L, "_lastEventType", "MoneyDeposited",
                "_lastUpdated", "2026-01-01T00:00:00Z");
        var result = reducer.apply(state, event("MoneyWithdrawn", "{\"balance\":75}"));

        assertThat(result.get("balance")).isEqualTo(125.0);
    }

    @Test
    void closedEventSetsStatusDeleted() {
        var result = reducer.apply(Map.of(), event("AccountClosed", "{\"reason\":\"customer request\"}"));

        assertThat(result).containsEntry("status", "DELETED")
                .containsEntry("reason", "customer request");
    }

    @Test
    void genericPayloadFieldsOverwriteState() {
        var state = Map.of("email", "old@test.com", "_version", 1L,
                "_lastEventType", "AccountCreated", "_lastUpdated", "now");
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
