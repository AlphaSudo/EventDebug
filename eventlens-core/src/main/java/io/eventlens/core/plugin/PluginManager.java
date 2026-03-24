package io.eventlens.core.plugin;

import io.eventlens.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Manages plugin lifecycle, health tracking, and safe shutdown.
 */
public class PluginManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final Map<String, PluginInstance> instances = new ConcurrentHashMap<>();
    private final ScheduledExecutorService healthScheduler;
    private final int healthCheckIntervalSeconds;
    private volatile boolean running = false;

    public PluginManager(int healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
        this.healthScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "plugin-health-checker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Initialize and register an event source plugin.
     */
    public void registerEventSource(String instanceId, EventSourcePlugin plugin, Map<String, Object> config) {
        registerPlugin(instanceId, plugin, PluginInstance.PluginType.EVENT_SOURCE, config,
                plugin::initialize, plugin::healthCheck);
    }

    /**
     * Initialize and register a stream adapter plugin.
     */
    public void registerStreamAdapter(String instanceId, StreamAdapterPlugin plugin, Map<String, Object> config) {
        registerPlugin(instanceId, plugin, PluginInstance.PluginType.STREAM_ADAPTER, config,
                plugin::initialize, plugin::healthCheck);
    }

    /**
     * Register a reducer plugin (no initialization needed).
     */
    public void registerReducer(String instanceId, ReducerPlugin plugin) {
        String compatError = SpiVersions.checkCompatibility(plugin.typeId(), plugin.spiVersion()).orElse(null);
        if (compatError != null) {
            log.error("Reducer plugin '{}' failed SPI version check: {}", instanceId, compatError);
            PluginInstance failed = new PluginInstance(
                    instanceId, plugin.typeId(), plugin.displayName(),
                    PluginInstance.PluginType.REDUCER, plugin,
                    PluginLifecycle.FAILED, HealthStatus.down(compatError),
                    Instant.now(), compatError
            );
            instances.put(instanceId, failed);
            return;
        }

        PluginInstance instance = new PluginInstance(
                instanceId, plugin.typeId(), plugin.displayName(),
                PluginInstance.PluginType.REDUCER, plugin,
                PluginLifecycle.READY, HealthStatus.up(),
                Instant.now(), null
        );
        instances.put(instanceId, instance);
        log.info("Registered reducer plugin: {} ({})", instanceId, plugin.displayName());
    }

    private void registerPlugin(
            String instanceId,
            Object plugin,
            PluginInstance.PluginType type,
            Map<String, Object> config,
            InitFunction initFn,
            HealthFunction healthFn
    ) {
        String typeId = getTypeId(plugin);
        String displayName = getDisplayName(plugin);
        int spiVersion = getSpiVersion(plugin);

        // SPI version check
        String compatError = SpiVersions.checkCompatibility(typeId, spiVersion).orElse(null);
        if (compatError != null) {
            log.error("Plugin '{}' failed SPI version check: {}", instanceId, compatError);
            PluginInstance failed = new PluginInstance(
                    instanceId, typeId, displayName, type, plugin,
                    PluginLifecycle.FAILED, HealthStatus.down(compatError),
                    Instant.now(), compatError
            );
            instances.put(instanceId, failed);
            return;
        }

        // Mark as initializing
        PluginInstance initializing = new PluginInstance(
                instanceId, typeId, displayName, type, plugin,
                PluginLifecycle.INITIALIZING, HealthStatus.up(),
                Instant.now(), null
        );
        instances.put(instanceId, initializing);

        try {
            initFn.initialize(instanceId, config);
            HealthStatus health = healthFn.check();
            PluginLifecycle lifecycle = health.state() == HealthStatus.State.UP
                    ? PluginLifecycle.READY
                    : PluginLifecycle.DEGRADED;

            PluginInstance ready = new PluginInstance(
                    instanceId, typeId, displayName, type, plugin,
                    lifecycle, health, Instant.now(), null
            );
            instances.put(instanceId, ready);
            log.info("Initialized plugin: {} ({}) - {}", instanceId, displayName, lifecycle);

        } catch (Exception e) {
            log.error("Failed to initialize plugin: {}", instanceId, e);
            PluginInstance failed = initializing.withFailure(e.getMessage());
            instances.put(instanceId, failed);
        }
    }

    /**
     * Start the health check scheduler.
     */
    public void startHealthChecks() {
        if (running) {
            log.warn("Health checks already running");
            return;
        }
        running = true;
        healthScheduler.scheduleAtFixedRate(
                this::refreshAllHealth,
                healthCheckIntervalSeconds,
                healthCheckIntervalSeconds,
                TimeUnit.SECONDS
        );
        log.info("Started health check scheduler (interval: {}s)", healthCheckIntervalSeconds);
    }

    private void refreshAllHealth() {
        instances.values().stream()
                .filter(i -> i.lifecycle() == PluginLifecycle.READY || i.lifecycle() == PluginLifecycle.DEGRADED)
                .forEach(this::refreshHealth);
    }

    private void refreshHealth(PluginInstance instance) {
        try {
            HealthStatus health = switch (instance.pluginType()) {
                case EVENT_SOURCE -> ((EventSourcePlugin) instance.plugin()).healthCheck();
                case STREAM_ADAPTER -> ((StreamAdapterPlugin) instance.plugin()).healthCheck();
                case REDUCER -> HealthStatus.up(); // Reducers don't have health checks
            };

            PluginInstance updated = instance.withHealth(health, Instant.now());
            instances.put(instance.instanceId(), updated);

            if (updated.lifecycle() != instance.lifecycle()) {
                log.info("Plugin '{}' transitioned: {} -> {}", 
                        instance.instanceId(), instance.lifecycle(), updated.lifecycle());
            }

        } catch (Exception e) {
            log.error("Health check failed for plugin: {}", instance.instanceId(), e);
            HealthStatus down = HealthStatus.down("Health check threw exception: " + e.getMessage());
            PluginInstance degraded = instance.withHealth(down, Instant.now());
            instances.put(instance.instanceId(), degraded);
        }
    }

    /**
     * Get all registered plugin instances.
     */
    public List<PluginInstance> listAll() {
        return List.copyOf(instances.values());
    }

    /**
     * Get plugin instances by type.
     */
    public List<PluginInstance> listByType(PluginInstance.PluginType type) {
        return instances.values().stream()
                .filter(i -> i.pluginType() == type)
                .toList();
    }

    /**
     * Get a specific plugin instance.
     */
    public Optional<PluginInstance> getInstance(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    @Override
    public void close() {
        log.info("Shutting down plugin manager...");
        running = false;

        // Stop health checks
        healthScheduler.shutdown();
        try {
            if (!healthScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                healthScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Shutdown plugins: streams first, then sources
        shutdownPluginsByType(PluginInstance.PluginType.STREAM_ADAPTER);
        shutdownPluginsByType(PluginInstance.PluginType.EVENT_SOURCE);

        log.info("Plugin manager shutdown complete");
    }

    private void shutdownPluginsByType(PluginInstance.PluginType type) {
        instances.values().stream()
                .filter(i -> i.pluginType() == type)
                .forEach(instance -> {
                    try {
                        if (instance.plugin() instanceof AutoCloseable closeable) {
                            closeable.close();
                            PluginInstance stopped = instance.withLifecycle(PluginLifecycle.STOPPED);
                            instances.put(instance.instanceId(), stopped);
                            log.info("Closed plugin: {}", instance.instanceId());
                        }
                    } catch (Exception e) {
                        log.error("Failed to close plugin: {}", instance.instanceId(), e);
                    }
                });
    }

    // Helper methods
    private String getTypeId(Object plugin) {
        if (plugin instanceof EventSourcePlugin p) return p.typeId();
        if (plugin instanceof StreamAdapterPlugin p) return p.typeId();
        if (plugin instanceof ReducerPlugin p) return p.typeId();
        return "unknown";
    }

    private String getDisplayName(Object plugin) {
        if (plugin instanceof EventSourcePlugin p) return p.displayName();
        if (plugin instanceof StreamAdapterPlugin p) return p.displayName();
        if (plugin instanceof ReducerPlugin p) return p.displayName();
        return "Unknown";
    }

    private int getSpiVersion(Object plugin) {
        if (plugin instanceof EventSourcePlugin p) return p.spiVersion();
        if (plugin instanceof StreamAdapterPlugin p) return p.spiVersion();
        if (plugin instanceof ReducerPlugin p) return p.spiVersion();
        return 0;
    }

    @FunctionalInterface
    private interface InitFunction {
        void initialize(String instanceId, Map<String, Object> config);
    }

    @FunctionalInterface
    private interface HealthFunction {
        HealthStatus check();
    }
}
