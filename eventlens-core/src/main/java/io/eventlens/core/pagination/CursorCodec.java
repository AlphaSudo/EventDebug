package io.eventlens.core.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import io.eventlens.core.InputValidator.ValidationException;
import io.eventlens.core.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Cursor codec for keyset pagination.
 *
 * <p>Cursors are base64url-encoded JSON so the format can evolve without
 * breaking clients.
 */
public final class CursorCodec {

    private CursorCodec() {
    }

    public static String encode(long sequence, Instant timestamp) {
        String json = """
                {"seq":%d,"ts":"%s","v":1}
                """.formatted(sequence, timestamp != null ? timestamp.toString() : Instant.EPOCH.toString());
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static CursorData decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return CursorData.START;
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            JsonNode node = JsonUtil.mapper().readTree(new String(raw, StandardCharsets.UTF_8));

            // Accept both "seq" (aggregate timeline) and "gp" (global position) to
            // keep this usable for future endpoints.
            long seq = node.hasNonNull("seq")
                    ? node.get("seq").asLong()
                    : (node.hasNonNull("gp") ? node.get("gp").asLong() : 0L);
            Instant ts = node.hasNonNull("ts")
                    ? Instant.parse(node.get("ts").asText())
                    : Instant.EPOCH;
            return new CursorData(seq, ts);
        } catch (Exception e) {
            throw new ValidationException("cursor", "Invalid cursor format");
        }
    }

    public record CursorData(long sequence, Instant timestamp) {
        public static final CursorData START = new CursorData(0L, Instant.EPOCH);
    }
}

