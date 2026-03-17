package io.eventlens.api;

import io.eventlens.api.routes.*;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.engine.*;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * EventLens Javalin HTTP server.
 *
 * <p>
 * Hosts the REST API, WebSocket live tail, and the embedded React UI as static
 * files.
 * CORS is configurable — defaults to localhost-only (not wildcard).
 * Basic auth middleware is applied when enabled in config.
 */
public class EventLensServer {

    private static final Logger log = LoggerFactory.getLogger(EventLensServer.class);

    private final Javalin app;
    private final int port;

    public EventLensServer(
            EventLensConfig config,
            EventStoreReader reader,
            ReplayEngine replayEngine,
            BisectEngine bisectEngine,
            AnomalyDetector anomalyDetector,
            ExportEngine exportEngine,
            DiffEngine diffEngine) {
        this.port = config.getServer().getPort();

        this.app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/web"); // Embedded React build
            cfg.jsonMapper(new JavalinJackson());
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                var origins = config.getServer().getAllowedOrigins();
                if (origins == null || origins.isEmpty()) {
                    // No origins configured: default to localhost only (same as built-in default)
                    rule.allowHost("http://localhost:" + config.getServer().getPort());
                } else if (origins.contains("*")) {
                    // Fix 5: "*" in the list → anyHost(), instead of crashing with
                    // IllegalArgumentException("* is not a valid host").
                    rule.anyHost();
                } else {
                    origins.forEach(rule::allowHost);
                }
            }));
        });

        // ── Security middleware ────────────────────────────────────────────
        app.before(ctx -> {
            // Prevent clickjacking
            ctx.header("X-Frame-Options", "DENY");

            // Prevent MIME sniffing
            ctx.header("X-Content-Type-Options", "nosniff");

            // XSS protection (legacy browsers)
            ctx.header("X-XSS-Protection", "1; mode=block");

            // Referrer policy
            ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");

            // Permissions policy (disable browser features we don't need)
            ctx.header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");

            // Content Security Policy (keep compatible with embedded React UI)
            ctx.header("Content-Security-Policy",
                    "default-src 'self'; " +
                            "script-src 'self'; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "img-src 'self' data:; " +
                            "connect-src 'self' ws: wss:; " +
                            "font-src 'self'; " +
                            "frame-ancestors 'none'");

            // HSTS (only when TLS is detected — reverse proxy sets X-Forwarded-Proto)
            if ("https".equalsIgnoreCase(ctx.header("X-Forwarded-Proto"))) {
                ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }

            // Request ID correlation
            String requestId = ctx.header("X-Request-Id");
            if (requestId == null || requestId.isBlank()) {
                requestId = "el-" + UUID.randomUUID().toString().substring(0, 12);
            }
            ctx.header("X-Request-Id", requestId);
            ctx.attribute("requestId", requestId);
        });

        var authConfig = config.getServer().getAuth();
        if (authConfig.isEnabled()) {
            if ("changeme".equals(authConfig.getPassword())) {
                log.warn("Basic auth is enabled with default password 'changeme'. Change server.auth.password in production.");
            }
            String expectedAuth = authConfig.getUsername() + ":" + authConfig.getPassword();
            app.before("/api/*", ctx -> {
                String auth = ctx.basicAuthCredentials() != null
                        ? ctx.basicAuthCredentials().getUsername() + ":" + ctx.basicAuthCredentials().getPassword()
                        : null;
                if (!expectedAuth.equals(auth)) {
                    ctx.status(401)
                            .header("WWW-Authenticate", "Basic realm=\"EventLens\"")
                            .json(Map.of("error", "Unauthorized"));
                    ctx.skipRemainingHandlers();
                }
            });
            app.before("/ws/*", ctx -> {
                String auth = ctx.basicAuthCredentials() != null
                        ? ctx.basicAuthCredentials().getUsername() + ":" + ctx.basicAuthCredentials().getPassword()
                        : null;
                if (!expectedAuth.equals(auth)) {
                    ctx.status(401)
                            .header("WWW-Authenticate", "Basic realm=\"EventLens\"")
                            .json(Map.of("error", "Unauthorized"));
                    ctx.skipRemainingHandlers();
                }
            });
            log.info("Basic auth ENABLED for /api/* and /ws/*");
        }

        // ── Routes ─────────────────────────────────────────────────────────
        var aggregateRoutes = new AggregateRoutes(reader);
        var timelineRoutes = new TimelineRoutes(replayEngine);
        var bisectRoutes = new BisectRoutes(bisectEngine);
        var anomalyRoutes = new AnomalyRoutes(anomalyDetector);
        var exportRoutes = new ExportRoutes(exportEngine);
        var healthRoutes = new HealthRoutes(reader);

        // Health
        app.get("/api/health", healthRoutes::health);

        // Aggregates
        app.get("/api/aggregates/search", aggregateRoutes::search);
        app.get("/api/meta/types", aggregateRoutes::types);
        app.get("/api/events/recent", aggregateRoutes::recentEvents);

        // Timeline / replay
        app.get("/api/aggregates/{id}/timeline", timelineRoutes::getTimeline);
        app.get("/api/aggregates/{id}/replay", timelineRoutes::replay);
        app.get("/api/aggregates/{id}/replay/{seq}", timelineRoutes::replayTo);
        app.get("/api/aggregates/{id}/transitions", timelineRoutes::transitions);

        // Bisect
        app.post("/api/aggregates/{id}/bisect", bisectRoutes::bisect);

        // Anomalies
        app.get("/api/aggregates/{id}/anomalies", anomalyRoutes::scanAggregate);
        app.get("/api/anomalies/recent", anomalyRoutes::scanRecent);

        // Export
        app.get("/api/aggregates/{id}/export", exportRoutes::export);

        // ── Error handling ─────────────────────────────────────────────────
        app.exception(IllegalArgumentException.class,
                (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
        app.exception(io.eventlens.core.exception.EventLensException.class,
                (e, ctx) -> ctx.status(500).json(Map.of("error", e.getMessage())));
        app.exception(Exception.class, (e, ctx) -> {
            log.error("Unhandled error", e);
            ctx.status(500).json(Map.of("error", "Internal server error"));
        });
    }

    public void start() {
        app.start(port);
        log.info("EventLens running at http://localhost:{}", port);
    }

    public void stop() {
        app.stop();
    }

    public Javalin getApp() {
        return app;
    }
}
