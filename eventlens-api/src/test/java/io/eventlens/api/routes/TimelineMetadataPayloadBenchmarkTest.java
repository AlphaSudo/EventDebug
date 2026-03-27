package io.eventlens.api.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventlens.api.cache.QueryResultCache;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.model.AggregateTimeline;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.pii.PiiMasker;
import io.eventlens.core.security.AuthorizationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineMetadataPayloadBenchmarkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void metadataOnlyShapeReducesSerializedPayloadByAtLeastSeventyPercent() throws Exception {
        AggregateTimeline fullTimeline = new AggregateTimeline(
                "ACC-001",
                "BankAccount",
                syntheticEvents(),
                syntheticEvents().size());

        TimelineRoutes routes = new TimelineRoutes(
                null,
                null,
                new PiiMasker(new EventLensConfig.PiiConfig()),
                new QueryResultCache(false, 1),
                Duration.ofSeconds(1),
                new RouteAuthorizer(new AuthorizationService(null)));

        Method metadataOnly = TimelineRoutes.class.getDeclaredMethod("metadataOnly", AggregateTimeline.class);
        metadataOnly.setAccessible(true);
        AggregateTimeline metadataTimeline = (AggregateTimeline) metadataOnly.invoke(routes, fullTimeline);

        int fullBytes = MAPPER.writeValueAsString(fullTimeline).getBytes(StandardCharsets.UTF_8).length;
        int metadataBytes = MAPPER.writeValueAsString(metadataTimeline).getBytes(StandardCharsets.UTF_8).length;
        double reduction = 1.0 - ((double) metadataBytes / (double) fullBytes);

        assertThat(reduction)
                .withFailMessage("Expected metadata-only reduction > 0.70 but was %.2f (full=%s bytes, metadata=%s bytes)", reduction, fullBytes, metadataBytes)
                .isGreaterThan(0.70);
    }

    private static List<StoredEvent> syntheticEvents() {
        String largePayload = "{\"description\":\"" + "x".repeat(4096) + "\",\"nested\":{\"trace\":\"" + "y".repeat(4096) + "\"}}";
        String metadata = "{\"source\":\"benchmark\",\"correlationId\":\"corr-123\"}";
        return java.util.stream.IntStream.rangeClosed(1, 25)
                .mapToObj(i -> new StoredEvent(
                        "evt-" + i,
                        "ACC-001",
                        "BankAccount",
                        i,
                        i == 1 ? "AccountCreated" : "MoneyDeposited",
                        largePayload,
                        metadata,
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i),
                        i
                ))
                .toList();
    }
}
