package io.eventlens.core;

import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.exception.ConfigurationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ConfigValidator {

    public record ValidationError(String path, String message, Severity severity) {
        public enum Severity { ERROR, WARNING }
    }

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "changeme", "password", "admin", "123456", "eventlens",
            "secret", "password123", "admin123"
    );

    private ConfigValidator() {
    }

    public static List<ValidationError> validate(EventLensConfig config) {
        var issues = new ArrayList<ValidationError>();

        if (config == null) {
            issues.add(error("<root>", "Config is null"));
            return issues;
        }

        var server = config.getServer();
        if (server == null) {
            issues.add(error("server", "Required"));
        } else {
            if (server.getPort() < 1 || server.getPort() > 65535) {
                issues.add(error("server.port", "Must be between 1 and 65535"));
            }

            var origins = server.getAllowedOrigins();
            if (origins == null || origins.isEmpty()) {
                issues.add(warning("server.allowed-origins", "Empty allowlist will block browser access"));
            }

            var auth = server.getAuth();
            if (auth == null) {
                issues.add(error("server.auth", "Required"));
            } else if (auth.isEnabled()) {
                if (isBlank(auth.getUsername())) {
                    issues.add(error("server.auth.username", "Required when auth is enabled"));
                }
                if (isBlank(auth.getPassword())) {
                    issues.add(error("server.auth.password", "Required when auth is enabled"));
                } else {
                    if (auth.getPassword().length() < 12) {
                        issues.add(error("server.auth.password",
                                "Must be at least 12 characters (got %d)".formatted(auth.getPassword().length())));
                    }
                    if (COMMON_PASSWORDS.contains(auth.getPassword())) {
                        issues.add(error("server.auth.password", "Password is in the common passwords list"));
                    }
                }

                if (origins != null && origins.contains("*")) {
                    issues.add(warning("server.allowed-origins",
                            "Using '*' with auth enabled allows credential theft from any origin"));
                }
            }

            if (server.getCorsMaxAgeSeconds() < 0 || server.getCorsMaxAgeSeconds() > 86_400) {
                issues.add(error("server.cors-max-age-seconds", "Must be between 0 and 86400"));
            }
        }

        if (server != null && server.getSecurity() != null && server.getSecurity().getRateLimit() != null) {
            var rl = server.getSecurity().getRateLimit();
            if (rl.getRequestsPerMinute() < 1 || rl.getRequestsPerMinute() > 10_000) {
                issues.add(error("server.security.rate-limit.requests-per-minute", "Must be between 1 and 10000"));
            }
            if (rl.getBurst() < 1 || rl.getBurst() > 10_000) {
                issues.add(error("server.security.rate-limit.burst", "Must be between 1 and 10000"));
            }
            if (rl.getBurst() > rl.getRequestsPerMinute() * 10L) {
                issues.add(warning("server.security.rate-limit.burst",
                        "Burst is very high compared to requests-per-minute; consider lowering to reduce memory spikes"));
            }
        }

        validateLegacyDatasource(config, issues);
        validateDatasourceInstances(config.getDatasourcesOrLegacy(), issues);
        validateStreamInstances(config.getStreamsOrLegacy(), issues);

        var anomaly = config.getAnomaly();
        if (anomaly != null && anomaly.getRules() != null) {
            for (int i = 0; i < anomaly.getRules().size(); i++) {
                var rule = anomaly.getRules().get(i);
                String base = "anomaly.rules[%d]".formatted(i);
                if (rule == null) {
                    issues.add(error(base, "Rule is null"));
                    continue;
                }
                if (isBlank(rule.getCode())) {
                    issues.add(error(base + ".code", "Required"));
                }
                if (isBlank(rule.getCondition())) {
                    issues.add(error(base + ".condition", "Required for rule '%s'".formatted(rule.getCode())));
                } else {
                    try {
                        BisectEngine.parseCondition(rule.getCondition());
                    } catch (Exception e) {
                        issues.add(error(base + ".condition",
                                "Unparseable condition for rule '%s': %s".formatted(rule.getCode(), e.getMessage())));
                    }
                }
            }
        }

        return issues;
    }

    private static void validateLegacyDatasource(EventLensConfig config, List<ValidationError> issues) {
        var ds = config.getDatasource();
        if (ds == null) {
            issues.add(error("datasource", "Required"));
            return;
        }
        if (isBlank(ds.getUrl())) {
            issues.add(error("datasource.url", "Required"));
        } else if (!ds.getUrl().startsWith("jdbc:postgresql://")) {
            String prefix = ds.getUrl().substring(0, Math.min(30, ds.getUrl().length()));
            issues.add(error("datasource.url", "Must be a PostgreSQL JDBC URL (got: %s...)".formatted(prefix)));
        }
        if (isBlank(ds.getUsername())) {
            issues.add(error("datasource.username", "Required"));
        }
    }

    private static void validateDatasourceInstances(List<EventLensConfig.DatasourceInstanceConfig> datasources, List<ValidationError> issues) {
        if (datasources == null || datasources.isEmpty()) {
            issues.add(error("datasources", "At least one datasource is required"));
            return;
        }
        for (int i = 0; i < datasources.size(); i++) {
            var ds = datasources.get(i);
            String base = "datasources[%d]".formatted(i);
            if (ds == null) {
                issues.add(error(base, "Datasource is null"));
                continue;
            }
            if (isBlank(ds.getId())) {
                issues.add(error(base + ".id", "Required"));
            }
            if (isBlank(ds.getType())) {
                issues.add(error(base + ".type", "Required"));
            }
            if (isBlank(ds.getUrl())) {
                issues.add(error(base + ".url", "Required"));
            } else if (!isSupportedDatasourceUrl(ds.getType(), ds.getUrl())) {
                issues.add(error(base + ".url", "URL must match datasource type '%s'".formatted(ds.getType())));
            }
            if (isBlank(ds.getUsername())) {
                issues.add(error(base + ".username", "Required"));
            }
            if (ds.getColumns() != null && ds.getColumns().hasAnyOverride()) {
                try {
                    InputValidator.validateColumnMapping(Map.ofEntries(
                            Map.entry("event-id", ds.getColumns().getEventId()),
                            Map.entry("aggregate-id", ds.getColumns().getAggregateId()),
                            Map.entry("aggregate-type", ds.getColumns().getAggregateType()),
                            Map.entry("sequence", ds.getColumns().getSequence()),
                            Map.entry("event-type", ds.getColumns().getEventType()),
                            Map.entry("payload", ds.getColumns().getPayload()),
                            Map.entry("metadata", ds.getColumns().getMetadata()),
                            Map.entry("timestamp", ds.getColumns().getTimestamp()),
                            Map.entry("global-position", ds.getColumns().getGlobalPosition())
                    ));
                } catch (ConfigurationException e) {
                    issues.add(error(base + ".columns", e.getMessage()));
                }
            }
            validatePool(base + ".pool", ds.getPool(), issues);
            if (ds.getQueryTimeoutSeconds() < 1 || ds.getQueryTimeoutSeconds() > 600) {
                issues.add(error(base + ".query-timeout-seconds", "Must be between 1 and 600"));
            }
        }
    }

    private static void validateStreamInstances(List<EventLensConfig.StreamInstanceConfig> streams, List<ValidationError> issues) {
        if (streams == null) {
            return;
        }
        for (int i = 0; i < streams.size(); i++) {
            var stream = streams.get(i);
            String base = "streams[%d]".formatted(i);
            if (stream == null) {
                issues.add(error(base, "Stream is null"));
                continue;
            }
            if (isBlank(stream.getId())) {
                issues.add(error(base + ".id", "Required"));
            }
            if (isBlank(stream.getType())) {
                issues.add(error(base + ".type", "Required"));
            }
            if ("kafka".equals(stream.getType())) {
                if (isBlank(stream.getBootstrapServers())) {
                    issues.add(error(base + ".bootstrap-servers", "Required for kafka streams"));
                }
                if (isBlank(stream.getTopic())) {
                    issues.add(error(base + ".topic", "Required for kafka streams"));
                }
            }
        }
    }

    private static void validatePool(String base, EventLensConfig.PoolConfig pool, List<ValidationError> issues) {
        if (pool == null) {
            return;
        }
        if (pool.getMaximumPoolSize() < 1 || pool.getMaximumPoolSize() > 200) {
            issues.add(error(base + ".maximum-pool-size", "Must be between 1 and 200"));
        }
        if (pool.getMinimumIdle() < 0 || pool.getMinimumIdle() > 200) {
            issues.add(error(base + ".minimum-idle", "Must be between 0 and 200"));
        }
        if (pool.getMinimumIdle() > pool.getMaximumPoolSize()) {
            issues.add(error(base + ".minimum-idle", "Must be <= maximum-pool-size"));
        } else if (pool.getMinimumIdle() == pool.getMaximumPoolSize() && pool.getMaximumPoolSize() > 10) {
            issues.add(warning(base + ".minimum-idle",
                    "minimum-idle equals maximum-pool-size; this keeps all connections warm but can waste RAM. Consider elastic pooling (e.g. minimum-idle=5, maximum-pool-size=50)."));
        }
    }

    private static boolean isSupportedDatasourceUrl(String type, String url) {
        if (type == null || url == null) {
            return false;
        }
        return switch (type.toLowerCase()) {
            case "postgres" -> url.startsWith("jdbc:postgresql://");
            case "mysql" -> url.startsWith("jdbc:mysql://");
            default -> url.startsWith("jdbc:");
        };
    }

    public static void validateOrThrow(EventLensConfig config) {
        List<ValidationError> issues = validate(config);
        long errors = issues.stream().filter(i -> i.severity() == ValidationError.Severity.ERROR).count();
        if (errors == 0) return;

        StringBuilder sb = new StringBuilder();
        sb.append("EventLens configuration validation failed.\n\n");
        for (ValidationError i : issues) {
            String prefix = i.severity() == ValidationError.Severity.ERROR ? "x" : "!";
            sb.append("  ").append(prefix).append(" ").append(i.path()).append(": ").append(i.message()).append("\n");
        }
        sb.append("\n").append(errors).append(" error(s). EventLens will not start.\n");
        sb.append("Fix the errors above in your eventlens.yaml and restart.\n");
        throw new ConfigurationException(sb.toString());
    }

    private static ValidationError error(String path, String message) { return new ValidationError(path, message, ValidationError.Severity.ERROR); }
    private static ValidationError warning(String path, String message) { return new ValidationError(path, message, ValidationError.Severity.WARNING); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
