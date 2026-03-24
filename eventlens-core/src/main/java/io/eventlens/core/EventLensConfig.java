package io.eventlens.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Typed configuration POJO loaded from eventlens.yaml.
 */
public class EventLensConfig {

    private ServerConfig server = new ServerConfig();
    private DatasourceConfig datasource = new DatasourceConfig();
    private KafkaConfig kafka;
    private List<DatasourceInstanceConfig> datasources = List.of();
    private List<StreamInstanceConfig> streams = List.of();
    private ReplayConfig replay = new ReplayConfig();
    private AnomalyConfig anomaly = new AnomalyConfig();
    private UiConfig ui = new UiConfig();
    private AuditConfig audit = new AuditConfig();
    private DataProtectionConfig dataProtection = new DataProtectionConfig();
    private ExportConfig export = new ExportConfig();
    private PluginsConfig plugins = new PluginsConfig();
    private QueryCacheConfig queryCache = new QueryCacheConfig();
    private String version = "2.0.0";

    public ServerConfig getServer() { return server; }
    public void setServer(ServerConfig server) { this.server = server; }

    public DatasourceConfig getDatasource() { return datasource; }
    public void setDatasource(DatasourceConfig datasource) { this.datasource = datasource; }

    public KafkaConfig getKafka() { return kafka; }
    public void setKafka(KafkaConfig kafka) { this.kafka = kafka; }

    public List<DatasourceInstanceConfig> getDatasources() { return datasources; }
    public void setDatasources(List<DatasourceInstanceConfig> datasources) { this.datasources = datasources == null ? List.of() : datasources; }

    public List<StreamInstanceConfig> getStreams() { return streams; }
    public void setStreams(List<StreamInstanceConfig> streams) { this.streams = streams == null ? List.of() : streams; }

    public ReplayConfig getReplay() { return replay; }
    public void setReplay(ReplayConfig replay) { this.replay = replay; }

    public AnomalyConfig getAnomaly() { return anomaly; }
    public void setAnomaly(AnomalyConfig anomaly) { this.anomaly = anomaly; }

    public UiConfig getUi() { return ui; }
    public void setUi(UiConfig ui) { this.ui = ui; }

    public AuditConfig getAudit() { return audit; }
    public void setAudit(AuditConfig audit) { this.audit = audit; }

    public DataProtectionConfig getDataProtection() { return dataProtection; }
    public void setDataProtection(DataProtectionConfig dataProtection) { this.dataProtection = dataProtection; }

    public ExportConfig getExport() { return export; }
    public void setExport(ExportConfig export) { this.export = export; }

    public PluginsConfig getPlugins() { return plugins; }
    public void setPlugins(PluginsConfig plugins) { this.plugins = plugins; }

    public QueryCacheConfig getQueryCache() { return queryCache; }
    public void setQueryCache(QueryCacheConfig queryCache) { this.queryCache = queryCache; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public List<DatasourceInstanceConfig> getDatasourcesOrLegacy() {
        if (datasources != null && !datasources.isEmpty()) {
            return datasources;
        }
        if (datasource == null || datasource.getUrl() == null || datasource.getUrl().isBlank()) {
            return List.of();
        }
        return List.of(DatasourceInstanceConfig.fromLegacy("default", "postgres", datasource));
    }

    public List<StreamInstanceConfig> getStreamsOrLegacy() {
        if (streams != null && !streams.isEmpty()) {
            return streams;
        }
        if (kafka == null || kafka.getBootstrapServers() == null || kafka.getBootstrapServers().isBlank()) {
            return List.of();
        }
        return List.of(StreamInstanceConfig.fromLegacy("default-kafka", "kafka", kafka));
    }

    public static class ServerConfig {
        private int port = 9090;
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:9090");
        private AuthConfig auth = new AuthConfig();
        private SecurityConfig security = new SecurityConfig();
        private int corsMaxAgeSeconds = 600;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public AuthConfig getAuth() { return auth; }
        public void setAuth(AuthConfig auth) { this.auth = auth; }
        public SecurityConfig getSecurity() { return security; }
        public void setSecurity(SecurityConfig security) { this.security = security; }
        public int getCorsMaxAgeSeconds() { return corsMaxAgeSeconds; }
        public void setCorsMaxAgeSeconds(int corsMaxAgeSeconds) { this.corsMaxAgeSeconds = corsMaxAgeSeconds; }
    }

    public static class AuthConfig {
        private boolean enabled = false;
        private String username = "admin";
        private String password = "changeme";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class SecurityConfig {
        private RateLimitConfig rateLimit = new RateLimitConfig();
        public RateLimitConfig getRateLimit() { return rateLimit; }
        public void setRateLimit(RateLimitConfig rateLimit) { this.rateLimit = rateLimit; }
    }

    public static class RateLimitConfig {
        private boolean enabled = false;
        private int requestsPerMinute = 120;
        private int burst = 20;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }
        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }
    }

    public static class DatasourceConfig {
        private String url = "jdbc:postgresql://localhost:5432/eventlens_dev";
        private String username = "postgres";
        private String password = "";
        private String table;
        private ColumnMappingConfig columns = new ColumnMappingConfig();
        private PoolConfig pool = new PoolConfig();
        private int queryTimeoutSeconds = 30;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public ColumnMappingConfig getColumns() { return columns; }
        public void setColumns(ColumnMappingConfig columns) { this.columns = columns; }
        public PoolConfig getPool() { return pool; }
        public void setPool(PoolConfig pool) { this.pool = pool; }
        public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
    }

    public static class DatasourceInstanceConfig extends DatasourceConfig {
        private String id = "default";
        private String type = "postgres";
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public static DatasourceInstanceConfig fromLegacy(String id, String type, DatasourceConfig legacy) {
            DatasourceInstanceConfig config = new DatasourceInstanceConfig();
            config.setId(id);
            config.setType(type);
            config.setUrl(legacy.getUrl());
            config.setUsername(legacy.getUsername());
            config.setPassword(legacy.getPassword());
            config.setTable(legacy.getTable());
            config.setColumns(legacy.getColumns());
            config.setPool(legacy.getPool());
            config.setQueryTimeoutSeconds(legacy.getQueryTimeoutSeconds());
            return config;
        }
    }

    public static class PoolConfig {
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private long connectionTimeoutMs = 5_000;
        private long idleTimeoutMs = 300_000;
        private long maxLifetimeMs = 900_000;
        private long leakDetectionThresholdMs = 30_000;
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public void setMinimumIdle(int minimumIdle) { this.minimumIdle = minimumIdle; }
        public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(long connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
        public long getIdleTimeoutMs() { return idleTimeoutMs; }
        public void setIdleTimeoutMs(long idleTimeoutMs) { this.idleTimeoutMs = idleTimeoutMs; }
        public long getMaxLifetimeMs() { return maxLifetimeMs; }
        public void setMaxLifetimeMs(long maxLifetimeMs) { this.maxLifetimeMs = maxLifetimeMs; }
        public long getLeakDetectionThresholdMs() { return leakDetectionThresholdMs; }
        public void setLeakDetectionThresholdMs(long leakDetectionThresholdMs) { this.leakDetectionThresholdMs = leakDetectionThresholdMs; }
    }

    public static class PluginsConfig {
        private String directory = "./plugins";
        private int healthCheckIntervalSeconds = 30;
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public int getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
        public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) { this.healthCheckIntervalSeconds = healthCheckIntervalSeconds; }
    }

    public static class ExportConfig {
        private String directory = "./exports";
        private int maxConcurrent = 2;
        private int maxEventsPerExport = 100_000;
        private int expireAfterSeconds = 3_600;
        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        public int getMaxEventsPerExport() { return maxEventsPerExport; }
        public void setMaxEventsPerExport(int maxEventsPerExport) { this.maxEventsPerExport = maxEventsPerExport; }
        public int getExpireAfterSeconds() { return expireAfterSeconds; }
        public void setExpireAfterSeconds(int expireAfterSeconds) { this.expireAfterSeconds = expireAfterSeconds; }
    }

    public static class QueryCacheConfig {
        private boolean enabled = true;
        private int maxEntries = 512;
        private int searchTtlSeconds = 15;
        private int timelineTtlSeconds = 10;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxEntries() { return maxEntries; }
        public void setMaxEntries(int maxEntries) { this.maxEntries = maxEntries; }
        public int getSearchTtlSeconds() { return searchTtlSeconds; }
        public void setSearchTtlSeconds(int searchTtlSeconds) { this.searchTtlSeconds = searchTtlSeconds; }
        public int getTimelineTtlSeconds() { return timelineTtlSeconds; }
        public void setTimelineTtlSeconds(int timelineTtlSeconds) { this.timelineTtlSeconds = timelineTtlSeconds; }
    }

    public static class ColumnMappingConfig {
        private String eventId;
        private String aggregateId;
        private String aggregateType;
        private String sequence;
        private String eventType;
        private String payload;
        private String metadata;
        private String timestamp;
        private String globalPosition;
        public String getEventId() { return eventId; }
        public void setEventId(String eventId) { this.eventId = eventId; }
        public String getAggregateId() { return aggregateId; }
        public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
        public String getAggregateType() { return aggregateType; }
        public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
        public String getSequence() { return sequence; }
        public void setSequence(String sequence) { this.sequence = sequence; }
        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }
        public String getPayload() { return payload; }
        public void setPayload(String payload) { this.payload = payload; }
        public String getMetadata() { return metadata; }
        public void setMetadata(String metadata) { this.metadata = metadata; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public String getGlobalPosition() { return globalPosition; }
        public void setGlobalPosition(String globalPosition) { this.globalPosition = globalPosition; }
        public boolean hasAnyOverride() {
            return eventId != null || aggregateId != null || aggregateType != null || sequence != null
                    || eventType != null || payload != null || metadata != null || timestamp != null || globalPosition != null;
        }
    }

    public static class KafkaConfig {
        private String bootstrapServers;
        private String topic = "domain-events";
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
    }

    public static class StreamInstanceConfig extends KafkaConfig {
        private String id = "default-kafka";
        private String type = "kafka";
        private boolean enabled = true;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public static StreamInstanceConfig fromLegacy(String id, String type, KafkaConfig legacy) {
            StreamInstanceConfig config = new StreamInstanceConfig();
            config.setId(id);
            config.setType(type);
            config.setBootstrapServers(legacy.getBootstrapServers());
            config.setTopic(legacy.getTopic());
            return config;
        }
    }

    public static class ReplayConfig {
        private String defaultReducer = "generic";
        private Map<String, String> reducers = Map.of();
        public String getDefaultReducer() { return defaultReducer; }
        public void setDefaultReducer(String defaultReducer) { this.defaultReducer = defaultReducer; }
        public Map<String, String> getReducers() { return reducers; }
        public void setReducers(Map<String, String> reducers) { this.reducers = reducers; }
    }

    public static class AnomalyConfig {
        private int scanIntervalSeconds = 60;
        private int maxAggregatesPerScan = 20;
        private List<AnomalyRuleConfig> rules = List.of();
        public int getScanIntervalSeconds() { return scanIntervalSeconds; }
        public void setScanIntervalSeconds(int scanIntervalSeconds) { this.scanIntervalSeconds = scanIntervalSeconds; }
        public int getMaxAggregatesPerScan() { return maxAggregatesPerScan; }
        public void setMaxAggregatesPerScan(int maxAggregatesPerScan) { this.maxAggregatesPerScan = maxAggregatesPerScan; }
        public List<AnomalyRuleConfig> getRules() { return rules; }
        public void setRules(List<AnomalyRuleConfig> rules) { this.rules = rules; }
    }

    public static class AnomalyRuleConfig {
        private String code;
        private String condition;
        private String severity = "MEDIUM";
        private String description;
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class UiConfig {
        private String theme = "dark";
        public String getTheme() { return theme; }
        public void setTheme(String theme) { this.theme = theme; }
    }

    public static class AuditConfig {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class DataProtectionConfig {
        private PiiConfig pii = new PiiConfig();
        public PiiConfig getPii() { return pii; }
        public void setPii(PiiConfig pii) { this.pii = pii; }
    }

    public static class PiiConfig {
        private boolean enabled = false;
        private List<PiiPatternConfig> patterns = defaultPatterns();
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<PiiPatternConfig> getPatterns() { return patterns; }
        public void setPatterns(List<PiiPatternConfig> patterns) { this.patterns = patterns; }
        private static List<PiiPatternConfig> defaultPatterns() {
            var list = new ArrayList<PiiPatternConfig>();
            list.add(new PiiPatternConfig("email", "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}", "***@***.***"));
            list.add(new PiiPatternConfig("credit-card", "\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b", "****-****-****-****"));
            list.add(new PiiPatternConfig("phone", "\\+?[1-9]\\d{7,14}", "***-***-****"));
            list.add(new PiiPatternConfig("ssn", "\\d{3}-\\d{2}-\\d{4}", "***-**-****"));
            return list;
        }
    }

    public static class PiiPatternConfig {
        private String name;
        private String regex;
        private String mask;
        public PiiPatternConfig() {}
        public PiiPatternConfig(String name, String regex, String mask) {
            this.name = name;
            this.regex = regex;
            this.mask = mask;
        }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRegex() { return regex; }
        public void setRegex(String regex) { this.regex = regex; }
        public String getMask() { return mask; }
        public void setMask(String mask) { this.mask = mask; }
    }
}

