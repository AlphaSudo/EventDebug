package io.eventlens.core.metadata;

import javax.sql.DataSource;

/**
 * Typed access point for v5 metadata-backed repositories.
 */
public final class MetadataRepositories {

    private final SessionRepository sessions;
    private final ApiKeyRepository apiKeys;
    private final AuditLogRepository auditLogs;

    public MetadataRepositories(DataSource dataSource) {
        this.sessions = new SessionRepository(dataSource);
        this.apiKeys = new ApiKeyRepository(dataSource);
        this.auditLogs = new AuditLogRepository(dataSource);
    }

    public SessionRepository sessions() {
        return sessions;
    }

    public ApiKeyRepository apiKeys() {
        return apiKeys;
    }

    public AuditLogRepository auditLogs() {
        return auditLogs;
    }
}
