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
        assertThat(issues.stream().filter(i -> i.severity() == ConfigValidator.ValidationError.Severity.ERROR).toList()).isEmpty();
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
    void legacyDatasourceUrlMustBePostgresJdbc() {
        var cfg = new EventLensConfig();
        cfg.getDatasource().setUrl("jdbc:mysql://localhost/db");

        var issues = ConfigValidator.validate(cfg);
        assertThat(issues.stream().anyMatch(i -> i.path().equals("datasource.url")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
    }

    @Test
    void pluralMysqlDatasourceIsValid() {
        var cfg = new EventLensConfig();
        var mysql = new EventLensConfig.DatasourceInstanceConfig();
        mysql.setId("mysql-main");
        mysql.setType("mysql");
        mysql.setUrl("jdbc:mysql://localhost:3306/eventlens");
        mysql.setUsername("root");
        cfg.setDatasources(java.util.List.of(mysql));

        var issues = ConfigValidator.validate(cfg);
        assertThat(issues.stream().filter(i -> i.severity() == ConfigValidator.ValidationError.Severity.ERROR && i.path().startsWith("datasources[0]")).toList()).isEmpty();
    }

    @Test
    void metadataStorageMustUseSqliteWhenEnabled() {
        var cfg = new EventLensConfig();
        cfg.getSecurity().getMetadata().setEnabled(true);
        cfg.getSecurity().getMetadata().setJdbcUrl("jdbc:postgresql://localhost/eventlens_metadata");

        var issues = ConfigValidator.validate(cfg);
        assertThat(issues.stream().anyMatch(i -> i.path().equals("security.metadata.jdbc-url")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
    }

    @Test
    void oidcProviderRequiresMetadataAndCoreSettings() {
        var cfg = new EventLensConfig();
        cfg.getSecurity().getAuth().setProvider("oidc");

        var issues = ConfigValidator.validate(cfg);
        assertThat(issues.stream().anyMatch(i -> i.path().equals("security.metadata.enabled")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
        assertThat(issues.stream().anyMatch(i -> i.path().equals("security.auth.oidc.issuer")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
        assertThat(issues.stream().anyMatch(i -> i.path().equals("security.auth.oidc.client-id")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
        assertThat(issues.stream().anyMatch(i -> i.path().equals("security.auth.oidc.client-secret")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
    }

    @Test
    void sessionCookieNoneRequiresSecureCookie() {
        var cfg = new EventLensConfig();
        cfg.getSecurity().getAuth().getSession().setSameSite("None");
        cfg.getSecurity().getAuth().getSession().setSecureCookie(false);

        var issues = ConfigValidator.validate(cfg);
        assertThat(issues.stream().anyMatch(i -> i.path().equals("security.auth.session.secure-cookie")
                && i.severity() == ConfigValidator.ValidationError.Severity.ERROR)).isTrue();
    }
}
