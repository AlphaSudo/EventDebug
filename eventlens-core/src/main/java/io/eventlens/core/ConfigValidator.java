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

        // --- Server ---
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

            // --- Auth ---
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

        // --- Rate limiting ---
        if (server != null && server.getSecurity() != null && server.getSecurity().getRateLimit() != null) {
            var rl = server.getSecurity().getRateLimit();
            if (rl.getRequestsPerMinute() < 1 || rl.getRequestsPerMinute() > 10_000) {
                issues.add(error("server.security.rate-limit.requests-per-minute",
                        "Must be between 1 and 10000"));
            }
            if (rl.getBurst() < 1 || rl.getBurst() > 10_000) {
                issues.add(error("server.security.rate-limit.burst",
                        "Must be between 1 and 10000"));
            }
            if (rl.getBurst() > rl.getRequestsPerMinute() * 10L) {
                issues.add(warning("server.security.rate-limit.burst",
                        "Burst is very high compared to requests-per-minute; consider lowering to reduce memory spikes"));
            }
        }

        // --- Datasource ---
        var ds = config.getDatasource();
        if (ds == null) {
            issues.add(error("datasource", "Required"));
        } else {
            if (isBlank(ds.getUrl())) {
                issues.add(error("datasource.url", "Required"));
            } else if (!ds.getUrl().startsWith("jdbc:postgresql://")) {
                String prefix = ds.getUrl().substring(0, Math.min(30, ds.getUrl().length()));
                issues.add(error("datasource.url",
                        "Must be a PostgreSQL JDBC URL (got: %s...)".formatted(prefix)));
            }
            if (isBlank(ds.getUsername())) {
                issues.add(error("datasource.username", "Required"));
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
                    issues.add(error("datasource.columns", e.getMessage()));
                }
            }
        }

        // --- Kafka (optional) ---
        var kafka = config.getKafka();
        if (kafka != null) {
            if (isBlank(kafka.getBootstrapServers())) {
                issues.add(error("kafka.bootstrap-servers", "Required when kafka section is present"));
            }
            if (isBlank(kafka.getTopic())) {
                issues.add(error("kafka.topic", "Required when kafka section is present"));
            }
        }

        // --- Anomaly rules ---
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
                    issues.add(error(base + ".condition",
                            "Required for rule '%s'".formatted(rule.getCode())));
                } else {
                    try {
                        BisectEngine.parseCondition(rule.getCondition());
                    } catch (Exception e) {
                        issues.add(error(base + ".condition",
                                "Unparseable condition for rule '%s': %s"
                                        .formatted(rule.getCode(), e.getMessage())));
                    }
                }
            }
        }

        return issues;
    }

    public static void validateOrThrow(EventLensConfig config) {
        List<ValidationError> issues = validate(config);
        long errors = issues.stream().filter(i -> i.severity() == ValidationError.Severity.ERROR).count();
        if (errors == 0) return;

        StringBuilder sb = new StringBuilder();
        sb.append("EventLens configuration validation failed.\n\n");
        for (ValidationError i : issues) {
            String prefix = i.severity() == ValidationError.Severity.ERROR ? "✗" : "⚠";
            sb.append("  ").append(prefix).append(" ").append(i.path()).append(": ").append(i.message()).append("\n");
        }
        sb.append("\n").append(errors).append(" error(s). EventLens will not start.\n");
        sb.append("Fix the errors above in your eventlens.yaml and restart.\n");

        throw new ConfigurationException(sb.toString());
    }

    private static ValidationError error(String path, String message) {
        return new ValidationError(path, message, ValidationError.Severity.ERROR);
    }

    private static ValidationError warning(String path, String message) {
        return new ValidationError(path, message, ValidationError.Severity.WARNING);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

