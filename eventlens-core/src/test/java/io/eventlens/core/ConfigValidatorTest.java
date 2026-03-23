package io.eventlens.core;

import io.eventlens.core.exception.ConfigurationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValidatorTest {

    @Test
    void validDefaultConfigHasNoErrors() {
        var cfg = new EventLensConfig();
        var issues = ConfigValidator.validate(cfg);
        assertThat(issues.stream().filter(i -> i.severity() == ConfigValidator.ValidationError.Severity.ERROR).toList())
                .isEmpty();
    }

    @Test
    void authEnabledRequiresStrongPassword() {
        var cfg = new EventLensConfig();
        cfg.getServer().getAuth().setEnabled(true);
        cfg.getServer().getAuth().setUsername("admin");
        cfg.getServer().getAuth().setPassword("short");

        assertThatThrownBy(() -> ConfigValidator.validateOrThrow(cfg))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("server.auth.password")
                .hasMessageContaining("at least 12 characters");
    }

    @Test
    void datasourceUrlMustBePostgresJdbc() {
        var cfg = new EventLensConfig();
        cfg.getDatasource().setUrl("jdbc:mysql://localhost/db");

        var issues = ConfigValidator.validate(cfg);
        assertThat(issues.stream().anyMatch(i -> i.path().equals("datasource.url")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
    }
}

