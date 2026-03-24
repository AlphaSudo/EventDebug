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
import io.eventlens.mysql.MySqlEventSourcePlugin;
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
import java.util.List;
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
        PluginDiscovery.DiscoveryResult discovered = new PluginDiscovery().discoverFromClasspath()
                .merge(new PluginDiscovery().discoverFromDirectory(config.getPlugins().getDirectory()));

        registerDatasources(config, pluginManager, discovered);
        registerStreams(config, pluginManager, discovered);
        pluginManager.startHealthChecks();

        EventStoreReader sourceReader = selectPrimaryReader(config, pluginManager);
        var reader = new ResilientEventStoreReader(sourceReader);
        var registry = new ReducerRegistry();
        if (classpathJars != null && !classpathJars.isEmpty()) {
            new ClasspathReducerLoader().loadAll(registry, classpathJars, config.getReplay().getReducers());
        }

        var replayEngine = new ReplayEngine(reader, registry);
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, config.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        var server = new EventLensServer(config, reader, replayEngine, bisectEngine, anomalyDetector, exportEngine, diffEngine);
        var auditLogger = new AuditLogger(config.getAudit().isEnabled());
        var liveTail = new LiveTailWebSocket(reader, auditLogger);

        selectPrimaryStream(config, pluginManager).ifPresentOrElse(
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

    private void registerDatasources(EventLensConfig config, PluginManager pluginManager, PluginDiscovery.DiscoveryResult discovered) {
        for (EventLensConfig.DatasourceInstanceConfig ds : config.getDatasourcesOrLegacy()) {
            if (ds == null || !ds.isEnabled()) continue;
            EventSourcePlugin plugin = discovered.eventSources().stream()
                    .filter(candidate -> ds.getType().equalsIgnoreCase(candidate.typeId()))
                    .findFirst()
                    .orElseGet(() -> createBuiltinDatasource(ds.getType()));
            pluginManager.registerEventSource(ds.getId(), plugin, datasourceConfig(ds));
        }
    }

    private void registerStreams(EventLensConfig config, PluginManager pluginManager, PluginDiscovery.DiscoveryResult discovered) {
        List<EventLensConfig.StreamInstanceConfig> streams = config.getStreamsOrLegacy();
        for (int i = 0; i < streams.size(); i++) {
            EventLensConfig.StreamInstanceConfig stream = streams.get(i);
            if (stream == null || !stream.isEnabled()) continue;
            if (i == 0 && kafkaBrokers != null) stream.setBootstrapServers(kafkaBrokers);
            if (i == 0 && kafkaTopic != null) stream.setTopic(kafkaTopic);
            StreamAdapterPlugin plugin = discovered.streamAdapters().stream()
                    .filter(candidate -> stream.getType().equalsIgnoreCase(candidate.typeId()))
                    .findFirst()
                    .orElseGet(() -> createBuiltinStream(stream.getType()));
            try {
                pluginManager.registerStreamAdapter(stream.getId(), plugin, Map.of(
                        "bootstrapServers", stream.getBootstrapServers(),
                        "topic", stream.getTopic()));
            } catch (Exception e) {
                log.warn("Stream '{}' unavailable ({}). Skipping.", stream.getId(), e.getMessage());
            }
        }
    }

    private EventStoreReader selectPrimaryReader(EventLensConfig config, PluginManager pluginManager) {
        for (EventLensConfig.DatasourceInstanceConfig ds : config.getDatasourcesOrLegacy()) {
            var plugin = pluginManager.getEventSource(ds.getId()).orElse(null);
            if (plugin instanceof EventStoreReader reader) {
                return reader;
            }
        }
        return pluginManager.getFirstReadyEventSource()
                .filter(EventStoreReader.class::isInstance)
                .map(EventStoreReader.class::cast)
                .orElseThrow(() -> new IllegalStateException("No ready event source plugin found"));
    }

    private java.util.Optional<StreamAdapterPlugin> selectPrimaryStream(EventLensConfig config, PluginManager pluginManager) {
        for (EventLensConfig.StreamInstanceConfig stream : config.getStreamsOrLegacy()) {
            var plugin = pluginManager.getStreamAdapter(stream.getId());
            if (plugin.isPresent()) {
                return plugin;
            }
        }
        return pluginManager.getFirstReadyStreamAdapter();
    }

    private Map<String, Object> datasourceConfig(EventLensConfig.DatasourceInstanceConfig ds) {
        Map<String, Object> sourceConfig = new HashMap<>();
        sourceConfig.put("jdbcUrl", ds.getUrl());
        sourceConfig.put("username", ds.getUsername());
        sourceConfig.put("password", ds.getPassword());
        sourceConfig.put("tableName", ds.getTable());
        sourceConfig.put("columnOverrides", ds.getColumns());
        sourceConfig.put("pool", ds.getPool());
        sourceConfig.put("queryTimeoutSeconds", ds.getQueryTimeoutSeconds());
        return sourceConfig;
    }

    private EventSourcePlugin createBuiltinDatasource(String type) {
        return switch (type.toLowerCase()) {
            case "postgres" -> new PostgresEventSourcePlugin();
            case "mysql" -> new MySqlEventSourcePlugin();
            default -> throw new IllegalArgumentException("Unsupported datasource type: " + type);
        };
    }

    private StreamAdapterPlugin createBuiltinStream(String type) {
        return switch (type.toLowerCase()) {
            case "kafka" -> new KafkaStreamAdapterPlugin();
            default -> throw new IllegalArgumentException("Unsupported stream type: " + type);
        };
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
