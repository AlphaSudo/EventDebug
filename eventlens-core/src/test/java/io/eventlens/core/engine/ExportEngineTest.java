package io.eventlens.core.engine;

import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportEngineTest {

    private StoredEvent event(long seq, String type, String payload) {
        return new StoredEvent(
                UUID.randomUUID(),
                "ACC-001",
                "BankAccount",
                seq,
                type,
                payload,
                "{}",
                Instant.parse("2024-01-01T00:00:00Z"),
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
    void jsonExportContainsAggregateIdAndEvents() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"amount\":50}")
        );

        var replay = new ReplayEngine(inMemory(events), new io.eventlens.core.aggregator.ReducerRegistry());
        var exporter = new ExportEngine(inMemory(events), replay);

        String json = exporter.export("ACC-001", ExportEngine.Format.JSON);

        assertThat(json).contains("\"aggregateId\":\"ACC-001\"");
        assertThat(json).contains("\"eventCount\":2");
        assertThat(json).contains("AccountCreated");
        assertThat(json).contains("MoneyDeposited");
    }

    @Test
    void markdownExportContainsTableAndEventTypes() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"amount\":50}")
        );

        var replay = new ReplayEngine(inMemory(events), new io.eventlens.core.aggregator.ReducerRegistry());
        var exporter = new ExportEngine(inMemory(events), replay);

        String md = exporter.export("ACC-001", ExportEngine.Format.MARKDOWN);

        assertThat(md).contains("| # | Event Type | Timestamp | Changes |");
        assertThat(md).contains("AccountCreated");
        assertThat(md).contains("MoneyDeposited");
    }

    @Test
    void csvExportHasHeaderAndRows() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}"),
                event(2, "MoneyDeposited", "{\"amount\":50}")
        );

        var replay = new ReplayEngine(inMemory(events), new io.eventlens.core.aggregator.ReducerRegistry());
        var exporter = new ExportEngine(inMemory(events), replay);

        String csv = exporter.export("ACC-001", ExportEngine.Format.CSV);

        assertThat(csv).startsWith("sequence,event_type,timestamp,payload");
        assertThat(csv).contains("AccountCreated");
        assertThat(csv).contains("MoneyDeposited");
    }

    @Test
    void junitFixtureExportBuildsJavaClassSkeleton() {
        var events = List.of(
                event(1, "AccountCreated", "{\"balance\":0}")
        );

        var replay = new ReplayEngine(inMemory(events), new io.eventlens.core.aggregator.ReducerRegistry());
        var exporter = new ExportEngine(inMemory(events), replay);

        String fixture = exporter.export("ACC-001", ExportEngine.Format.JUNIT_FIXTURE);

        assertThat(fixture).contains("class ACC_001Fixture");
        assertThat(fixture).contains("AccountCreated");
    }
}

