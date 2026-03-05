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

    public static class DatasourceConfig {
        private String url = "jdbc:postgresql://localhost:5432/eventlens_dev";
        private String username = "postgres";
        private String password = "";
        private String table; // null = auto-detect

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
        private List<AnomalyRuleConfig> rules = List.of();

        public int getScanIntervalSeconds() {
            return scanIntervalSeconds;
        }

        public void setScanIntervalSeconds(int s) {
            this.scanIntervalSeconds = s;
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
