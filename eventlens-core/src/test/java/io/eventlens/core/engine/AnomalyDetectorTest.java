package io.eventlens.core.engine;

import io.eventlens.core.model.AnomalyReport;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectorTest {

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

    private EventStoreReader inMemory(List<StoredEvent> events) {
        return new EventStoreReader() {
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
    }

    @Test
    void configRuleDetectsNegativeBalance() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0,\"_version\":1}"),
                event(2, "MoneyWithdrawn", "{\"balance\":-10,\"_version\":2}")
        );

        var reader = inMemory(events);
        var replayEngine = new ReplayEngine(reader, new io.eventlens.core.aggregator.ReducerRegistry());
        var config = new io.eventlens.core.EventLensConfig.AnomalyConfig();
        config.setRules(List.of(ruleConfig("NEGATIVE_BALANCE", "balance < 0", "HIGH")));
        var detector = new AnomalyDetector(reader, replayEngine, config);

        List<AnomalyReport> anomalies = detector.scan("ACC-001");

        assertThat(anomalies).hasSize(1);
        AnomalyReport a = anomalies.getFirst();
        assertThat(a.code()).isEqualTo("NEGATIVE_BALANCE");
        assertThat(a.severity()).isEqualTo(AnomalyReport.Severity.HIGH);
        assertThat(a.atSequence()).isEqualTo(2);
    }

    private static io.eventlens.core.EventLensConfig.AnomalyRuleConfig ruleConfig(
            String code, String condition, String severity) {
        var rc = new io.eventlens.core.EventLensConfig.AnomalyRuleConfig();
        rc.setCode(code);
        rc.setCondition(condition);
        rc.setSeverity(severity);
        return rc;
    }

    @Test
    void customRuleIsInvokedAndIncludedInResults() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"balance\":100}")
        );

        var reader = inMemory(events);
        var replayEngine = new ReplayEngine(reader, new io.eventlens.core.aggregator.ReducerRegistry());
        var detector = new AnomalyDetector(reader, replayEngine);

        detector.addRule(new AnomalyDetector.AnomalyRule(
                "ALWAYS",
                "Always triggers",
                AnomalyReport.Severity.LOW,
                (e, state) -> true));

        List<AnomalyReport> anomalies = detector.scan("ACC-001");

        assertThat(anomalies.stream().map(AnomalyReport::code)).contains("ALWAYS");
    }

    @Test
    void scanRecentGroupsByAggregate() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":-5}"),
                new StoredEvent(UUID.randomUUID(), "ACC-002", "BankAccount", 1,
                        "AccountCreated", "{\"balance\":-1}", "{}", Instant.now(), 1)
        );

        var reader = inMemory(events);
        var replayEngine = new ReplayEngine(reader, new io.eventlens.core.aggregator.ReducerRegistry());
        var config = new io.eventlens.core.EventLensConfig.AnomalyConfig();
        config.setRules(List.of(ruleConfig("NEGATIVE_BALANCE", "balance < 0", "HIGH")));
        var detector = new AnomalyDetector(reader, replayEngine, config);

        List<AnomalyReport> anomalies = detector.scanRecent(10);

        // Both aggregates should be scanned and emit at least one anomaly
        assertThat(anomalies)
                .extracting(AnomalyReport::aggregateId)
                .contains("ACC-001", "ACC-002");
    }
}

