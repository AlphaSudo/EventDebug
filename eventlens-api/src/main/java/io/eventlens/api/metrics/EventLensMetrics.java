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

    private EventLensMetrics() {
    }

    public static void initJvmMetrics(MeterRegistry r) {
        new ClassLoaderMetrics().bindTo(r);
        new JvmMemoryMetrics().bindTo(r);
        new JvmGcMetrics().bindTo(r);
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
}

