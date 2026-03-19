package io.eventlens.api.shutdown;

import io.eventlens.api.health.HealthService;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Graceful shutdown handler for the EventLens server.
 *
 * <p>Javalin 7 includes a built-in {@code StatisticsHandler} that ensures
 * active requests are drained before the server actually stops. We only need
 * to register a JVM shutdown hook that orchestrates:
 * <ol>
 *   <li>Mark the service as shutting down (health checks return not-ready).</li>
 *   <li>Wait briefly so load balancers detect the not-ready state.</li>
 *   <li>Stop Javalin (drains remaining in-flight requests).</li>
 *   <li>Close application resources in reverse order.</li>
 * </ol>
 */
public final class GracefulShutdown {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    private GracefulShutdown() {
    }

    public static void register(Javalin app, List<AutoCloseable> resources) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received. Draining in-flight requests...");

            // Mark not-ready
            HealthService.setShuttingDown(true);

            // Allow load balancer to notice not-ready state
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            // Stop Javalin — Javalin 7's built-in StatisticsHandler drains
            // active requests automatically before the server shuts down.
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
