package io.eventlens.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.eventlens.core.metadata.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Writes structured audit entries to the {@code eventlens.audit} logger.
 *
 * <p>Logback routes that logger to a dedicated rolling file ({@code logs/audit.log})
 * so audit records are never mixed with application logs and can be retained
 * independently (90-day default).
 *
 * <p>Each entry is a single-line JSON object — trivially grep-able and
 * ingestible by log-aggregation tools (ELK, Loki, Datadog, etc.).
 *
 * <p>This class is thread-safe; a single shared instance is created at startup
 * and injected wherever audit logging is needed.
 */
public final class AuditLogger {

    /** Dedicated logger name — Logback routes it to the AUDIT appender only. */
    private static final Logger auditLog =
            LoggerFactory.getLogger("eventlens.audit");

    private final ObjectMapper mapper;
    private final boolean enabled;
    private final AuditLogRepository auditLogRepository;

    public AuditLogger(boolean enabled) {
        this(enabled, null);
    }

    public AuditLogger(boolean enabled, AuditLogRepository auditLogRepository) {
        this.enabled = enabled;
        this.auditLogRepository = auditLogRepository;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Serialise {@code event} to a single-line JSON string and write it to
     * the audit log. Errors are swallowed (never propagate to the caller) to
     * ensure that an audit-logging failure never breaks the API response.
     */
    public void log(AuditEvent event) {
        if (!enabled || event == null) return;
        try {
            auditLog.info(mapper.writeValueAsString(event));
        } catch (Exception ex) {
            // Fallback — log a minimal message so at least something is recorded
            auditLog.info("{\"action\":\"{}\",\"requestId\":\"{}\",\"error\":\"serialisation failed\"}",
                    event.action(), event.requestId());
        }
        if (auditLogRepository != null) {
            try {
                auditLogRepository.append(event, event.timestamp() != null ? event.timestamp() : Instant.now());
            } catch (Exception ex) {
                auditLog.warn("Failed to persist audit event requestId={} action={}", event.requestId(), event.action(), ex);
            }
        }
    }
}
