package io.eventlens.core.metadata;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;

public final class SessionRepository {

    private final DataSource dataSource;

    public SessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void upsert(SessionRecord record) {
        String sql = """
                INSERT INTO sessions(
                    session_id, principal_user_id, display_name, auth_method, roles_json, attributes_json,
                    created_at, last_seen_at, idle_expires_at, absolute_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    principal_user_id = excluded.principal_user_id,
                    display_name = excluded.display_name,
                    auth_method = excluded.auth_method,
                    roles_json = excluded.roles_json,
                    attributes_json = excluded.attributes_json,
                    created_at = excluded.created_at,
                    last_seen_at = excluded.last_seen_at,
                    idle_expires_at = excluded.idle_expires_at,
                    absolute_expires_at = excluded.absolute_expires_at
                """;
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, record);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upsert session " + record.sessionId(), e);
        }
    }

    public Optional<SessionRecord> findById(String sessionId) {
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT * FROM sessions WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load session " + sessionId, e);
        }
    }

    public void touch(String sessionId, Instant lastSeenAt, Instant idleExpiresAt) {
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE sessions SET last_seen_at = ?, idle_expires_at = ? WHERE session_id = ?")) {
            ps.setString(1, lastSeenAt.toString());
            ps.setString(2, idleExpiresAt.toString());
            ps.setString(3, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to touch session " + sessionId, e);
        }
    }

    public int deleteExpired(Instant now) {
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM sessions WHERE idle_expires_at <= ? OR absolute_expires_at <= ?")) {
            String value = now.toString();
            ps.setString(1, value);
            ps.setString(2, value);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete expired sessions", e);
        }
    }

    public void deleteById(String sessionId) {
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete session " + sessionId, e);
        }
    }

    private static void bind(PreparedStatement ps, SessionRecord record) throws Exception {
        ps.setString(1, record.sessionId());
        ps.setString(2, record.principalUserId());
        ps.setString(3, record.displayName());
        ps.setString(4, record.authMethod());
        ps.setString(5, MetadataJsonCodec.writeList(record.roles()));
        ps.setString(6, MetadataJsonCodec.writeMap(record.attributes()));
        ps.setString(7, record.createdAt().toString());
        ps.setString(8, record.lastSeenAt().toString());
        ps.setString(9, record.idleExpiresAt().toString());
        ps.setString(10, record.absoluteExpiresAt().toString());
    }

    private static SessionRecord map(ResultSet rs) throws Exception {
        return new SessionRecord(
                rs.getString("session_id"),
                rs.getString("principal_user_id"),
                rs.getString("display_name"),
                rs.getString("auth_method"),
                MetadataJsonCodec.readList(rs.getString("roles_json")),
                MetadataJsonCodec.readMap(rs.getString("attributes_json")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("last_seen_at")),
                Instant.parse(rs.getString("idle_expires_at")),
                Instant.parse(rs.getString("absolute_expires_at")));
    }
}
