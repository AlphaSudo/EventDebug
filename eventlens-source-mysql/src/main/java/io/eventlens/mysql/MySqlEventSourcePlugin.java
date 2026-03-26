package io.eventlens.mysql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eventlens.core.EventLensConfig.ColumnMappingConfig;
import io.eventlens.core.EventLensConfig.PoolConfig;
import io.eventlens.core.JsonUtil;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.spi.Event;
import io.eventlens.spi.EventQuery;
import io.eventlens.spi.EventQueryResult;
import io.eventlens.spi.EventSourceCapabilities;
import io.eventlens.spi.EventSourcePlugin;
import io.eventlens.spi.EventStatistics;
import io.eventlens.spi.EventStatisticsQuery;
import io.eventlens.spi.HealthStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class MySqlEventSourcePlugin implements EventSourcePlugin, EventStoreReader {

    private volatile MySqlEventStoreReader reader;

    @Override
    public String typeId() {
        return "mysql";
    }

    @Override
    public String displayName() {
        return "MySQL Event Store";
    }

    @Override
    public void initialize(String instanceId, Map<String, Object> config) {
        this.reader = new MySqlEventStoreReader(new MySqlConfig(
                requireString(config, "jdbcUrl"),
                requireString(config, "username"),
                Objects.toString(config.getOrDefault("password", ""), ""),
                blankToNull(Objects.toString(config.get("tableName"), null)),
                config.get("columnOverrides") instanceof ColumnMappingConfig columnOverrides ? columnOverrides : new ColumnMappingConfig(),
                config.get("pool") instanceof PoolConfig pool ? pool : new PoolConfig(),
                config.get("queryTimeoutSeconds") instanceof Number n ? n.intValue() : 30));
    }

    @Override
    public EventSourceCapabilities capabilities() {
        return new EventSourceCapabilities(true, true, true, true, Set.of("aggregate_id", "aggregate_type", "event_type", "timestamp"));
    }

    @Override
    public EventQueryResult query(EventQuery query) {
        MySqlEventStoreReader activeReader = requireReader();
        if (query.type() == EventQuery.QueryType.TIMELINE) {
            List<StoredEvent> events = query.cursor() != null && !query.cursor().isBlank()
                    ? activeReader.getEventsAfterSequence(query.aggregateId(), Long.parseLong(query.cursor()), query.limit() + 1)
                    : activeReader.getEvents(query.aggregateId(), query.limit() + 1, 0);
            boolean hasMore = events.size() > query.limit();
            List<StoredEvent> page = hasMore ? events.subList(0, query.limit()) : events;
            String nextCursor = hasMore && !page.isEmpty() ? Long.toString(page.get(page.size() - 1).sequenceNumber()) : null;
            return new EventQueryResult(page.stream().map(event -> toSpiEvent(event, query.fields())).toList(), hasMore, nextCursor);
        }
        String searchTerm = query.aggregateId() != null ? query.aggregateId() : "";
        List<String> ids = activeReader.searchAggregates(searchTerm, query.limit());
        List<Event> events = ids.stream().map(id -> activeReader.getEvents(id, 1, 0)).filter(list -> !list.isEmpty()).map(list -> toSpiEvent(list.get(0), query.fields())).toList();
        return new EventQueryResult(events, false, null);
    }

    @Override
    public HealthStatus healthCheck() {
        try {
            requireReader().getAggregateTypes();
            return HealthStatus.up();
        } catch (Exception e) {
            return HealthStatus.down(e.getMessage() != null ? e.getMessage() : "mysql health check failed");
        }
    }

    @Override
    public EventStatistics statistics(EventStatisticsQuery query) {
        return requireReader().statistics(query);
    }

    @Override
    public void close() {
        MySqlEventStoreReader activeReader = reader;
        reader = null;
        if (activeReader != null) {
            activeReader.close();
        }
    }

    @Override public List<StoredEvent> getEvents(String aggregateId) { return requireReader().getEvents(aggregateId); }
    @Override public List<StoredEvent> getEvents(String aggregateId, int limit, int offset) { return requireReader().getEvents(aggregateId, limit, offset); }
    @Override public List<StoredEvent> getEventsAfterSequence(String aggregateId, long afterSequence, int limit) { return requireReader().getEventsAfterSequence(aggregateId, afterSequence, limit); }
    @Override public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) { return requireReader().getEventsUpTo(aggregateId, maxSequence); }
    @Override public List<String> findAggregateIds(String aggregateType, int limit, int offset) { return requireReader().findAggregateIds(aggregateType, limit, offset); }
    @Override public List<StoredEvent> getRecentEvents(int limit) { return requireReader().getRecentEvents(limit); }
    @Override public List<StoredEvent> getEventsAfter(long globalPosition, int limit) { return requireReader().getEventsAfter(globalPosition, limit); }
    @Override public long countEvents(String aggregateId) { return requireReader().countEvents(aggregateId); }
    @Override public List<String> getAggregateTypes() { return requireReader().getAggregateTypes(); }
    @Override public List<String> searchAggregates(String query, int limit) { return requireReader().searchAggregates(query, limit); }

    private MySqlEventStoreReader requireReader() {
        MySqlEventStoreReader activeReader = reader;
        if (activeReader == null) throw new IllegalStateException("MySQL plugin is not initialized");
        return activeReader;
    }

    private static Event toSpiEvent(StoredEvent event, EventQuery.Fields fields) {
        return new Event(event.eventId(), event.aggregateId(), event.aggregateType(), event.sequenceNumber(), event.eventType(), fields == EventQuery.Fields.METADATA ? emptyObject() : parseJson(event.payload()), parseJson(event.metadata()), event.timestamp(), event.globalPosition());
    }

    private static JsonNode parseJson(String json) {
        try { return JsonUtil.mapper().readTree(json == null || json.isBlank() ? "{}" : json); }
        catch (Exception e) { ObjectNode fallback = JsonUtil.mapper().createObjectNode(); fallback.put("raw", json == null ? "" : json); return fallback; }
    }

    private static ObjectNode emptyObject() { return JsonUtil.mapper().createObjectNode(); }
    private static String requireString(Map<String, Object> config, String key) { Object value = config.get(key); if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("Missing required mysql config: " + key); return value.toString(); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
