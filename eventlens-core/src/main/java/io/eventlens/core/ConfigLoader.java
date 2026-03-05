package io.eventlens.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * Discovers and loads the EventLens configuration file from well-known
 * locations.
 *
 * <p>
 * Search order:
 * <ol>
 * <li>{@code eventlens.yaml} (working directory)</li>
 * <li>{@code eventlens.yml} (working directory)</li>
 * <li>{@code ~/.eventlens/config.yaml}</li>
 * <li>{@code /etc/eventlens/config.yaml}</li>
 * </ol>
 * Falls back to sensible defaults when no file is found.
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    private static final String[] CONFIG_PATHS = {
            "eventlens.yaml",
            "eventlens.yml",
            System.getProperty("user.home") + "/.eventlens/config.yaml",
            "/etc/eventlens/config.yaml"
    };

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules();

    public static EventLensConfig load() {
        for (String path : CONFIG_PATHS) {
            File file = new File(path);
            if (file.exists()) {
                try {
                    EventLensConfig config = YAML_MAPPER.readValue(file, EventLensConfig.class);
                    log.info("Loaded config from: {}", file.getAbsolutePath());
                    return config;
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse config: " + path, e);
                }
            }
        }
        log.warn("No config file found — using defaults. Searched: {}", String.join(", ", CONFIG_PATHS));
        return new EventLensConfig();
    }

    /**
     * Load a config from a specific path (e.g. from a --config CLI flag).
     */
    public static EventLensConfig load(String path) {
        File file = new File(path);
        if (!file.exists()) {
            throw new RuntimeException("Config file not found: " + path);
        }
        try {
            EventLensConfig config = YAML_MAPPER.readValue(file, EventLensConfig.class);
            log.info("Loaded config from: {}", file.getAbsolutePath());
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse config: " + path, e);
        }
    }
}
