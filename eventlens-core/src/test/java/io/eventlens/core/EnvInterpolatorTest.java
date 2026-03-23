package io.eventlens.core;

import io.eventlens.core.exception.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvInterpolatorTest {

    @Test
    void interpolatesExistingVariable() {
        String out = EnvInterpolator.interpolate("jdbc:postgresql://${DB_HOST}:5432/db",
                k -> Map.of("DB_HOST", "localhost").get(k));

        assertThat(out).isEqualTo("jdbc:postgresql://localhost:5432/db");
    }

    @Test
    void usesDefaultWhenMissing() {
        String out = EnvInterpolator.interpolate("${DB_HOST:-127.0.0.1}",
                k -> null);

        assertThat(out).isEqualTo("127.0.0.1");
    }

    @Test
    void missingWithoutDefaultThrows() {
        assertThatThrownBy(() -> EnvInterpolator.interpolate("pw=${DB_PASSWORD}", k -> null))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void escapedLiteralIsPreserved() {
        String out = EnvInterpolator.interpolate("literal=$${DB_PASSWORD}", k -> "secret");
        assertThat(out).isEqualTo("literal=${DB_PASSWORD}");
    }

    @Test
    void nestedInterpolationIsRejected() {
        assertThatThrownBy(() -> EnvInterpolator.interpolate("${A_${B}}", k -> "x"))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("Nested interpolation");
    }
}

