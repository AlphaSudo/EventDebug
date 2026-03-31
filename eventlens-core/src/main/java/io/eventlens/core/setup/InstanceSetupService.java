package io.eventlens.core.setup;

import io.eventlens.core.ConfigLoader;
import io.eventlens.core.ConfigValidator;
import io.eventlens.core.EventLensConfig;

import java.util.Locale;

/**
 * Tracks first-run setup state and persists a minimal security bootstrap choice.
 */
public final class InstanceSetupService {

    private final EventLensConfig config;
    private final String configPath;
    private final boolean configuredAtStartup;
    private volatile boolean restartRequired = false;

    public InstanceSetupService(EventLensConfig config, String configPath, boolean configuredAtStartup) {
        this.config = config;
        this.configPath = configPath == null || configPath.isBlank()
                ? ConfigLoader.defaultConfigPath()
                : configPath;
        this.configuredAtStartup = configuredAtStartup;
    }

    public SetupStatus status() {
        boolean completed = isCompleted();
        return new SetupStatus(!completed && !restartRequired, restartRequired, configPath);
    }

    public synchronized SetupApplyResult apply(SetupRequest request) {
        if (isCompleted() || restartRequired) {
            throw new IllegalStateException("Setup has already been completed for this instance");
        }

        String mode = normalizeMode(request.mode());
        switch (mode) {
            case "basic" -> applyBasic(request);
            case "oidc" -> applyOidc(request);
            case "disabled" -> applyDisabled();
            default -> throw new IllegalArgumentException("Unsupported setup mode: " + request.mode());
        }

        config.getSecurity().getSetup().setCompleted(true);
        ConfigValidator.validateOrThrow(config);
        ConfigLoader.save(configPath, config);
        restartRequired = true;
        return new SetupApplyResult(true, true, mode, configPath);
    }

    private boolean isCompleted() {
        if (restartRequired) {
            return true;
        }
        Boolean marker = config.getSecurity().getSetup().getCompleted();
        if (Boolean.FALSE.equals(marker)) {
            return false;
        }
        if (Boolean.TRUE.equals(marker)) {
            return true;
        }
        return configuredAtStartup;
    }

    private void applyBasic(SetupRequest request) {
        requireText(request.username(), "username");
        requireText(request.password(), "password");

        config.getServer().getAuth().setEnabled(true);
        config.getServer().getAuth().setUsername(request.username().trim());
        config.getServer().getAuth().setPassword(request.password());

        config.getSecurity().setProductionMode(false);
        config.getSecurity().getMetadata().setEnabled(true);
        config.getSecurity().getAuth().setProvider("basic");
        applyLocalSessionDefaults();
        config.getSecurity().getAuth().getApiKeys().setEnabled(false);
        config.getSecurity().getAuthorization().setEnabled(false);
        config.getAudit().setEnabled(true);
    }

    private void applyOidc(SetupRequest request) {
        requireText(request.issuer(), "issuer");
        requireText(request.clientId(), "clientId");
        requireText(request.clientSecret(), "clientSecret");

        config.getServer().getAuth().setEnabled(false);
        config.getSecurity().setProductionMode(false);
        config.getSecurity().getMetadata().setEnabled(true);
        config.getSecurity().getAuth().setProvider("oidc");
        applyLocalSessionDefaults();
        config.getSecurity().getAuth().getApiKeys().setEnabled(false);
        config.getSecurity().getAuthorization().setEnabled(false);
        config.getSecurity().getAuth().getOidc().setIssuer(request.issuer().trim());
        config.getSecurity().getAuth().getOidc().setClientId(request.clientId().trim());
        config.getSecurity().getAuth().getOidc().setClientSecret(request.clientSecret());
        config.getAudit().setEnabled(true);
    }

    private void applyDisabled() {
        config.getServer().getAuth().setEnabled(false);
        config.getSecurity().setProductionMode(false);
        config.getSecurity().getMetadata().setEnabled(false);
        config.getSecurity().getAuth().setProvider("disabled");
        config.getSecurity().getAuth().getApiKeys().setEnabled(false);
        config.getSecurity().getAuthorization().setEnabled(false);
    }

    private void applyLocalSessionDefaults() {
        config.getSecurity().getAuth().getSession().setCookieName("eventlens_session");
        config.getSecurity().getAuth().getSession().setSecureCookie(false);
        config.getSecurity().getAuth().getSession().setSameSite("Lax");
    }

    private static String normalizeMode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
    }

    public record SetupStatus(boolean setupRequired, boolean restartRequired, String configPath) {
    }

    public record SetupApplyResult(boolean saved, boolean restartRequired, String mode, String configPath) {
    }

    public record SetupRequest(
            String mode,
            String username,
            String password,
            String issuer,
            String clientId,
            String clientSecret) {
    }
}
