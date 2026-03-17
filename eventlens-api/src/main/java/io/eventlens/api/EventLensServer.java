package io.eventlens.api;

import io.eventlens.api.routes.*;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.RateLimiter;
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
        });

        // ── Security middleware ────────────────────────────────────────────
        var rateLimitCfg = config.getServer().getSecurity() != null
                ? config.getServer().getSecurity().getRateLimit()
                : null;
        final RateLimiter rateLimiter = (rateLimitCfg != null && rateLimitCfg.isEnabled())
                ? new RateLimiter(rateLimitCfg)
                : null;

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

        // Strict CORS: allowlist only; GET + OPTIONS (read-only)
        app.before(ctx -> {
            String origin = ctx.header("Origin");
            if (origin == null) return;

            var allowed = config.getServer().getAllowedOrigins();
            if (allowed == null || allowed.isEmpty() || !allowed.contains(origin)) {
                ctx.status(403).result("Origin not allowed");
                ctx.skipRemainingHandlers();
                return;
            }

            ctx.header("Access-Control-Allow-Origin", origin);
            ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Request-Id");
            ctx.header("Access-Control-Max-Age", String.valueOf(config.getServer().getCorsMaxAgeSeconds()));
            ctx.header("Access-Control-Allow-Credentials", "true");
            ctx.header("Vary", "Origin");

            if ("OPTIONS".equalsIgnoreCase(ctx.method().name())) {
                ctx.status(204);
                ctx.skipRemainingHandlers();
            }
        });

        if (rateLimiter != null) {
            app.before("/api/*", ctx -> {
                String clientIp = extractClientIp(ctx);
                var result = rateLimiter.tryConsume(clientIp);

                ctx.header("X-RateLimit-Limit", String.valueOf(result.limitPerMinute()));
                ctx.header("X-RateLimit-Remaining", String.valueOf(result.remainingTokens()));
                if (!result.allowed()) {
                    ctx.status(429)
                            .header("Retry-After", String.valueOf(result.retryAfterSeconds()))
                            .header("X-RateLimit-Reset", String.valueOf(result.resetEpochSeconds()))
                            .json(Map.of(
                                    "error", "rate_limit_exceeded",
                                    "message", "Too many requests. Retry after %d seconds.".formatted(result.retryAfterSeconds()),
                                    "retryAfterSeconds", result.retryAfterSeconds()
                            ));
                    ctx.skipRemainingHandlers();
                }
            });
        }

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

    private static String extractClientIp(io.javalin.http.Context ctx) {
        String xff = ctx.header("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isBlank()) return first;
        }
        String xri = ctx.header("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return ctx.ip();
    }
}
