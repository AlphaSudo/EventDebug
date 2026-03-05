package io.eventlens.core.engine;

import io.eventlens.core.model.StateTransition;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiffEngineTest {

    private StoredEvent event(long seq, String type, String payload) {
        return new StoredEvent(
                UUID.randomUUID(),
                "ACC-001",
                "BankAccount",
                seq,
                type,
                payload,
                "{}",
                Instant.now(),
                seq);
    }

    private ReplayEngine replayEngineWithStates(Map<String, Object> stateA, Map<String, Object> stateB) {
        // Use a tiny EventStoreReader that returns two events,
        // and a custom reducer that just returns the provided states.
        List<StoredEvent> events = List.of(
                event(1, "SnapshotA", "{}"),
                event(2, "SnapshotB", "{}"));

        EventStoreReader reader = new EventStoreReader() {
            @Override
            public List<StoredEvent> getEvents(String aggregateId) {
                return events;
            }

            @Override
            public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
                return events.stream().filter(e -> e.sequenceNumber() <= maxSequence).toList();
            }

            @Override
            public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
                return List.of();
            }

            @Override
            public List<StoredEvent> getRecentEvents(int limit) {
                return events;
            }

            @Override
            public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
                return List.of();
            }

            @Override
            public long countEvents(String aggregateId) {
                return events.size();
            }

            @Override
            public List<String> getAggregateTypes() {
                return List.of();
            }

            @Override
            public List<String> searchAggregates(String query, int limit) {
                return List.of();
            }
        };

        var registry = new io.eventlens.core.aggregator.ReducerRegistry();
        registry.register("BankAccount", (state, ev) -> {
            if (ev.sequenceNumber() == 1) {
                return stateA;
            }
            if (ev.sequenceNumber() == 2) {
                return stateB;
            }
            return state;
        });

        return new ReplayEngine(reader, registry);
    }

    @Test
    void diffComparesFinalStates() {
        Map<String, Object> stateA = Map.of("balance", 100, "status", "OPEN");
        Map<String, Object> stateB = Map.of("balance", 50, "status", "CLOSED", "flag", "FRAUD");

        var diffEngine = new DiffEngine(replayEngineWithStates(stateA, stateB));
        Map<String, StateTransition.FieldChange> diff = diffEngine.diff("ACC-001", "ACC-001");

        assertThat(diff).containsKey("balance");
        assertThat(diff.get("balance").oldValue()).isEqualTo(100);
        assertThat(diff.get("balance").newValue()).isEqualTo(50);

        assertThat(diff).containsKey("status");
        assertThat(diff.get("status").oldValue()).isEqualTo("OPEN");
        assertThat(diff.get("status").newValue()).isEqualTo("CLOSED");

        assertThat(diff).containsKey("flag");
        assertThat(diff.get("flag").oldValue()).isNull();
        assertThat(diff.get("flag").newValue()).isEqualTo("FRAUD");
    }

    @Test
    void diffAtSequencesUsesReplayToStates() {
        Map<String, Object> stateA = Map.of("balance", 10);
        Map<String, Object> stateB = Map.of("balance", 20);

        var replayEngine = replayEngineWithStates(stateA, stateB);
        var diffEngine = new DiffEngine(replayEngine);

        Map<String, StateTransition.FieldChange> diff =
                diffEngine.diffAtSequences("ACC-001", 1, 2);

        assertThat(diff).containsKey("balance");
        assertThat(diff.get("balance").oldValue()).isEqualTo(10);
        assertThat(diff.get("balance").newValue()).isEqualTo(20);
    }
}

