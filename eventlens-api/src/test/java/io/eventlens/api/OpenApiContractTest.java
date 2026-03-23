package io.eventlens.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void openapiJsonExistsAndHasExpectedShape() throws Exception {
        InputStream in = getClass().getClassLoader().getResourceAsStream("openapi.json");
        assertThat(in).as("openapi.json should be on the classpath").isNotNull();

        JsonNode root = MAPPER.readTree(in);
        assertThat(root.get("openapi").asText()).isEqualTo("3.1.0");

        JsonNode info = root.get("info");
        assertThat(info.get("title").asText()).isEqualTo("EventLens API");
        assertThat(info.get("version").asText()).isEqualTo("2.0.0");

        JsonNode paths = root.get("paths");
        assertThat(paths).isNotNull();

        // Key paths that must be present in the spec
        Set<String> requiredPaths = Set.of(
                "/aggregates/search",
                "/aggregates/{aggregateId}/timeline",
                "/events/recent"
        );

        for (String p : requiredPaths) {
            assertThat(paths.has(p))
                    .as("paths.%s should be defined in openapi.json", p)
                    .isTrue();
        }

        // Ensure timeline path defines a GET operation
        JsonNode timelineGet = paths.path("/aggregates/{aggregateId}/timeline").path("get");
        assertThat(timelineGet).isNotNull();
        assertThat(timelineGet.has("responses")).isTrue();
        assertThat(timelineGet.path("responses").has("200")).isTrue();
    }
}

