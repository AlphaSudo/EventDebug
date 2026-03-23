package io.eventlens.core.pii;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.eventlens.core.EventLensConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans event payload {@link JsonNode}s for common PII patterns and replaces
 * matched text values with a configured mask string.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li>Masking is applied <em>globally</em> in v2 — no role-based bypass yet
 *       (that comes in v5).</li>
 *   <li>Only {@code TextNode}s are inspected; numbers, booleans, and nulls
 *       pass through unchanged.</li>
 *   <li>A Caffeine cache keyed on the SHA-256 of the raw payload string
 *       keeps repeat costs to &lt;0.1ms after warm-up.</li>
 *   <li>When {@code enabled = false} the {@link #mask(JsonNode, String)} call
 *       returns the node reference unchanged — zero overhead.</li>
 * </ul>
 */
public final class PiiMasker {

    private static final Logger log = LoggerFactory.getLogger(PiiMasker.class);

    private final boolean enabled;
    private final List<MaskingRule> rules;

    /** Cache: SHA-256(raw payload JSON string) → masked JsonNode (deep copy). */
    private final Cache<String, JsonNode> cache;

    public PiiMasker(EventLensConfig.PiiConfig config) {
        this.enabled = config.isEnabled();
        this.rules   = config.getPatterns().stream()
                .map(p -> new MaskingRule(p.getName(),
                        Pattern.compile(p.getRegex()), p.getMask()))
                .toList();
        this.cache  = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();

        if (enabled) {
            log.info("PII masking ENABLED with {} rule(s): {}",
                    rules.size(),
                    rules.stream().map(MaskingRule::name).toList());
        } else {
            log.info("PII masking DISABLED (data-protection.pii.enabled=false)");
        }
    }

    /**
     * Returns a masked copy of {@code payload}, or the original node unchanged
     * when masking is disabled or the payload is null.
     *
     * @param payload    the raw event payload as a Jackson tree
     * @param rawPayload the original serialised string used as cache key;
     *                   pass {@code null} to skip caching
     */
    public JsonNode mask(JsonNode payload, String rawPayload) {
        if (!enabled || payload == null) return payload;

        if (rawPayload != null) {
            String key = sha256(rawPayload);
            return cache.get(key, k -> maskNode(payload.deepCopy()));
        }
        return maskNode(payload.deepCopy());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private JsonNode maskNode(JsonNode node) {
        if (node.isTextual()) {
            String value = node.asText();
            for (MaskingRule rule : rules) {
                if (rule.pattern().matcher(value).find()) {
                    return new TextNode(rule.mask());
                }
            }
            return node;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.properties().forEach(entry ->
                    obj.set(entry.getKey(), maskNode(entry.getValue())));
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                arr.set(i, maskNode(arr.get(i)));
            }
            return arr;
        }
        // numbers, booleans, nulls — pass through
        return node;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // Should never happen — SHA-256 is always available
            return String.valueOf(input.hashCode());
        }
    }

    private record MaskingRule(String name, Pattern pattern, String mask) {}
}
