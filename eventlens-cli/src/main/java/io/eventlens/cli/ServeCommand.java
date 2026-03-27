package io.eventlens.cli;

import io.eventlens.api.EventLensServer;
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
import io.eventlens.core.metadata.MetadataDatabase;
import io.eventlens.core.plugin.PluginDiscovery;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.core.spi.ResilientEventStoreReader;
import io.eventlens.mysql.MySqlEventSourcePlugin;
import io.eventlens.kafka.KafkaStreamAdapterPlugin;
import io.eventlens.pg.PostgresEventSourcePlugin;
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

        var validationIssues = ConfigValidator.validate(config);
        validationIssues.stream()
                .filter(issue -> issue.severity() == ConfigValidator.ValidationError.Severity.WARNING)
                .forEach(issue -> log.warn("Config warning {}: {}", issue.path(), issue.message()));
        ConfigValidator.validateOrThrow(config);
        MetadataDatabase metadataDatabase = MetadataDatabase.open(
                config.getSecurity() != null ? config.getSecurity().getMetadata() : null);

        PluginManager pluginManager = new PluginManager(config.getPlugins().getHealthCheckIntervalSeconds());
        PluginDiscovery.DiscoveryResult discovered = new PluginDiscovery().discoverFromClasspath()
                .merge(new PluginDiscovery().discoverFromDirectory(config.getPlugins().getDirectory()));

        registerDatasources(config, pluginManager, discovered);
        registerStreams(config, pluginManager, discovered);
        pluginManager.startHealthChecks();

        String primaryDatasourceId = selectPrimaryDatasourceId(config, pluginManager);
        EventStoreReader sourceReader = selectReader(primaryDatasourceId, pluginManager);
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

        var server = new EventLensServer(
                config,
                reader,
                replayEngine,
                registry,
                pluginManager,
                primaryDatasourceId,
                bisectEngine,
                anomalyDetector,
                exportEngine,
                diffEngine,
                datasourceStreamBindings(config),
                metadataDatabase);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                metadataDatabase.close();
            } catch (Exception e) {
                log.warn("Failed to close metadata database cleanly", e);
            }
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
            // Always create a fresh plugin instance per datasource to avoid shared state.
            // The discovered list is only used to verify the type is supported.
            boolean supported = discovered.eventSources().stream()
                    .anyMatch(candidate -> ds.getType().equalsIgnoreCase(candidate.typeId()));
            EventSourcePlugin plugin = supported || isBuiltinType(ds.getType())
                    ? createBuiltinDatasource(ds.getType())
                    : discovered.eventSources().stream()
                            .filter(candidate -> ds.getType().equalsIgnoreCase(candidate.typeId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Unsupported datasource type: " + ds.getType()));
            pluginManager.registerEventSource(ds.getId(), plugin, datasourceConfig(ds));
        }
    }

    private boolean isBuiltinType(String type) {
        return "postgres".equalsIgnoreCase(type) || "mysql".equalsIgnoreCase(type);
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

    private String selectPrimaryDatasourceId(EventLensConfig config, PluginManager pluginManager) {
        for (EventLensConfig.DatasourceInstanceConfig ds : config.getDatasourcesOrLegacy()) {
            var plugin = pluginManager.getEventSource(ds.getId()).orElse(null);
            if (plugin instanceof EventStoreReader) {
                return ds.getId();
            }
        }
        return pluginManager.listByType(io.eventlens.core.plugin.PluginInstance.PluginType.EVENT_SOURCE).stream()
                .filter(instance -> instance.plugin() instanceof EventStoreReader)
                .findFirst()
                .map(io.eventlens.core.plugin.PluginInstance::instanceId)
                .orElseThrow(() -> new IllegalStateException("No ready event source plugin found"));
    }

    private EventStoreReader selectReader(String datasourceId, PluginManager pluginManager) {
        return pluginManager.getEventSource(datasourceId)
                .filter(EventStoreReader.class::isInstance)
                .map(EventStoreReader.class::cast)
                .orElseThrow(() -> new IllegalStateException("No ready event source plugin found for id: " + datasourceId));
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

    private Map<String, String> datasourceStreamBindings(EventLensConfig config) {
        Map<String, String> bindings = new HashMap<>();
        for (EventLensConfig.DatasourceInstanceConfig datasource : config.getDatasourcesOrLegacy()) {
            if (datasource == null || !datasource.isEnabled()) continue;
            if (datasource.getStreamId() != null) {
                bindings.put(datasource.getId(), datasource.getStreamId());
            }
        }
        return bindings;
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
}
