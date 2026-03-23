package io.eventlens.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.eventlens.core.exception.ConfigurationException;
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
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .findAndRegisterModules();

    public static EventLensConfig load() {
        for (String path : CONFIG_PATHS) {
            File file = new File(path);
            if (file.exists()) {
                try {
                    EventLensConfig config = readAndInterpolate(file);
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
            EventLensConfig config = readAndInterpolate(file);
            log.info("Loaded config from: {}", file.getAbsolutePath());
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse config: " + path, e);
        }
    }

    private static EventLensConfig readAndInterpolate(File file) throws IOException {
        JsonNode root = YAML_MAPPER.readTree(file);
        if (root == null) {
            throw new ConfigurationException("Config file is empty: " + file.getAbsolutePath());
        }
        interpolateNode(root);
        return YAML_MAPPER.treeToValue(root, EventLensConfig.class);
    }

    private static void interpolateNode(JsonNode node) {
        if (node == null) return;

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            for (var e : obj.properties()) {
                JsonNode child = e.getValue();
                if (child != null && child.isTextual()) {
                    obj.set(e.getKey(), TextNode.valueOf(EnvInterpolator.interpolate(child.asText())));
                } else {
                    interpolateNode(child);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode child = arr.get(i);
                if (child != null && child.isTextual()) {
                    arr.set(i, TextNode.valueOf(EnvInterpolator.interpolate(child.asText())));
                } else {
                    interpolateNode(child);
                }
            }
        }
    }
}
