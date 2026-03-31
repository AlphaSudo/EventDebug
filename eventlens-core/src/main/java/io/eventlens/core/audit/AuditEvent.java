package io.eventlens.core.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Structured payload for a single audit-log entry.
 *
 * <p>Serialised to JSON by {@link AuditLogger} via SLF4J / Logback.
 * All fields are nullable except {@code timestamp}, {@code action}, and
 * {@code requestId} — those are always present.
 */
public record AuditEvent(
        Instant  timestamp,
        String   userId,        // from auth principal; "anonymous" when auth is off
        String   authMethod,    // basic | api-key | anonymous
        String   action,        // see Action constants below
        String   resourceType,  // AGGREGATE | EVENT | ANOMALY | EXPORT | STREAM
        String   resourceId,    // aggregate-id / null when not applicable
        Map<String, Object> details,   // action-specific metadata (limit, format, …)
        String   clientIp,
        String   requestId,
        String   userAgent
) {

    // ── Action constants ────────────────────────────────────────────────
    public static final String ACTION_SEARCH          = "SEARCH";
    public static final String ACTION_VIEW_TIMELINE   = "VIEW_TIMELINE";
    public static final String ACTION_VIEW_ANOMALIES  = "VIEW_ANOMALIES";
    public static final String ACTION_EXPORT          = "EXPORT";
    public static final String ACTION_REVEAL_PII      = "REVEAL_PII";
    public static final String ACTION_CREATE_API_KEY  = "CREATE_API_KEY";
    public static final String ACTION_REVOKE_API_KEY  = "REVOKE_API_KEY";
    public static final String ACTION_LOGIN           = "LOGIN";
    public static final String ACTION_LOGIN_FAILED    = "LOGIN_FAILED";
    public static final String ACTION_VIEW_LIVE_STREAM = "VIEW_LIVE_STREAM";

    // ── Resource type constants ──────────────────────────────────────────
    public static final String RT_AGGREGATE = "AGGREGATE";
    public static final String RT_EVENT     = "EVENT";
    public static final String RT_ANOMALY   = "ANOMALY";
    public static final String RT_EXPORT    = "EXPORT";
    public static final String RT_STREAM    = "STREAM";
    public static final String RT_AUTH      = "AUTH";

    // ── Factory helpers ──────────────────────────────────────────────────

    /** Convenience builder to reduce boilerplate in route handlers. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String userId    = "anonymous";
        private String authMethod = "anonymous";
        private String action;
        private String resourceType;
        private String resourceId;
        private Map<String, Object> details = Map.of();
        private String clientIp  = "unknown";
        private String requestId = "unknown";
        private String userAgent;

        public Builder userId(String v)       { this.userId = v; return this; }
        public Builder authMethod(String v)    { this.authMethod = v; return this; }
        public Builder action(String v)        { this.action = v; return this; }
        public Builder resourceType(String v)  { this.resourceType = v; return this; }
        public Builder resourceId(String v)    { this.resourceId = v; return this; }
        public Builder details(Map<String, Object> v) { this.details = v; return this; }
        public Builder clientIp(String v)      { this.clientIp = v; return this; }
        public Builder requestId(String v)     { this.requestId = v; return this; }
        public Builder userAgent(String v)     { this.userAgent = v; return this; }

        public AuditEvent build() {
            return new AuditEvent(
                    Instant.now(), userId, authMethod, action,
                    resourceType, resourceId, details,
                    clientIp, requestId, userAgent
            );
        }
    }
}
