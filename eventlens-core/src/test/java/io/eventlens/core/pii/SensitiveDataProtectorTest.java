package io.eventlens.core.pii;

import io.eventlens.core.EventLensConfig;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.model.AnomalyReport;
import io.eventlens.core.model.StateTransition;
import io.eventlens.core.model.StoredEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataProtectorTest {

    private final SensitiveDataProtector protector = new SensitiveDataProtector(enabledMasker());

    @Test
    void maskEventMasksSensitivePayloadButPreservesSafeFields() {
        StoredEvent event = new StoredEvent(
                "evt-1",
                "ACC-1",
                "Account",
                1,
                "AccountCreated",
                "{\"email\":\"alice@example.com\",\"status\":\"active\"}",
                "{\"source\":\"test\"}",
                Instant.parse("2026-01-01T00:00:00Z"),
                1L
        );

        StoredEvent masked = protector.maskEvent(event);

        assertThat(masked.payload()).contains("***@***.***");
        assertThat(masked.payload()).contains("\"status\":\"active\"");
        assertThat(masked.metadata()).isEqualTo(event.metadata());
    }

    @Test
    void maskReplayResultMasksStateSnapshotsAndDiffValues() {
        StoredEvent event = new StoredEvent(
                "evt-1",
                "ACC-1",
                "Account",
                1,
                "AccountCreated",
                "{\"email\":\"alice@example.com\"}",
                "{}",
                Instant.parse("2026-01-01T00:00:00Z"),
                1L
        );
        StateTransition transition = new StateTransition(
                event,
                Map.of("email", "alice@example.com"),
                Map.of("email", "alice@example.com", "status", "active"),
                Map.of(
                        "email", new StateTransition.FieldChange(null, "alice@example.com"),
                        "status", new StateTransition.FieldChange(null, "active")
                )
        );
        ReplayEngine.ReplayResult replay = new ReplayEngine.ReplayResult(
                "ACC-1",
                1L,
                Map.of("email", "alice@example.com", "status", "active"),
                List.of(transition)
        );

        ReplayEngine.ReplayResult masked = protector.maskReplayResult(replay);

        assertThat(masked.state()).containsEntry("email", "***@***.***");
        assertThat(masked.transitions()).singleElement().satisfies(result -> {
            assertThat(result.event().payload()).contains("***@***.***");
            assertThat(result.stateAfter()).containsEntry("email", "***@***.***");
            assertThat(result.diff().get("email").newValue()).isEqualTo("***@***.***");
            assertThat(result.diff().get("status").newValue()).isEqualTo("active");
        });
    }

    @Test
    void maskAnomalyMasksStateAtAnomaly() {
        AnomalyReport report = new AnomalyReport(
                "EMAIL_LEAK",
                "Suspicious state",
                AnomalyReport.Severity.HIGH,
                "ACC-1",
                2L,
                "AccountUpdated",
                Instant.parse("2026-01-01T00:00:00Z"),
                Map.of("email", "alice@example.com", "status", "active")
        );

        AnomalyReport masked = protector.maskAnomaly(report);

        assertThat(masked.stateAtAnomaly()).containsEntry("email", "***@***.***");
        assertThat(masked.stateAtAnomaly()).containsEntry("status", "active");
    }

    private static PiiMasker enabledMasker() {
        var config = new EventLensConfig.PiiConfig();
        config.setEnabled(true);
        return new PiiMasker(config);
    }
}
