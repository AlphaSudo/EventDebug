package io.eventlens.cli;

import io.eventlens.api.EventLensServer;
import io.eventlens.api.websocket.LiveTailWebSocket;
import io.eventlens.core.ConfigLoader;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.*;
import io.eventlens.core.engine.*;
import io.eventlens.kafka.*;
import io.eventlens.pg.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Start the EventLens web server")
public class ServeCommand implements Runnable {

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
        EventLensConfig config = configPath != null
                ? ConfigLoader.load(configPath)
                : ConfigLoader.load();

        // CLI flags override config file
        if (port != null)
            config.getServer().setPort(port);
        if (dbUrl != null)
            config.getDatasource().setUrl(dbUrl);
        if (dbUser != null)
            config.getDatasource().setUsername(dbUser);
        if (dbPassword != null)
            config.getDatasource().setPassword(dbPassword);
        if (tableName != null)
            config.getDatasource().setTable(tableName);

        var pgConfig = new PgConfig(
                config.getDatasource().getUrl(),
                config.getDatasource().getUsername(),
                config.getDatasource().getPassword(),
                config.getDatasource().getTable());

        var reader = new PgEventStoreReader(pgConfig);
        var registry = new ReducerRegistry();

        // Load custom reducers from classpath JARs
        if (classpathJars != null && !classpathJars.isEmpty()) {
            var loader = new ClasspathReducerLoader();
            loader.loadAll(registry, classpathJars, config.getReplay().getReducers());
        }

        var replayEngine = new ReplayEngine(reader, registry);
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine);
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);

        // Kafka is optional — graceful degradation to PG polling
        KafkaLiveTail kafkaTail = null;
        String brokers = kafkaBrokers != null ? kafkaBrokers
                : (config.getKafka() != null ? config.getKafka().getBootstrapServers() : null);
        String topic = kafkaTopic != null ? kafkaTopic
                : (config.getKafka() != null ? config.getKafka().getTopic() : null);

        if (brokers != null && topic != null) {
            try {
                kafkaTail = new KafkaLiveTail(new KafkaConfig(brokers, topic));
                System.out.println("📡 Kafka consumer: " + topic);
            } catch (Exception e) {
                System.err.println("⚠ Kafka unavailable (" + e.getMessage() + ") — using PG polling fallback");
            }
        }

        var server = new EventLensServer(config, reader, replayEngine,
                bisectEngine, anomalyDetector, exportEngine, diffEngine);
        var liveTail = new LiveTailWebSocket(reader);
        liveTail.configure(server.getApp());

        if (kafkaTail != null) {
            var finalKafkaTail = kafkaTail;
            finalKafkaTail.addListener(liveTail::broadcast);
            finalKafkaTail.start();
        } else {
            liveTail.startPolling();
        }

        server.start();

        // Block the main thread to keep the JVM alive (Javalin runs on daemon threads)
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
