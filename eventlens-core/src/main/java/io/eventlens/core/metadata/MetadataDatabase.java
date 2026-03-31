package io.eventlens.core.metadata;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.exception.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite-backed metadata bootstrap for v5 security features.
 *
 * <p>The scope is intentionally narrow: sessions, API keys, and audit storage.
 * Feature-specific repositories land in later epics on top of this seam.</p>
 */
public final class MetadataDatabase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetadataDatabase.class);

    private final boolean enabled;
    private final HikariDataSource dataSource;

    private MetadataDatabase(boolean enabled, HikariDataSource dataSource) {
        this.enabled = enabled;
        this.dataSource = dataSource;
    }

    public static MetadataDatabase disabled() {
        return new MetadataDatabase(false, null);
    }

    public static MetadataDatabase open(EventLensConfig.MetadataConfig config) {
        if (config == null || !config.isEnabled()) {
            return disabled();
        }
        if (config.getJdbcUrl() == null || !config.getJdbcUrl().startsWith("jdbc:sqlite:")) {
            throw new ConfigurationException("security.metadata.jdbc-url must be a SQLite JDBC URL when metadata is enabled");
        }

        ensureSqliteParentDirectory(config.getJdbcUrl());

        // Explicitly load the SQLite driver — in fat JARs the
        // META-INF/services/java.sql.Driver merge may lose entries.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new ConfigurationException(
                    "SQLite JDBC driver not found on classpath. "
                            + "Add org.xerial:sqlite-jdbc to your dependencies.", e);
        }

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.getJdbcUrl());
        hikari.setPoolName("eventlens-metadata");
        hikari.setAutoCommit(true);
        hikari.setReadOnly(false);

        var pool = config.getPool();
        if (pool != null) {
            hikari.setMaximumPoolSize(pool.getMaximumPoolSize());
            hikari.setMinimumIdle(pool.getMinimumIdle());
            hikari.setConnectionTimeout(pool.getConnectionTimeoutMs());
            hikari.setIdleTimeout(pool.getIdleTimeoutMs());
            hikari.setMaxLifetime(pool.getMaxLifetimeMs());
            hikari.setLeakDetectionThreshold(pool.getLeakDetectionThresholdMs());
        }

        HikariDataSource dataSource = new HikariDataSource(hikari);
        try {
            configurePragmas(dataSource, config);
            MetadataMigrationRunner.migrate(dataSource);
            log.info("Metadata database ready: {}", config.getJdbcUrl());
            return new MetadataDatabase(true, dataSource);
        } catch (Exception e) {
            dataSource.close();
            throw new ConfigurationException("Failed to initialize metadata database: " + e.getMessage(), e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public DataSource dataSource() {
        if (!enabled || dataSource == null) {
            throw new IllegalStateException("Metadata database is disabled");
        }
        return dataSource;
    }

    public MetadataRepositories repositories() {
        return new MetadataRepositories(dataSource());
    }

    @Override
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static void configurePragmas(HikariDataSource dataSource, EventLensConfig.MetadataConfig config) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            if (config.isWalEnabled()) {
                try (ResultSet ignored = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                    // Execute and discard result; later tests assert the effective mode.
                }
            }
            statement.execute("PRAGMA busy_timeout=" + Math.max(0, config.getBusyTimeoutMs()));
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA temp_store=MEMORY");
            statement.execute("PRAGMA foreign_keys=" + (config.isForeignKeysEnabled() ? "ON" : "OFF"));
        }
    }

    private static void ensureSqliteParentDirectory(String jdbcUrl) {
        String raw = jdbcUrl.substring("jdbc:sqlite:".length());
        if (raw.isBlank() || raw.equals(":memory:")) {
            return;
        }

        Path dbPath = Path.of(raw);
        Path parent = dbPath.isAbsolute() ? dbPath.getParent() : Path.of("").resolve(dbPath).normalize().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception e) {
            throw new ConfigurationException("Unable to create metadata database directory: " + parent, e);
        }
    }
}
