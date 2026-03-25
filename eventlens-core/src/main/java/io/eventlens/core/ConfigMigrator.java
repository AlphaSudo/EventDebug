package io.eventlens.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eventlens.core.exception.ConfigurationException;
import org.slf4j.Logger;

public final class ConfigMigrator {

    private ConfigMigrator() {
    }

    public static JsonNode normalize(JsonNode root, Logger log) {
        if (!(root instanceof ObjectNode objectRoot)) {
            return root;
        }

        boolean hasLegacyDatasource = objectRoot.has("datasource");
        boolean hasPluralDatasources = objectRoot.has("datasources");
        boolean hasLegacyKafka = objectRoot.has("kafka");
        boolean hasPluralStreams = objectRoot.has("streams");

        if (hasLegacyDatasource && hasPluralDatasources) {
            throw new ConfigurationException("Config cannot contain both 'datasource' and 'datasources'. Choose one format.");
        }
        if (hasLegacyKafka && hasPluralStreams) {
            throw new ConfigurationException("Config cannot contain both 'kafka' and 'streams'. Choose one format.");
        }

        if (hasLegacyDatasource && !hasPluralDatasources) {
            ArrayNode datasources = objectRoot.putArray("datasources");
            ObjectNode migrated = datasources.addObject();
            migrated.put("id", "default");
            migrated.put("type", "postgres");
            copyObjectFields(objectRoot.withObject("datasource"), migrated);
            log.warn("Using deprecated v2 'datasource' config format. Migrate to 'datasources[]'.");
        }

        if (hasLegacyKafka && !hasPluralStreams) {
            ArrayNode streams = objectRoot.putArray("streams");
            ObjectNode migrated = streams.addObject();
            migrated.put("id", "default-kafka");
            migrated.put("type", "kafka");
            copyObjectFields(objectRoot.withObject("kafka"), migrated);
            log.warn("Using deprecated v2 'kafka' config format. Migrate to 'streams[]'.");
        }

        if (!objectRoot.has("datasource") && hasPluralDatasources && objectRoot.get("datasources").isArray() && !objectRoot.get("datasources").isEmpty()) {
            JsonNode first = objectRoot.get("datasources").get(0);
            if (first instanceof ObjectNode firstObject) {
                ObjectNode legacy = objectRoot.putObject("datasource");
                copyKnownDatasourceFields(firstObject, legacy);
            }
        }

        if (!objectRoot.has("kafka") && hasPluralStreams && objectRoot.get("streams").isArray()) {
            for (JsonNode stream : objectRoot.withArray("streams")) {
                if (stream instanceof ObjectNode streamObject && "kafka".equals(streamObject.path("type").asText("kafka"))) {
                    ObjectNode legacy = objectRoot.putObject("kafka");
                    copyKnownStreamFields(streamObject, legacy);
                    break;
                }
            }
        }

        return objectRoot;
    }

    private static void copyObjectFields(ObjectNode from, ObjectNode to) {
        from.properties().forEach(entry -> to.set(entry.getKey(), entry.getValue().deepCopy()));
    }

    private static void copyKnownDatasourceFields(ObjectNode from, ObjectNode to) {
        copyIfPresent(from, to, "url");
        copyIfPresent(from, to, "username");
        copyIfPresent(from, to, "password");
        copyIfPresent(from, to, "table");
        copyIfPresent(from, to, "columns");
        copyIfPresent(from, to, "pool");
        copyIfPresent(from, to, "query-timeout-seconds");
    }

    private static void copyKnownStreamFields(ObjectNode from, ObjectNode to) {
        copyIfPresent(from, to, "bootstrap-servers");
        copyIfPresent(from, to, "topic");
    }

    private static void copyIfPresent(ObjectNode from, ObjectNode to, String field) {
        if (from.has(field)) {
            to.set(field, from.get(field).deepCopy());
        }
    }
}
