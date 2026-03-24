package io.eventlens.cli;

import io.eventlens.api.EventLensServer;
import io.eventlens.api.websocket.LiveTailWebSocket;
import io.eventlens.core.ConfigLoader;
import io.eventlens.core.ConfigValidator;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.ClasspathReducerLoader;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.AnomalyDetector;
import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.engine.DiffEngine;
import io.eventlens.core.engine.ExportEngine;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.plugin.PluginDiscovery;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.core.spi.ResilientEventStoreReader;
import io.eventlens.kafka.KafkaStreamAdapterPlugin;
import io.eventlens.pg.PostgresEventSourcePlugin;
import io.eventlens.spi.Event;
import io.eventlens.spi.EventSourcePlugin;
import io.eventlens.spi.StreamAdapterPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.HashMap;
import java.util.Map;

@Command(name = "serve", description = "Start the EventLens web server")
public class ServeCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ServeCommand.class);

    @Option(names = "--port", description = "HTTP port (default: from config or 9090)")
    Integer port;

    @Option(names = "--db-url", description = "PostgreSQL JDBC URL (overrides config)")
    String dbUrl;

    @Option(names = "--db-user", description = "Database username")
    String dbUser;

    @Option(names = "--db-password", description = "Database password")
    String dbPassword;

    @Option(names = "--table", description = "Event store table name (auto-detected if omitted)")
    String tableName;

    @Option(names = "--kafka-brokers", description = "Kafka bootstrap servers (optional)")
    String kafkaBrokers;

    @Option(names = "--kafka-topic", description = "Kafka topic for live events")
    String kafkaTopic;

    @Option(names = "--classpath", split = ",", description = "JAR files containing custom AggregateReducer implementations")
    java.util.List<String> classpathJars;

    @Option(names = "--config", description = "Path to eventlens.yaml config file")
    String configPath;

    @Override
    public void run() {
        EventLensConfig config = configPath != null ? ConfigLoader.load(configPath) : ConfigLoader.load();

        if (port != null) config.getServer().setPort(port);
        if (dbUrl != null) config.getDatasource().setUrl(dbUrl);
        if (dbUser != null) config.getDatasource().setUsername(dbUser);
        if (dbPassword != null) config.getDatasource().setPassword(dbPassword);
        if (tableName != null) config.getDatasource().setTable(tableName);

        ConfigValidator.validateOrThrow(config);

        PluginManager pluginManager = new PluginManager(config.getPlugins().getHealthCheckIntervalSeconds());
        registerBuiltInPlugins(config, pluginManager);
        pluginManager.startHealthChecks();

        EventSourcePlugin sourcePlugin = pluginManager.getFirstReadyEventSource()
                .orElseThrow(() -> new IllegalStateException("No ready event source plugin found"));
        if (!(sourcePlugin instanceof EventStoreReader sourceReader)) {
            throw new IllegalStateException("Selected event source plugin does not implement EventStoreReader: " + sourcePlugin.getClass().getName());
        }

        var reader = new ResilientEventStoreReader(sourceReader);
        var registry = new ReducerRegistry();
        if (classpathJars != null && !classpathJars.isEmpty()) {
            var loader = new ClasspathReducerLoader();
            loader.loadAll(registry, classpathJars, config.getReplay().getReducers());
        }

        var replayEngine = new ReplayEngine(reader, registry);
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, config.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        var server = new EventLensServer(config, reader, replayEngine, bisectEngine, anomalyDetector, exportEngine, diffEngine);
        var auditLogger = new AuditLogger(config.getAudit().isEnabled());
        var liveTail = new LiveTailWebSocket(reader, auditLogger);

        pluginManager.getFirstReadyStreamAdapter().ifPresentOrElse(
                stream -> startStreaming(stream, liveTail),
                liveTail::startPolling);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pluginManager.close();
            } catch (Exception e) {
                log.warn("Failed to close plugin manager cleanly", e);
            }
        }, "eventlens-plugin-shutdown"));

        server.start();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void registerBuiltInPlugins(EventLensConfig config, PluginManager pluginManager) {
        PluginDiscovery discovery = new PluginDiscovery();
        PluginDiscovery.DiscoveryResult discovered = discovery.discoverFromClasspath()
                .merge(discovery.discoverFromDirectory(config.getPlugins().getDirectory()));

        EventSourcePlugin postgres = discovered.eventSources().stream()
                .filter(plugin -> "postgres".equals(plugin.typeId()))
                .findFirst()
                .orElseGet(PostgresEventSourcePlugin::new);
        pluginManager.registerEventSource("default", postgres, postgresConfig(config));

        String brokers = kafkaBrokers != null ? kafkaBrokers : config.getKafka() != null ? config.getKafka().getBootstrapServers() : null;
        String topic = kafkaTopic != null ? kafkaTopic : config.getKafka() != null ? config.getKafka().getTopic() : null;
        if (brokers != null && topic != null) {
            StreamAdapterPlugin kafka = discovered.streamAdapters().stream()
                    .filter(plugin -> "kafka".equals(plugin.typeId()))
                    .findFirst()
                    .orElseGet(KafkaStreamAdapterPlugin::new);
            try {
                pluginManager.registerStreamAdapter("default-kafka", kafka, Map.of(
                        "bootstrapServers", brokers,
                        "topic", topic));
                log.info("Kafka stream adapter registered for topic: {}", topic);
            } catch (Exception e) {
                log.warn("Kafka unavailable ({}) - using PostgreSQL polling fallback", e.getMessage());
            }
        }
    }

    private Map<String, Object> postgresConfig(EventLensConfig config) {
        Map<String, Object> sourceConfig = new HashMap<>();
        sourceConfig.put("jdbcUrl", config.getDatasource().getUrl());
        sourceConfig.put("username", config.getDatasource().getUsername());
        sourceConfig.put("password", config.getDatasource().getPassword());
        sourceConfig.put("tableName", config.getDatasource().getTable());
        sourceConfig.put("columnOverrides", config.getDatasource().getColumns());
        sourceConfig.put("pool", config.getDatasource().getPool());
        sourceConfig.put("queryTimeoutSeconds", config.getDatasource().getQueryTimeoutSeconds());
        return sourceConfig;
    }

    private void startStreaming(StreamAdapterPlugin streamAdapter, LiveTailWebSocket liveTail) {
        try {
            streamAdapter.subscribe(event -> liveTail.broadcast(toStoredEvent(event)));
        } catch (Exception e) {
            log.warn("Stream adapter subscribe failed ({}); using PostgreSQL polling fallback", e.getMessage());
            liveTail.startPolling();
        }
    }

    private StoredEvent toStoredEvent(Event event) {
        return new StoredEvent(
                event.eventId(),
                event.aggregateId(),
                event.aggregateType(),
                event.sequenceNumber(),
                event.eventType(),
                io.eventlens.core.JsonUtil.toJson(event.payload()),
                io.eventlens.core.JsonUtil.toJson(event.metadata()),
                event.timestamp(),
                event.globalPosition());
    }
}
