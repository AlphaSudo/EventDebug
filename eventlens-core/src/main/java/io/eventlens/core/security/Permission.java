package io.eventlens.core.security;

import java.util.Arrays;
import java.util.Optional;

public enum Permission {
    SEARCH_AGGREGATES,
    VIEW_RECENT_EVENTS,
    VIEW_AGGREGATE_TYPES,
    VIEW_TIMELINE,
    VIEW_REPLAY,
    VIEW_ANOMALIES,
    EXPORT_AGGREGATE,
    START_EXPORT,
    REVEAL_PII,
    VIEW_DATASOURCES,
    VIEW_PLUGINS,
    VIEW_STATISTICS,
    VIEW_METRICS,
    VIEW_AUDIT_LOG,
    VIEW_OPENAPI,
    EXECUTE_BISECT;

    public static Optional<Permission> fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        return Arrays.stream(values()).filter(permission -> permission.name().equals(normalized)).findFirst();
    }
}
