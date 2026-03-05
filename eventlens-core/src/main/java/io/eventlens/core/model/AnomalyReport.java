package io.eventlens.core.model;

import java.time.Instant;
import java.util.Map;

/**
 * A detected anomaly in an aggregate's event history.
 */
public record AnomalyReport(
        String code,
        String description,
        Severity severity,
        String aggregateId,
        long atSequence,
        String triggeringEventType,
        Instant timestamp,
        Map<String, Object> stateAtAnomaly) {
    public enum Severity {
        LOW, MEDIUM, HIGH, CRITICAL;

        public static Severity fromString(String s) {
            return switch (s.toUpperCase()) {
                case "CRITICAL" -> CRITICAL;
                case "HIGH" -> HIGH;
                case "LOW" -> LOW;
                default -> MEDIUM;
            };
        }
    }
}
