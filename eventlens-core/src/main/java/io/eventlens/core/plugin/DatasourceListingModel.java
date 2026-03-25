package io.eventlens.core.plugin;

import java.util.List;

/**
 * API model for datasource listing endpoint.
 */
public record DatasourceListingModel(
        String id,
        String displayName,
        String status,
        String healthMessage,
        List<String> capabilities
) {
    public static DatasourceListingModel from(PluginInstance instance) {
        if (instance.pluginType() != PluginInstance.PluginType.EVENT_SOURCE) {
            throw new IllegalArgumentException("Instance is not an event source: " + instance.instanceId());
        }

        String status = switch (instance.lifecycle()) {
            case READY -> "ready";
            case DEGRADED -> "degraded";
            case FAILED -> "failed";
            case INITIALIZING -> "initializing";
            case DISCOVERED -> "discovered";
            case STOPPED -> "stopped";
        };

        String healthMessage = instance.health() != null
                ? java.util.Objects.toString(instance.health().message(), "")
                : "Health not yet checked";

        return new DatasourceListingModel(
                instance.instanceId(),
                instance.displayName(),
                status,
                healthMessage,
                List.of() // Capabilities will be populated in later phases
        );
    }
}
