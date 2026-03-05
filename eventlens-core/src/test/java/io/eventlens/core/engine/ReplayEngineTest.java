package io.eventlens.core.engine;

import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.exception.ReplayException;

import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class ReplayEngineTest {

    // Lightweight in-memory EventStoreReader — no DB needed
    private static EventStoreReader inMemory(List<StoredEvent> events) {
        return new EventStoreReader() {
            public List<StoredEvent> getEvents(String id) {
                return events;
            }

            public List<StoredEvent> getEventsUpTo(String id, long max) {
                return events.stream().filter(e -> e.sequenceNumber() <= max).toList();
            }

            public List<String> findAggregateIds(String t, int l, int o) {
                return List.of();
            }

            public List<StoredEvent> getRecentEvents(int l) {
                return events;
            }

            public List<StoredEvent> getEventsAfter(long pos, int l) {
                return List.of();
            }

            public long countEvents(String id) {
                return events.size();
            }

            public List<String> getAggregateTypes() {
                return List.of("BankAccount");
            }

            public List<String> searchAggregates(String q, int l) {
                return List.of();
            }
        };
    }

    private StoredEvent event(long seq, String type, String payload) {
        return new StoredEvent(UUID.randomUUID(), "ACC-001", "BankAccount", seq,
                type, payload, "{}", Instant.now(), seq);
    }

    private ReplayEngine engine(List<StoredEvent> events) {
        var registry = new ReducerRegistry();
        return new ReplayEngine(inMemory(events), registry);
    }

    @Test
    void replayFullReturnOneTransitionPerEvent() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"balance\":100}"),
                event(3, "MoneyWithdrawn", "{\"balance\":30}"));
        var transitions = engine(events).replayFull("ACC-001");
        assertThat(transitions).hasSize(3);
    }

    @Test
    void replayToStopsAtSpecifiedSequence() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"balance\":500}"),
                event(3, "MoneyWithdrawn", "{\"balance\":100}"));
        var result = engine(events).replayTo("ACC-001", 2);
        assertThat(result.atSequence()).isEqualTo(2);
        assertThat(result.transitions()).hasSize(2);
    }

    @Test
    void diffDetectsAddedRemovedAndChangedFields() {
        Map<String, Object> before = Map.of("balance", 100.0, "status", "OPEN");
        Map<String, Object> after = Map.of("balance", 200.0, "email", "new@test.com");

        var e = engine(List.of());
        var diff = e.computeDiff(before, after);

        // balance changed
        assertThat(diff).containsKey("balance");
        assertThat(diff.get("balance").oldValue()).isEqualTo(100.0);
        assertThat(diff.get("balance").newValue()).isEqualTo(200.0);

        // status removed
        assertThat(diff).containsKey("status");
        assertThat(diff.get("status").newValue()).isNull();

        // email added
        assertThat(diff).containsKey("email");
        assertThat(diff.get("email").oldValue()).isNull();
    }

    @Test
    void replayFullReturnsEmptyForNoEvents() {
        var transitions = engine(List.of()).replayFull("EMPTY");
        assertThat(transitions).isEmpty();
    }

    @Test
    void buildTimelineHasCorrectEventCount() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"balance\":50}"));
        var timeline = engine(events).buildTimeline("ACC-001");
        assertThat(timeline.totalEvents()).isEqualTo(2);
        assertThat(timeline.aggregateType()).isEqualTo("BankAccount");
    }

    @Test
    void replayWrapsReducerExceptionAsReplayException() {
        var brokenEvent = event(1, "Broken", "not-valid-json");
        // Force a reducer that always throws
        var registry = new ReducerRegistry();
        registry.register("BankAccount", (state, ev) -> {
            throw new RuntimeException("reducer failed");
        });

        var replayEngine = new ReplayEngine(inMemory(List.of(brokenEvent)), registry);
        assertThatThrownBy(() -> replayEngine.replayFull("ACC-001"))
                .isInstanceOf(ReplayException.class)
                .hasMessageContaining("Reducer failed");
    }
}
