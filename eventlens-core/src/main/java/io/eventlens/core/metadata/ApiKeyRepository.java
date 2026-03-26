package io.eventlens.core.metadata;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ApiKeyRepository {

    private final DataSource dataSource;

    public ApiKeyRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void insert(ApiKeyRecord record) {
        String sql = """
                INSERT INTO api_keys(
                    api_key_id, key_prefix, key_hash, description, principal_user_id, scopes_json,
                    created_at, expires_at, revoked_at, last_used_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            bind(ps, record);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert api key " + record.apiKeyId(), e);
        }
    }

    public Optional<ApiKeyRecord> findByPrefix(String keyPrefix) {
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT * FROM api_keys WHERE key_prefix = ?")) {
            ps.setString(1, keyPrefix);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load API key prefix " + keyPrefix, e);
        }
    }

    public List<ApiKeyRecord> list() {
        List<ApiKeyRecord> results = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT * FROM api_keys ORDER BY created_at DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(map(rs));
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list API keys", e);
        }
    }

    public void markUsed(String apiKeyId, Instant lastUsedAt) {
        updateTimestamp("UPDATE api_keys SET last_used_at = ? WHERE api_key_id = ?", apiKeyId, lastUsedAt);
    }

    public void revoke(String apiKeyId, Instant revokedAt) {
        updateTimestamp("UPDATE api_keys SET revoked_at = ? WHERE api_key_id = ?", apiKeyId, revokedAt);
    }

    private void updateTimestamp(String sql, String apiKeyId, Instant timestamp) {
        try (var connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, timestamp != null ? timestamp.toString() : null);
            ps.setString(2, apiKeyId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update API key " + apiKeyId, e);
        }
    }

    private static void bind(PreparedStatement ps, ApiKeyRecord record) throws Exception {
        ps.setString(1, record.apiKeyId());
        ps.setString(2, record.keyPrefix());
        ps.setString(3, record.keyHash());
        ps.setString(4, record.description());
        ps.setString(5, record.principalUserId());
        ps.setString(6, MetadataJsonCodec.writeList(record.scopes()));
        ps.setString(7, record.createdAt().toString());
        ps.setString(8, record.expiresAt() != null ? record.expiresAt().toString() : null);
        ps.setString(9, record.revokedAt() != null ? record.revokedAt().toString() : null);
        ps.setString(10, record.lastUsedAt() != null ? record.lastUsedAt().toString() : null);
    }

    private static ApiKeyRecord map(ResultSet rs) throws Exception {
        return new ApiKeyRecord(
                rs.getString("api_key_id"),
                rs.getString("key_prefix"),
                rs.getString("key_hash"),
                rs.getString("description"),
                rs.getString("principal_user_id"),
                MetadataJsonCodec.readList(rs.getString("scopes_json")),
                Instant.parse(rs.getString("created_at")),
                parseInstant(rs.getString("expires_at")),
                parseInstant(rs.getString("revoked_at")),
                parseInstant(rs.getString("last_used_at")));
    }

    private static Instant parseInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
