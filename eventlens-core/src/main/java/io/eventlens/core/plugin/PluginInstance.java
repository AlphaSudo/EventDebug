package io.eventlens.core.plugin;

import io.eventlens.spi.HealthStatus;
import io.eventlens.spi.PluginLifecycle;

import java.time.Instant;

/**
 * Represents a loaded plugin instance with its lifecycle state and health.
 */
public record PluginInstance(
        String instanceId,
        String typeId,
        String displayName,
        PluginType pluginType,
        Object plugin,
        PluginLifecycle lifecycle,
        HealthStatus health,
        Instant lastHealthCheck,
        String failureReason
) {
    public enum PluginType {
        EVENT_SOURCE,
        STREAM_ADAPTER,
        REDUCER
    }

    public PluginInstance withLifecycle(PluginLifecycle newLifecycle) {
        return new PluginInstance(instanceId, typeId, displayName, pluginType, plugin, 
                newLifecycle, health, lastHealthCheck, failureReason);
    }

    public PluginInstance withHealth(HealthStatus newHealth, Instant checkTime) {
        PluginLifecycle newLifecycle = lifecycle;
        if (newHealth.state() == HealthStatus.State.DOWN && lifecycle == PluginLifecycle.READY) {
            newLifecycle = PluginLifecycle.DEGRADED;
        } else if (newHealth.state() == HealthStatus.State.UP && lifecycle == PluginLifecycle.DEGRADED) {
            newLifecycle = PluginLifecycle.READY;
        }
        return new PluginInstance(instanceId, typeId, displayName, pluginType, plugin, 
                newLifecycle, newHealth, checkTime, failureReason);
    }

    public PluginInstance withFailure(String reason) {
        return new PluginInstance(instanceId, typeId, displayName, pluginType, plugin, 
                PluginLifecycle.FAILED, health, lastHealthCheck, reason);
    }
}
