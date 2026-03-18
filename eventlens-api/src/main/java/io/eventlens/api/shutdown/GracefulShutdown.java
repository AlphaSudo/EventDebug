package io.eventlens.api.shutdown;

import io.eventlens.api.health.HealthService;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

public final class GracefulShutdown {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private GracefulShutdown() {
    }

    public static void register(Javalin app, List<AutoCloseable> resources) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Draining in-flight requests...");

            // Stop accepting new requests
            app.jettyServer().server().setStopTimeout(DRAIN_TIMEOUT.toMillis());

            // Mark not-ready
            HealthService.setShuttingDown(true);

            // Allow load balancer to notice not-ready state
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // Stop Javalin (drains remaining requests)
            try {
                app.stop();
            } catch (Exception e) {
                log.warn("Error stopping Javalin", e);
            }

            // Close resources in reverse order
            for (int i = resources.size() - 1; i >= 0; i--) {
                try {
                    AutoCloseable c = resources.get(i);
                    log.info("Closing {}", c.getClass().getSimpleName());
                    c.close();
                } catch (Exception e) {
                    log.warn("Error closing resource", e);
                }
            }

            log.info("Shutdown complete.");
        }, "eventlens-graceful-shutdown"));
    }
}

