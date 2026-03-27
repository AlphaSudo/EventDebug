package io.eventlens.core.metadata;

import io.eventlens.core.audit.AuditEvent;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class AuditLogRepository {

    private final DataSource dataSource;

    public AuditLogRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public long append(AuditEvent event, Instant createdAt) {
        String sql = """
                INSERT INTO audit_log(
                    action, resource_type, resource_id, user_id, auth_method,
                    client_ip, request_id, user_agent, details_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, event.action());
            ps.setString(2, event.resourceType());
            ps.setString(3, event.resourceId());
            ps.setString(4, event.userId());
            ps.setString(5, event.authMethod());
            ps.setString(6, event.clientIp());
            ps.setString(7, event.requestId());
            ps.setString(8, event.userAgent());
            ps.setString(9, MetadataJsonCodec.writeObjectMap(event.details()));
            ps.setString(10, createdAt.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1L;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to append audit log entry", e);
        }
    }

    public List<AuditLogRecord> findRecent(int limit) {
        return findRecent(limit, null, null);
    }

    public List<AuditLogRecord> findRecent(int limit, String action, String userId) {
        List<AuditLogRecord> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_log");
        List<Object> params = new ArrayList<>();
        if (action != null || userId != null) {
            sql.append(" WHERE ");
            boolean appended = false;
            if (action != null) {
                sql.append("action = ?");
                params.add(action);
                appended = true;
            }
            if (userId != null) {
                if (appended) {
                    sql.append(" AND ");
                }
                sql.append("user_id = ?");
                params.add(userId);
            }
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        params.add(limit);

        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(map(rs));
                }
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query audit log", e);
        }
    }

    private static AuditLogRecord map(ResultSet rs) throws Exception {
        return new AuditLogRecord(
                rs.getLong("audit_id"),
                rs.getString("action"),
                rs.getString("resource_type"),
                rs.getString("resource_id"),
                rs.getString("user_id"),
                rs.getString("auth_method"),
                rs.getString("client_ip"),
                rs.getString("request_id"),
                rs.getString("user_agent"),
                rs.getString("details_json"),
                Instant.parse(rs.getString("created_at")));
    }
}
