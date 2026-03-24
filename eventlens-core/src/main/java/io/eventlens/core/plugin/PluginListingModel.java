package io.eventlens.core.plugin;

import io.eventlens.spi.HealthStatus;
import io.eventlens.spi.PluginLifecycle;

import java.time.Instant;

/**
 * API model for plugin listing endpoint.
 */
public record PluginListingModel(
        String instanceId,
        String typeId,
        String displayName,
        String pluginType,
        String lifecycle,
        HealthStatusModel health,
        Instant lastHealthCheck,
        String failureReason
) {
    public static PluginListingModel from(PluginInstance instance) {
        return new PluginListingModel(
                instance.instanceId(),
                instance.typeId(),
                instance.displayName(),
                instance.pluginType().name().toLowerCase(),
                instance.lifecycle().name().toLowerCase(),
                HealthStatusModel.from(instance.health()),
                instance.lastHealthCheck(),
                instance.failureReason()
        );
    }

    public record HealthStatusModel(
            String state,
            String message
    ) {
        public static HealthStatusModel from(HealthStatus health) {
            return new HealthStatusModel(
                    health.state().name().toLowerCase(),
                    health.message()
            );
        }
    }
}
