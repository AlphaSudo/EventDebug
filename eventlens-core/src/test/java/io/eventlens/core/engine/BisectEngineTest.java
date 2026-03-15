package io.eventlens.core.engine;

import io.eventlens.core.exception.ConditionParseException;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.spi.EventStoreReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class BisectEngineTest {

    private StoredEvent event(long seq, String type, String payload) {
        return new StoredEvent(UUID.randomUUID().toString(), "ACC-001", "BankAccount", seq,
                type, payload, "{}", Instant.now(), seq);
    }

    private BisectEngine makeEngine(List<StoredEvent> events) {
        EventStoreReader reader = new EventStoreReader() {
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
                return List.of();
            }

            public List<String> searchAggregates(String q, int l) {
                return List.of();
            }
        };
        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        return new BisectEngine(replayEngine, reader);
    }

    @Test
    void bisectFindsCulpritEventForNegativeBalance() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":100}"),
                event(2, "BalanceAdjusted", "{\"balance\":-50}")
        );

        var engine = makeEngine(events);
        var condition = BisectEngine.parseCondition("balance < 0");
        var result = engine.bisect("ACC-001", condition);

        assertThat(result.culpritEvent()).isNotNull();
        assertThat(result.culpritEvent().sequenceNumber()).isEqualTo(2);
        assertThat(result.replaysPerformed()).isGreaterThan(0);
    }

    @Test
    void bisectReturnsNoResultWhenConditionNeverTrue() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"balance\":100}"));

        var engine = makeEngine(events);
        var condition = BisectEngine.parseCondition("balance < 0");
        var result = engine.bisect("ACC-001", condition);

        assertThat(result.culpritEvent()).isNull();
        assertThat(result.summary()).contains("never becomes true");
    }

    @Test
    void bisectHandlesEmptyEventStream() {
        var engine = makeEngine(List.of());
        var condition = BisectEngine.parseCondition("balance < 0");
        var result = engine.bisect("ACC-001", condition);

        assertThat(result.culpritEvent()).isNull();
        assertThat(result.summary()).contains("No events");
    }

    // ── parseCondition tests ──────────────────────────────────────────────────

    @Test
    void parseConditionRejectsEmptyExpression() {
        assertThatThrownBy(() -> BisectEngine.parseCondition(""))
                .isInstanceOf(ConditionParseException.class);
    }

    @Test
    void parseConditionRejectsInvalidFormat() {
        assertThatThrownBy(() -> BisectEngine.parseCondition("balance"))
                .isInstanceOf(ConditionParseException.class);
    }

    @Test
    void parseConditionRejectsMaliciousInput() {
        assertThatThrownBy(() -> BisectEngine.parseCondition("balance; DROP TABLE events; --"))
                .isInstanceOf(ConditionParseException.class);
    }

    @Test
    void parseConditionNumericLessThan() {
        var pred = BisectEngine.parseCondition("balance < 0");
        assertThat(pred.test(Map.of("balance", -1.0))).isTrue();
        assertThat(pred.test(Map.of("balance", 1.0))).isFalse();
    }

    @Test
    void parseConditionStringEquals() {
        var pred = BisectEngine.parseCondition("status == DELETED");
        assertThat(pred.test(Map.of("status", "DELETED"))).isTrue();
        assertThat(pred.test(Map.of("status", "OPEN"))).isFalse();
    }

    @Test
    void parseConditionReturnsFalseForMissingField() {
        var pred = BisectEngine.parseCondition("balance < 0");
        assertThat(pred.test(Map.of())).isFalse();
        assertThat(pred.test(Map.of("amount", -5.0))).isFalse();
    }
}
