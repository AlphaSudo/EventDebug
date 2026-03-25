package io.eventlens.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Map;

public interface EventSourcePlugin extends AutoCloseable {
    String typeId();

    String displayName();

    default int spiVersion() {
        return SpiVersions.CURRENT;
    }

    void initialize(String instanceId, Map<String, Object> config);

    EventQueryResult query(EventQuery query);

    default EventSourceCapabilities capabilities() {
        return EventSourceCapabilities.basic();
    }

    HealthStatus healthCheck();

    default JsonNode configSchema() {
        return NullNode.getInstance();
    }

    @Override
    default void close() {
        // Optional for plugins with no resources to release.
    }
}
