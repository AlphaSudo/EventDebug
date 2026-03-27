package io.eventlens.core;

import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.exception.ConfigurationException;
import io.eventlens.core.security.Permission;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

        validateMetadata(config, issues);
        validateSecurityAuth(config, issues);
        validateAuthorization(config, issues);
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

    private static void validateMetadata(EventLensConfig config, List<ValidationError> issues) {
        var security = config.getSecurity();
        if (security == null || security.getMetadata() == null) {
            return;
        }

        var metadata = security.getMetadata();
        if (!metadata.isEnabled()) {
            return;
        }

        if (isBlank(metadata.getJdbcUrl())) {
            issues.add(error("security.metadata.jdbc-url", "Required when metadata storage is enabled"));
        } else if (!metadata.getJdbcUrl().startsWith("jdbc:sqlite:")) {
            issues.add(error("security.metadata.jdbc-url", "v5 metadata storage currently supports SQLite only"));
        } else if ("jdbc:sqlite::memory:".equalsIgnoreCase(metadata.getJdbcUrl())) {
            issues.add(warning("security.metadata.jdbc-url",
                    "In-memory metadata loses sessions, API keys, and DB-backed audit data on restart"));
        }

        if (metadata.getBusyTimeoutMs() < 0 || metadata.getBusyTimeoutMs() > 600_000) {
            issues.add(error("security.metadata.busy-timeout-ms", "Must be between 0 and 600000"));
        }

        validatePool("security.metadata.pool", metadata.getPool(), issues);
    }

    private static void validateSecurityAuth(EventLensConfig config, List<ValidationError> issues) {
        var security = config.getSecurity();
        if (security == null || security.getAuth() == null) {
            return;
        }

        var auth = security.getAuth();
        String provider = auth.getProvider() == null ? "disabled" : auth.getProvider().trim().toLowerCase();
        if (!Set.of("disabled", "basic", "oidc").contains(provider)) {
            issues.add(error("security.auth.provider", "Must be one of: disabled, basic, oidc"));
            return;
        }

        var session = auth.getSession();
        var apiKeys = auth.getApiKeys();
        if (session != null) {
            if (isBlank(session.getCookieName())) {
                issues.add(error("security.auth.session.cookie-name", "Required"));
            }
            if (session.getIdleTimeoutSeconds() < 60 || session.getIdleTimeoutSeconds() > 86_400) {
                issues.add(error("security.auth.session.idle-timeout-seconds", "Must be between 60 and 86400"));
            }
            if (session.getAbsoluteTimeoutSeconds() < session.getIdleTimeoutSeconds()) {
                issues.add(error("security.auth.session.absolute-timeout-seconds",
                        "Must be >= idle-timeout-seconds"));
            }
            if (session.getAbsoluteTimeoutSeconds() > 604_800) {
                issues.add(error("security.auth.session.absolute-timeout-seconds", "Must be <= 604800"));
            }
            if (!Set.of("lax", "strict", "none").contains(normalize(session.getSameSite()))) {
                issues.add(error("security.auth.session.same-site", "Must be one of: Lax, Strict, None"));
            }
            if ("none".equals(normalize(session.getSameSite())) && !session.isSecureCookie()) {
                issues.add(error("security.auth.session.secure-cookie",
                        "Must be true when same-site is None"));
            }
            if (session.getCookieName() != null
                    && session.getCookieName().startsWith("__Host-")
                    && !session.isSecureCookie()) {
                issues.add(error("security.auth.session.secure-cookie",
                        "Must be true for __Host- prefixed cookies"));
            }
        }

        if (apiKeys != null && apiKeys.isEnabled()) {
            if (security.getMetadata() == null || !security.getMetadata().isEnabled()) {
                issues.add(error("security.metadata.enabled", "Must be enabled when security.auth.api-keys.enabled is true"));
            }
            if (isBlank(apiKeys.getHeaderName())) {
                issues.add(error("security.auth.api-keys.header-name", "Required when API keys are enabled"));
            }
            if (isBlank(apiKeys.getKeyPrefix())) {
                issues.add(error("security.auth.api-keys.key-prefix", "Required when API keys are enabled"));
            } else if (!apiKeys.getKeyPrefix().matches("[A-Za-z0-9_-]{2,32}")) {
                issues.add(error("security.auth.api-keys.key-prefix", "Must be 2-32 chars of letters, digits, '_' or '-'"));
            }
        }

        if ("oidc".equals(provider)) {
            if (security.getMetadata() == null || !security.getMetadata().isEnabled()) {
                issues.add(error("security.metadata.enabled", "Must be enabled when security.auth.provider is oidc"));
            }

            var oidc = auth.getOidc();
            if (oidc == null) {
                issues.add(error("security.auth.oidc", "Required when provider is oidc"));
                return;
            }
            if (isBlank(oidc.getIssuer())) {
                issues.add(error("security.auth.oidc.issuer", "Required when provider is oidc"));
            }
            if (isBlank(oidc.getClientId())) {
                issues.add(error("security.auth.oidc.client-id", "Required when provider is oidc"));
            }
            if (isBlank(oidc.getClientSecret())) {
                issues.add(error("security.auth.oidc.client-secret", "Required when provider is oidc"));
            }
            if (isBlank(oidc.getRedirectPath()) || !oidc.getRedirectPath().startsWith("/")) {
                issues.add(error("security.auth.oidc.redirect-path", "Must start with '/'"));
            }
            if (isBlank(oidc.getPostLogoutRedirectPath()) || !oidc.getPostLogoutRedirectPath().startsWith("/")) {
                issues.add(error("security.auth.oidc.post-logout-redirect-path", "Must start with '/'"));
            }
            if (oidc.getScopes() == null || oidc.getScopes().isEmpty()) {
                issues.add(error("security.auth.oidc.scopes", "At least one scope is required"));
            } else {
                var normalizedScopes = new HashSet<String>();
                for (int i = 0; i < oidc.getScopes().size(); i++) {
                    String scope = oidc.getScopes().get(i);
                    String path = "security.auth.oidc.scopes[%d]".formatted(i);
                    if (isBlank(scope)) {
                        issues.add(error(path, "Scope must not be blank"));
                    } else if (!normalizedScopes.add(scope.trim().toLowerCase())) {
                        issues.add(warning(path, "Duplicate scope"));
                    }
                }
            }
        }
    }

    private static void validateAuthorization(EventLensConfig config, List<ValidationError> issues) {
        var security = config.getSecurity();
        if (security == null || security.getAuthorization() == null) {
            return;
        }

        var authorization = security.getAuthorization();
        if (!authorization.isEnabled()) {
            return;
        }

        Map<String, EventLensConfig.RoleConfig> rolesById = new LinkedHashMap<>();
        for (int i = 0; i < authorization.getRoles().size(); i++) {
            EventLensConfig.RoleConfig role = authorization.getRoles().get(i);
            String path = "security.authorization.roles[%d]".formatted(i);
            if (role == null || isBlank(role.getId())) {
                issues.add(error(path + ".id", "Role id is required"));
                continue;
            }
            if (rolesById.putIfAbsent(role.getId(), role) != null) {
                issues.add(error(path + ".id", "Duplicate role id: " + role.getId()));
            }
            if (role.getPermissions() == null || role.getPermissions().isEmpty()) {
                issues.add(warning(path + ".permissions", "Role has no permissions"));
            } else {
                for (int permissionIndex = 0; permissionIndex < role.getPermissions().size(); permissionIndex++) {
                    String permissionValue = role.getPermissions().get(permissionIndex);
                    if (Permission.fromConfigValue(permissionValue).isEmpty()) {
                        issues.add(error(
                                path + ".permissions[%d]".formatted(permissionIndex),
                                "Unknown permission: " + permissionValue));
                    }
                }
            }
        }

        if (authorization.getDefaultRoles().isEmpty() && authorization.getPrincipalRoles().isEmpty()) {
            issues.add(warning("security.authorization", "Authorization is enabled with no default roles or principal role assignments"));
        }

        for (int i = 0; i < authorization.getDefaultRoles().size(); i++) {
            String roleId = authorization.getDefaultRoles().get(i);
            if (!rolesById.containsKey(roleId)) {
                issues.add(error("security.authorization.default-roles[%d]".formatted(i), "Unknown role: " + roleId));
            }
        }

        for (Map.Entry<String, List<String>> entry : authorization.getPrincipalRoles().entrySet()) {
            String principalId = entry.getKey();
            if (isBlank(principalId)) {
                issues.add(error("security.authorization.principal-roles", "Principal role assignments must use a non-blank principal id"));
                continue;
            }
            List<String> assignedRoles = entry.getValue() == null ? List.of() : entry.getValue();
            if (assignedRoles.isEmpty()) {
                issues.add(warning("security.authorization.principal-roles." + principalId, "Principal has no assigned roles"));
            }
            for (int i = 0; i < assignedRoles.size(); i++) {
                String roleId = assignedRoles.get(i);
                if (!rolesById.containsKey(roleId)) {
                    issues.add(error("security.authorization.principal-roles.%s[%d]".formatted(principalId, i), "Unknown role: " + roleId));
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
    private static String normalize(String s) { return s == null ? "" : s.trim().toLowerCase(); }
}
