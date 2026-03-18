package io.eventlens.core.pii;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eventlens.core.EventLensConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class PiiMaskerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PiiMasker enabledMasker;
    private PiiMasker disabledMasker;

    @BeforeEach
    void setUp() {
        var enabledCfg  = new EventLensConfig.PiiConfig();
        enabledCfg.setEnabled(true);
        enabledMasker = new PiiMasker(enabledCfg);

        var disabledCfg = new EventLensConfig.PiiConfig();
        disabledCfg.setEnabled(false);
        disabledMasker = new PiiMasker(disabledCfg);
    }

    // ── Enabled masker — positive cases ─────────────────────────────────

    @ParameterizedTest(name = "masks email: {0}")
    @CsvSource({
            "alice@example.com,      ***@***.***",
            "bob.doe+tag@mail.co.uk, ***@***.***"
    })
    void masks_email(String raw, String expected) throws Exception {
        assertMasked(raw, expected.trim());
    }

    @ParameterizedTest(name = "masks SSN: {0}")
    @CsvSource({
            "123-45-6789, ***-**-****",
            "001-02-0003, ***-**-****"
    })
    void masks_ssn(String raw, String expected) throws Exception {
        assertMasked(raw, expected.trim());
    }

    @ParameterizedTest(name = "masks credit card: {0}")
    @CsvSource({
            "4111 1111 1111 1111, ****-****-****-****",
            "4111-1111-1111-1111, ****-****-****-****",
            "4111111111111111,    ****-****-****-****"
    })
    void masks_creditCard(String raw, String expected) throws Exception {
        assertMasked(raw, expected.trim());
    }

    @Test
    void safe_text_passes_through() throws Exception {
        String raw = "Hello World";
        ObjectNode node = MAPPER.createObjectNode();
        node.put("greeting", raw);
        var result = enabledMasker.mask(node, node.toString());
        assertThat(result.get("greeting").asText()).isEqualTo(raw);
    }

    @Test
    void numbers_and_booleans_pass_through() throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("amount", 1234.56);
        node.put("active", true);
        var result = enabledMasker.mask(node, node.toString());
        assertThat(result.get("amount").asDouble()).isEqualTo(1234.56);
        assertThat(result.get("active").asBoolean()).isTrue();
    }

    @Test
    void nested_object_is_traversed() throws Exception {
        String raw = "{\"user\":{\"email\":\"alice@example.com\",\"name\":\"Alice\"}}";
        var node   = MAPPER.readTree(raw);
        var result = enabledMasker.mask(node, raw);
        assertThat(result.path("user").path("email").asText()).isEqualTo("***@***.***");
        assertThat(result.path("user").path("name").asText()).isEqualTo("Alice");
    }

    @Test
    void array_elements_are_traversed() throws Exception {
        String raw = "{\"emails\":[\"alice@example.com\",\"safe\"]}";
        var node   = MAPPER.readTree(raw);
        var result = enabledMasker.mask(node, raw);
        assertThat(result.path("emails").get(0).asText()).isEqualTo("***@***.***");
        assertThat(result.path("emails").get(1).asText()).isEqualTo("safe");
    }

    @Test
    void null_payload_returns_null() {
        assertThat(enabledMasker.mask(null, null)).isNull();
    }

    // ── Caching ──────────────────────────────────────────────────────────

    @Test
    void same_payload_returns_cached_result() throws Exception {
        String raw = "{\"email\":\"alice@example.com\"}";
        var node   = MAPPER.readTree(raw);

        var first  = enabledMasker.mask(node, raw);
        var second = enabledMasker.mask(node, raw);

        // Both should have masked value
        assertThat(first.path("email").asText()).isEqualTo("***@***.***");
        assertThat(second.path("email").asText()).isEqualTo("***@***.***");
    }

    // ── Disabled masker ──────────────────────────────────────────────────

    @Test
    void disabled_masker_returns_original_node() throws Exception {
        String raw = "{\"email\":\"alice@example.com\"}";
        var node   = MAPPER.readTree(raw);
        var result = disabledMasker.mask(node, raw);
        // Exact same reference
        assertThat(result).isSameAs(node);
    }

    @Test
    void disabled_masker_does_not_throw_on_null() {
        assertThatNoException().isThrownBy(() -> disabledMasker.mask(null, null));
    }

    // ── Helper ──────────────────────────────────────────────────────────

    private void assertMasked(String rawValue, String expectedMask) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("field", rawValue);
        var result = enabledMasker.mask(node, node.toString());
        assertThat(result.get("field").asText())
                .as("expected '%s' to be masked to '%s'", rawValue, expectedMask)
                .isEqualTo(expectedMask);
    }
}
