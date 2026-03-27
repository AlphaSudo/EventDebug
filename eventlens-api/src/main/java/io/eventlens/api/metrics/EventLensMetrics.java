package io.eventlens.api.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

public final class EventLensMetrics {

    public static final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    // --- HTTP ---
    private static final String HTTP_REQUESTS_TOTAL = "eventlens_http_requests_total";
    private static final String HTTP_REQUEST_DURATION = "eventlens_http_request_duration_seconds";

    // --- WebSocket ---
    private static final AtomicInteger websocketConnectionsValue = new AtomicInteger(0);
    public static final Gauge websocketConnections = Gauge.builder("eventlens_websocket_connections", websocketConnectionsValue, AtomicInteger::get)
            .description("Active WebSocket connections")
            .register(registry);

    private static final AtomicInteger securityMetricsBound = new AtomicInteger(0);

    private EventLensMetrics() {
    }

    public static void initJvmMetrics(MeterRegistry r) {
        new ClassLoaderMetrics().bindTo(r);
        new JvmMemoryMetrics().bindTo(r);
        try (var gcMetrics = new JvmGcMetrics()) {
            gcMetrics.bindTo(r);
        }
        new JvmThreadMetrics().bindTo(r);
        new ProcessorMetrics().bindTo(r);
        new UptimeMetrics().bindTo(r);
    }

    public static void recordHttpRequest(String method, String matchedPath, int statusCode) {
        Counter.builder(HTTP_REQUESTS_TOTAL)
                .description("Total HTTP requests")
                .tags("method", method, "path", matchedPath, "status", String.valueOf(statusCode))
                .register(registry)
                .increment();
    }

    public static void recordHttpDuration(String method, String matchedPath, long durationNs) {
        Timer.builder(HTTP_REQUEST_DURATION)
                .description("HTTP request latency")
                // Enable histogram buckets so Prometheus can calculate quantiles server-side
                // (histogram_quantile). Do not publish client-side percentiles.
                .publishPercentileHistogram()
                .tags("method", method, "path", matchedPath)
                .register(registry)
                .record(durationNs, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public static void setWebsocketConnections(int count) {
        websocketConnectionsValue.set(Math.max(0, count));
    }

    public static void recordAuthAttempt(String method, String outcome) {
        Counter.builder("eventlens_security_auth_attempts_total")
                .description("Authentication attempts by method and outcome")
                .tags("method", normalize(method), "outcome", normalize(outcome))
                .register(registry)
                .increment();
    }

    public static void recordAuthorizationDenied(String permission, String reason) {
        Counter.builder("eventlens_security_authz_denied_total")
                .description("Authorization denials by permission and reason")
                .tags("permission", normalize(permission), "reason", normalize(reason))
                .register(registry)
                .increment();
    }

    public static void recordSessionLifecycle(String action) {
        Counter.builder("eventlens_security_sessions_total")
                .description("Session lifecycle events")
                .tags("action", normalize(action))
                .register(registry)
                .increment();
    }

    public static void bindActiveSessionsGauge(ToDoubleFunction<Object> valueFunction, Object stateObject) {
        if (securityMetricsBound.compareAndSet(0, 1)) {
            Gauge.builder("eventlens_security_sessions_active", stateObject, valueFunction)
                    .description("Active browser sessions backed by metadata storage")
                    .register(registry);
        }
    }

    public static void recordSensitiveAction(String action, String outcome) {
        Counter.builder("eventlens_security_sensitive_actions_total")
                .description("Sensitive actions such as export and PII reveal")
                .tags("action", normalize(action), "outcome", normalize(outcome))
                .register(registry)
                .increment();
    }

    public static void recordApiKeyLifecycle(String action) {
        Counter.builder("eventlens_security_api_keys_total")
                .description("API key lifecycle events")
                .tags("action", normalize(action))
                .register(registry)
                .increment();
    }

    public static void recordAuditWriteFailure(String sink) {
        Counter.builder("eventlens_security_audit_write_failures_total")
                .description("Audit persistence failures by sink")
                .tags("sink", normalize(sink))
                .register(registry)
                .increment();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
