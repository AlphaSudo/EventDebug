package io.eventlens.core;

import java.util.List;
import java.util.Map;

/**
 * Typed configuration POJO loaded from eventlens.yaml.
 */
public class EventLensConfig {

    private ServerConfig server = new ServerConfig();
    private DatasourceConfig datasource = new DatasourceConfig();
    private KafkaConfig kafka; // null = Kafka disabled
    private ReplayConfig replay = new ReplayConfig();
    private AnomalyConfig anomaly = new AnomalyConfig();
    private UiConfig ui = new UiConfig();

    // ── Getters / Setters ──────────────────────────────────────────────

    public ServerConfig getServer() {
        return server;
    }

    public void setServer(ServerConfig s) {
        this.server = s;
    }

    public DatasourceConfig getDatasource() {
        return datasource;
    }

    public void setDatasource(DatasourceConfig d) {
        this.datasource = d;
    }

    public KafkaConfig getKafka() {
        return kafka;
    }

    public void setKafka(KafkaConfig k) {
        this.kafka = k;
    }

    public ReplayConfig getReplay() {
        return replay;
    }

    public void setReplay(ReplayConfig r) {
        this.replay = r;
    }

    public AnomalyConfig getAnomaly() {
        return anomaly;
    }

    public void setAnomaly(AnomalyConfig a) {
        this.anomaly = a;
    }

    public UiConfig getUi() {
        return ui;
    }

    public void setUi(UiConfig u) {
        this.ui = u;
    }

    // ── Nested configs ─────────────────────────────────────────────────

    public static class ServerConfig {
        private int port = 9090;
        private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:9090");
        private AuthConfig auth = new AuthConfig();
        private SecurityConfig security = new SecurityConfig();
        private int corsMaxAgeSeconds = 600;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> o) {
            this.allowedOrigins = o;
        }

        public AuthConfig getAuth() {
            return auth;
        }

        public void setAuth(AuthConfig auth) {
            this.auth = auth;
        }

        public SecurityConfig getSecurity() {
            return security;
        }

        public void setSecurity(SecurityConfig security) {
            this.security = security;
        }

        public int getCorsMaxAgeSeconds() {
            return corsMaxAgeSeconds;
        }

        public void setCorsMaxAgeSeconds(int corsMaxAgeSeconds) {
            this.corsMaxAgeSeconds = corsMaxAgeSeconds;
        }
    }

    public static class AuthConfig {
        private boolean enabled = false;
        private String username = "admin";
        private String password = "changeme";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String u) {
            this.username = u;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String p) {
            this.password = p;
        }
    }

    public static class SecurityConfig {
        private RateLimitConfig rateLimit = new RateLimitConfig();

        public RateLimitConfig getRateLimit() {
            return rateLimit;
        }

        public void setRateLimit(RateLimitConfig rateLimit) {
            this.rateLimit = rateLimit;
        }
    }

    public static class RateLimitConfig {
        private boolean enabled = false;
        private int requestsPerMinute = 120;
        private int burst = 20;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRequestsPerMinute() {
            return requestsPerMinute;
        }

        public void setRequestsPerMinute(int requestsPerMinute) {
            this.requestsPerMinute = requestsPerMinute;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }
    }

    public static class DatasourceConfig {
        private String url = "jdbc:postgresql://localhost:5432/eventlens_dev";
        private String username = "postgres";
        private String password = "";
        private String table; // null = auto-detect
        private ColumnMappingConfig columns = new ColumnMappingConfig();

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String u) {
            this.username = u;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String p) {
            this.password = p;
        }

        public String getTable() {
            return table;
        }

        public void setTable(String table) {
            this.table = table;
        }

        public ColumnMappingConfig getColumns() {
            return columns;
        }

        public void setColumns(ColumnMappingConfig columns) {
            this.columns = columns;
        }
    }

    /**
     * Fix 1: Explicit column name overrides for projects whose event store schema
     * does not match EventLens's auto-detection candidates.
     *
     * <p>
     * Usage in eventlens.yaml:
     * 
     * <pre>
     * datasource:
     *   table: my_events
     *   columns:
     *     event-id: uid              # default: event_id / id / uid
     *     aggregate-id: account_id  # default: aggregate_id / stream_id / entity_id
     *     aggregate-type: kind       # default: aggregate_type / type (optional)
     *     sequence: revision         # default: sequence_number / version / seq
     *     event-type: event_name     # default: event_type / type_name
     *     payload: body              # default: payload / data / event_data
     *     metadata: headers          # default: metadata / meta (optional)
     *     timestamp: created_at      # default: timestamp / occurred_at / created_at
     *     global-position: log_seq   # default: global_position / global_seq (optional)
     * </pre>
     *
     * Any field left null falls back to auto-detection from the table metadata.
     */
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

        public String getEventId() {
            return eventId;
        }

        public void setEventId(String v) {
            this.eventId = v;
        }

        public String getAggregateId() {
            return aggregateId;
        }

        public void setAggregateId(String v) {
            this.aggregateId = v;
        }

        public String getAggregateType() {
            return aggregateType;
        }

        public void setAggregateType(String v) {
            this.aggregateType = v;
        }

        public String getSequence() {
            return sequence;
        }

        public void setSequence(String v) {
            this.sequence = v;
        }

        public String getEventType() {
            return eventType;
        }

        public void setEventType(String v) {
            this.eventType = v;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(String v) {
            this.payload = v;
        }

        public String getMetadata() {
            return metadata;
        }

        public void setMetadata(String v) {
            this.metadata = v;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String v) {
            this.timestamp = v;
        }

        public String getGlobalPosition() {
            return globalPosition;
        }

        public void setGlobalPosition(String v) {
            this.globalPosition = v;
        }

        /** Returns true if any column override has been set by the user. */
        public boolean hasAnyOverride() {
            return eventId != null || aggregateId != null || aggregateType != null
                    || sequence != null || eventType != null || payload != null
                    || metadata != null || timestamp != null || globalPosition != null;
        }
    }

    public static class KafkaConfig {
        private String bootstrapServers;
        private String topic = "domain-events";

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String b) {
            this.bootstrapServers = b;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }
    }

    public static class ReplayConfig {
        private String defaultReducer = "generic";
        private Map<String, String> reducers = Map.of();

        public String getDefaultReducer() {
            return defaultReducer;
        }

        public void setDefaultReducer(String d) {
            this.defaultReducer = d;
        }

        public Map<String, String> getReducers() {
            return reducers;
        }

        public void setReducers(Map<String, String> r) {
            this.reducers = r;
        }
    }

    public static class AnomalyConfig {
        private int scanIntervalSeconds = 60;
        // Fix 11: cap how many aggregates scanRecent() will process to prevent O(n²)
        // blowup
        private int maxAggregatesPerScan = 20;
        private List<AnomalyRuleConfig> rules = List.of();

        public int getScanIntervalSeconds() {
            return scanIntervalSeconds;
        }

        public void setScanIntervalSeconds(int s) {
            this.scanIntervalSeconds = s;
        }

        public int getMaxAggregatesPerScan() {
            return maxAggregatesPerScan;
        }

        public void setMaxAggregatesPerScan(int v) {
            this.maxAggregatesPerScan = v;
        }

        public List<AnomalyRuleConfig> getRules() {
            return rules;
        }

        public void setRules(List<AnomalyRuleConfig> r) {
            this.rules = r;
        }
    }

    public static class AnomalyRuleConfig {
        private String code;
        private String condition;
        private String severity = "MEDIUM";
        private String description;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String c) {
            this.condition = c;
        }

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String s) {
            this.severity = s;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String d) {
            this.description = d;
        }
    }

    public static class UiConfig {
        private String theme = "dark";

        public String getTheme() {
            return theme;
        }

        public void setTheme(String theme) {
            this.theme = theme;
        }
    }
}
