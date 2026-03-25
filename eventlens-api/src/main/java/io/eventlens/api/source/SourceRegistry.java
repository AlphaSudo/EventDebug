package io.eventlens.api.source;

import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.plugin.DatasourceListingModel;
import io.eventlens.core.plugin.PluginInstance;
import io.eventlens.spi.HealthStatus;
import io.eventlens.core.plugin.PluginListingModel;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.spi.PluginLifecycle;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SourceRegistry {

    private final String defaultSourceId;
    private final EventStoreReader defaultReader;
    private final ReplayEngine defaultReplayEngine;
    private final ReducerRegistry reducerRegistry;
    private final PluginManager pluginManager;
    private final Map<String, ReplayEngine> replayEngines = new ConcurrentHashMap<>();

    public SourceRegistry(
            String defaultSourceId,
            EventStoreReader defaultReader,
            ReplayEngine defaultReplayEngine,
            ReducerRegistry reducerRegistry,
            PluginManager pluginManager) {
        this.defaultSourceId = defaultSourceId;
        this.defaultReader = defaultReader;
        this.defaultReplayEngine = defaultReplayEngine;
        this.reducerRegistry = reducerRegistry;
        this.pluginManager = pluginManager;
    }

    public ResolvedSource resolve(String requestedSourceId) {
        if (requestedSourceId == null || requestedSourceId.isBlank() || requestedSourceId.equals(defaultSourceId)) {
            return new ResolvedSource(defaultSourceId, "Primary datasource", defaultReader, defaultReplayEngine, true);
        }

        PluginInstance instance = pluginManager.getInstance(requestedSourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown datasource: " + requestedSourceId));

        if (instance.pluginType() != PluginInstance.PluginType.EVENT_SOURCE) {
            throw new IllegalArgumentException("Plugin is not a datasource: " + requestedSourceId);
        }

        if (instance.lifecycle() != PluginLifecycle.READY && instance.lifecycle() != PluginLifecycle.DEGRADED) {
            throw new IllegalArgumentException("Datasource is not ready: " + requestedSourceId);
        }

        if (!(instance.plugin() instanceof EventStoreReader reader)) {
            throw new IllegalArgumentException("Datasource does not expose an EventStoreReader: " + requestedSourceId);
        }

        ReplayEngine replayEngine = replayEngines.computeIfAbsent(requestedSourceId, ignored -> new ReplayEngine(reader, reducerRegistry));
        return new ResolvedSource(instance.instanceId(), instance.displayName(), reader, replayEngine, false);
    }

    public List<DatasourceListingModel> listDatasources() {
        return pluginManager.listByType(PluginInstance.PluginType.EVENT_SOURCE).stream()
                .sorted(Comparator.comparing(PluginInstance::instanceId))
                .map(DatasourceListingModel::from)
                .toList();
    }

    public Map<String, Object> datasourceHealth(String datasourceId) {
        PluginInstance instance = pluginManager.getInstance(datasourceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown datasource: " + datasourceId));

        if (instance.pluginType() != PluginInstance.PluginType.EVENT_SOURCE) {
            throw new IllegalArgumentException("Plugin is not a datasource: " + datasourceId);
        }

        HealthStatus health = instance.health();
        Map<String, Object> healthMap = new LinkedHashMap<>();
        healthMap.put("state", health != null && health.state() != null
                ? health.state().name().toLowerCase() : "unknown");
        healthMap.put("message", health != null
                ? Objects.toString(health.message(), "") : "Health not yet checked");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Objects.toString(instance.instanceId(), datasourceId));
        result.put("displayName", Objects.toString(instance.displayName(), datasourceId));
        result.put("status", instance.lifecycle() != null
                ? instance.lifecycle().name().toLowerCase() : "unknown");
        result.put("health", healthMap);
        result.put("lastHealthCheck", Objects.toString(instance.lastHealthCheck(), ""));
        result.put("failureReason", Objects.toString(instance.failureReason(), ""));
        return result;
    }

    public List<PluginListingModel> listPlugins() {
        return pluginManager.listAll().stream()
                .sorted(Comparator.comparing(PluginInstance::instanceId))
                .map(PluginListingModel::from)
                .toList();
    }

    public record ResolvedSource(
            String id,
            String displayName,
            EventStoreReader reader,
            ReplayEngine replayEngine,
            boolean primary) {
    }
}
