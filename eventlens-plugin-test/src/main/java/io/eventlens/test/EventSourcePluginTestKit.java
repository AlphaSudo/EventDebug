package io.eventlens.test;

import io.eventlens.spi.EventQuery;
import io.eventlens.spi.EventSourcePlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class EventSourcePluginTestKit {

    protected EventSourcePlugin plugin;

    @BeforeEach
    void initializePluginUnderTest() throws Exception {
        plugin = createPlugin();
        seedCanonicalEvents();
    }

    @AfterEach
    void cleanupPluginUnderTest() throws Exception {
        if (plugin != null) {
            plugin.close();
        }
        cleanupStore();
    }

    protected abstract EventSourcePlugin createPlugin() throws Exception;

    protected abstract void seedCanonicalEvents() throws Exception;

    protected abstract void cleanupStore() throws Exception;

    @Test
    void healthCheckReportsUpForSeededPlugin() {
        assertThat(plugin.healthCheck().state().name()).isEqualTo("UP");
    }

    @Test
    void timelineQueryReturnsEventsInSequenceOrder() {
        var result = plugin.query(EventQuery.builder(EventQuery.QueryType.TIMELINE)
                .aggregateId(CanonicalEventSet.PRIMARY_AGGREGATE_ID)
                .limit(10)
                .build());

        PluginAssertions.assertOrderedSequence(result, 1L, 2L, 3L);
        PluginAssertions.assertEventTypes(result.events(), "AccountCreated", "MoneyDeposited", "MoneyWithdrawn");
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void timelineCursorPaginationReturnsNextWindowWithoutDuplicates() {
        var firstPage = plugin.query(EventQuery.builder(EventQuery.QueryType.TIMELINE)
                .aggregateId(CanonicalEventSet.PRIMARY_AGGREGATE_ID)
                .limit(2)
                .build());
        PluginAssertions.assertOrderedSequence(firstPage, 1L, 2L);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();

        var secondPage = plugin.query(EventQuery.builder(EventQuery.QueryType.TIMELINE)
                .aggregateId(CanonicalEventSet.PRIMARY_AGGREGATE_ID)
                .cursor(firstPage.nextCursor())
                .limit(2)
                .build());
        PluginAssertions.assertOrderedSequence(secondPage, 3L);
    }

    @Test
    void metadataOnlyTimelineOmitsPayloadButKeepsMetadata() {
        var result = plugin.query(EventQuery.builder(EventQuery.QueryType.TIMELINE)
                .aggregateId(CanonicalEventSet.PRIMARY_AGGREGATE_ID)
                .fields(EventQuery.Fields.METADATA)
                .limit(10)
                .build());

        assertThat(result.events()).isNotEmpty();
        result.events().forEach(PluginAssertions::assertMetadataOnly);
    }

    @Test
    void searchQueryReturnsMatchingAggregateSummaries() {
        var result = plugin.query(EventQuery.builder(EventQuery.QueryType.SEARCH)
                .aggregateId(CanonicalEventSet.SEARCH_TERM)
                .limit(10)
                .build());

        PluginAssertions.assertAggregateIdsPresent(result, CanonicalEventSet.PRIMARY_AGGREGATE_ID, CanonicalEventSet.SECONDARY_AGGREGATE_ID);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void emptyTimelineReturnsEmptyResult() {
        var result = plugin.query(EventQuery.builder(EventQuery.QueryType.TIMELINE)
                .aggregateId("UNKNOWN-AGGREGATE")
                .limit(10)
                .build());

        assertThat(result.events()).isEmpty();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }
}
