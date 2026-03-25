package io.eventlens.core.plugin;

import io.eventlens.spi.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    private PluginManager manager;

    @AfterEach
    void cleanup() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void testRegisterEventSource_Success() {
        manager = new PluginManager(60);
        TestEventSourcePlugin plugin = new TestEventSourcePlugin(true);

        manager.registerEventSource("test-source", plugin, Map.of());

        List<PluginInstance> instances = manager.listAll();
        assertEquals(1, instances.size());

        PluginInstance instance = instances.get(0);
        assertEquals("test-source", instance.instanceId());
        assertEquals("test-datasource", instance.typeId());
        assertEquals(PluginLifecycle.READY, instance.lifecycle());
        assertEquals(HealthStatus.State.UP, instance.health().state());
    }

    @Test
    void testRegisterEventSource_InitFailure() {
        manager = new PluginManager(60);
        TestEventSourcePlugin plugin = new TestEventSourcePlugin(false);

        manager.registerEventSource("failing-source", plugin, Map.of());

        PluginInstance instance = manager.getInstance("failing-source").orElseThrow();
        assertEquals(PluginLifecycle.FAILED, instance.lifecycle());
        assertNotNull(instance.failureReason());
    }

    @Test
    void testRegisterReducer() {
        manager = new PluginManager(60);
        TestReducerPlugin plugin = new TestReducerPlugin();

        manager.registerReducer("test-reducer", plugin);

        PluginInstance instance = manager.getInstance("test-reducer").orElseThrow();
        assertEquals(PluginLifecycle.READY, instance.lifecycle());
        assertEquals(PluginInstance.PluginType.REDUCER, instance.pluginType());
    }

    @Test
    void testHealthTransition_ReadyToDegraded() throws InterruptedException {
        manager = new PluginManager(1); // 1 second health check
        TestEventSourcePlugin plugin = new TestEventSourcePlugin(true);

        manager.registerEventSource("test-source", plugin, Map.of());
        manager.startHealthChecks();

        // Initially ready
        PluginInstance initial = manager.getInstance("test-source").orElseThrow();
        assertEquals(PluginLifecycle.READY, initial.lifecycle());

        // Make health check fail
        plugin.setHealthy(false);
        Thread.sleep(1500); // Wait for health check

        PluginInstance degraded = manager.getInstance("test-source").orElseThrow();
        assertEquals(PluginLifecycle.DEGRADED, degraded.lifecycle());
        assertEquals(HealthStatus.State.DOWN, degraded.health().state());
    }

    @Test
    void testHealthTransition_DegradedToReady() throws InterruptedException {
        manager = new PluginManager(1);
        TestEventSourcePlugin plugin = new TestEventSourcePlugin(true); // Start healthy

        manager.registerEventSource("test-source", plugin, Map.of());
        
        // Make it unhealthy before starting health checks
        plugin.setHealthy(false);
        manager.startHealthChecks();
        
        Thread.sleep(1500); // Wait for first health check to make it degraded

        // Should be degraded now
        PluginInstance degraded = manager.getInstance("test-source").orElseThrow();
        assertEquals(PluginLifecycle.DEGRADED, degraded.lifecycle());

        // Make health check succeed
        plugin.setHealthy(true);
        Thread.sleep(1500);

        PluginInstance ready = manager.getInstance("test-source").orElseThrow();
        assertEquals(PluginLifecycle.READY, ready.lifecycle());
        assertEquals(HealthStatus.State.UP, ready.health().state());
    }

    @Test
    void testListByType() {
        manager = new PluginManager(60);
        manager.registerEventSource("source1", new TestEventSourcePlugin(true), Map.of());
        manager.registerStreamAdapter("stream1", new TestStreamAdapterPlugin(true), Map.of());
        manager.registerReducer("reducer1", new TestReducerPlugin());

        List<PluginInstance> sources = manager.listByType(PluginInstance.PluginType.EVENT_SOURCE);
        List<PluginInstance> streams = manager.listByType(PluginInstance.PluginType.STREAM_ADAPTER);
        List<PluginInstance> reducers = manager.listByType(PluginInstance.PluginType.REDUCER);

        assertEquals(1, sources.size());
        assertEquals(1, streams.size());
        assertEquals(1, reducers.size());
    }

    // Test plugins
    static class TestEventSourcePlugin implements EventSourcePlugin {
        private boolean healthy;

        TestEventSourcePlugin(boolean healthy) {
            this.healthy = healthy;
        }

        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }

        @Override
        public String typeId() {
            return "test-datasource";
        }

        @Override
        public String displayName() {
            return "Test Datasource";
        }

        @Override
        public void initialize(String instanceId, Map<String, Object> config) {
            if (!healthy) {
                throw new RuntimeException("Initialization failed");
            }
        }

        @Override
        public EventQueryResult query(EventQuery query) {
            return new EventQueryResult(List.of(), false, null);
        }

        @Override
        public HealthStatus healthCheck() {
            return healthy ? HealthStatus.up() : HealthStatus.down("Unhealthy");
        }
    }

    static class TestStreamAdapterPlugin implements StreamAdapterPlugin {
        private boolean healthy;

        TestStreamAdapterPlugin(boolean healthy) {
            this.healthy = healthy;
        }

        @Override
        public String typeId() {
            return "test-stream";
        }

        @Override
        public String displayName() {
            return "Test Stream";
        }

        @Override
        public void initialize(String instanceId, Map<String, Object> config) {
            if (!healthy) {
                throw new RuntimeException("Initialization failed");
            }
        }

        @Override
        public void subscribe(java.util.function.Consumer<Event> listener) {
        }

        @Override
        public void unsubscribe() {
        }

        @Override
        public HealthStatus healthCheck() {
            return healthy ? HealthStatus.up() : HealthStatus.down("Unhealthy");
        }
    }

    static class TestReducerPlugin implements ReducerPlugin {
        @Override
        public String typeId() {
            return "test-reducer";
        }

        @Override
        public String displayName() {
            return "Test Reducer";
        }

        @Override
        public String aggregateType() {
            return "TestAggregate";
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode reduce(List<Event> events) {
            return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        }
    }
}
