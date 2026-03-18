package io.eventlens.core.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class AuditLoggerTest {

    @Test
    void log_enabled_doesNotThrow() {
        var logger = new AuditLogger(true);
        var event = AuditEvent.builder()
                .action(AuditEvent.ACTION_SEARCH)
                .resourceType(AuditEvent.RT_AGGREGATE)
                .userId("admin")
                .authMethod("basic")
                .clientIp("127.0.0.1")
                .requestId("el-abc123")
                .details(Map.of("q", "ACC", "limit", 20))
                .build();

        assertThatNoException().isThrownBy(() -> logger.log(event));
    }

    @Test
    void log_disabled_isNoOp() {
        var logger = new AuditLogger(false);
        // Should silently do nothing — no exceptions, no output
        assertThatNoException().isThrownBy(() -> logger.log(null));
    }

    @Test
    void auditEvent_timestamp_isPopulatedByBuild() {
        var event = AuditEvent.builder()
                .action(AuditEvent.ACTION_LOGIN)
                .resourceType(AuditEvent.RT_AUTH)
                .build();

        assertThat(event.timestamp()).isNotNull();
        assertThat(event.action()).isEqualTo(AuditEvent.ACTION_LOGIN);
        assertThat(event.userId()).isEqualTo("anonymous");
    }

    @Test
    void auditEvent_isSerializableToJson() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var event = AuditEvent.builder()
                .action(AuditEvent.ACTION_EXPORT)
                .resourceType(AuditEvent.RT_EXPORT)
                .resourceId("AGG-001")
                .userId("alice")
                .authMethod("basic")
                .clientIp("10.0.0.1")
                .requestId("el-xyz")
                .details(Map.of("format", "csv", "byteCount", 512))
                .build();

        String json = mapper.writeValueAsString(event);

        assertThat(json).contains("\"action\":\"EXPORT\"");
        assertThat(json).contains("\"userId\":\"alice\"");
        assertThat(json).contains("\"resourceId\":\"AGG-001\"");
        assertThat(json).contains("\"format\"");
    }

    @Test
    void auditEvent_allActionConstants_areNonBlank() {
        var constants = List.of(
                AuditEvent.ACTION_SEARCH,
                AuditEvent.ACTION_VIEW_TIMELINE,
                AuditEvent.ACTION_VIEW_ANOMALIES,
                AuditEvent.ACTION_EXPORT,
                AuditEvent.ACTION_LOGIN,
                AuditEvent.ACTION_LOGIN_FAILED,
                AuditEvent.ACTION_VIEW_LIVE_STREAM
        );
        assertThat(constants).allSatisfy(c -> assertThat(c).isNotBlank());
    }
}
