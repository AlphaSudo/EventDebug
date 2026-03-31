package io.eventlens.core.metadata;

import java.time.Instant;

public record AuditLogRecord(
        long auditId,
        String action,
        String resourceType,
        String resourceId,
        String userId,
        String authMethod,
        String clientIp,
        String requestId,
        String userAgent,
        String detailsJson,
        Instant createdAt) {
}
