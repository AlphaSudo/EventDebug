package io.eventlens.spi;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public interface ReducerPlugin {
    String typeId();

    String displayName();

    default int spiVersion() {
        return SpiVersions.CURRENT;
    }

    String aggregateType();

    JsonNode reduce(List<Event> events);
}
