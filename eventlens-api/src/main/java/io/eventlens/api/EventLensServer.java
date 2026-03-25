package io.eventlens.api;

import io.eventlens.api.cache.QueryResultCache;
import io.eventlens.api.routes.*;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.api.websocket.LiveTailWebSocket;
import io.eventlens.api.export.ExportService;
import io.eventlens.api.shutdown.GracefulShutdown;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.routes.MetricsRoutes;
import io.eventlens.api.http.RequestContextMdcFilter;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.RateLimiter;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.*;
import io.eventlens.core.exception.QueryTimeoutException;
import io.eventlens.core.pii.PiiMasker;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.compression.Gzip;
import io.javalin.json.JavalinJackson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
 *
 * <p>v2 additions:
 * <ul>
 *   <li><b>1.8</b> — Audit logging: every significant API action emits a
 *       structured {@link AuditEvent} written to {@code logs/audit.log}.</li>
 *   <li><b>1.9</b> — PII masking: event payloads are scanned for common PII
 *       patterns (email, phone, SSN, credit-card) before inclusion in API
 *       responses.</li>
 * </ul>
 */
public class EventLensServer {

    private static final Logger log = LoggerFactory.getLogger(EventLensServer.class);

    private final Javalin app;
    private final int port;
    private final ExportService exportService;
    private final EventStoreReader reader;

    public EventLensServer(
            EventLensConfig config,
            EventStoreReader reader,
            ReplayEngine replayEngine,
            ReducerRegistry reducerRegistry,
            PluginManager pluginManager,
            String defaultSourceId,
            BisectEngine bisectEngine,
            AnomalyDetector anomalyDetector,
            ExportEngine exportEngine,
            DiffEngine diffEngine) {
        this(config, reader, replayEngine, reducerRegistry, pluginManager, defaultSourceId, bisectEngine, anomalyDetector, exportEngine, diffEngine, Map.of());
    }

    public EventLensServer(
            EventLensConfig config,
            EventStoreReader reader,
            ReplayEngine replayEngine,
            ReducerRegistry reducerRegistry,
            PluginManager pluginManager,
            String defaultSourceId,
            BisectEngine bisectEngine,
            AnomalyDetector anomalyDetector,
            ExportEngine exportEngine,
            DiffEngine diffEngine,
            Map<String, String> sourceStreamBindings) {
        this.port = config.getServer().getPort();
        this.reader = reader;

        // ── 1.8 Audit Logger ──────────────────────────────────────────────
        final AuditLogger auditLogger = new AuditLogger(
                config.getAudit().isEnabled());

        // ── 1.9 PII Masker ────────────────────────────────────────────────
        final PiiMasker piiMasker = new PiiMasker(
                config.getDataProtection().getPii());

        // ── 2.6 Async Export ───────────────────────────────────────────────
        this.exportService = new ExportService(reader, auditLogger, config.getExport());

        // 4.1 Metrics: JVM binders + per-request instrumentation
        EventLensMetrics.initJvmMetrics(EventLensMetrics.registry);

        var sourceRegistry = new SourceRegistry(defaultSourceId, reader, replayEngine, reducerRegistry, pluginManager);
        var queryCache = new QueryResultCache(
                config.getQueryCache().isEnabled(),
                config.getQueryCache().getMaxEntries());

        // ── Security middleware preparation ────────────────────────────────
        var rateLimitCfg = config.getServer().getSecurity() != null
                ? config.getServer().getSecurity().getRateLimit()
                : null;
        final RateLimiter rateLimiter = (rateLimitCfg != null && rateLimitCfg.isEnabled())
                ? new RateLimiter(rateLimitCfg)
                : null;

        // ── Route handler instances ───────────────────────────────────────
        var aggregateRoutes    = new AggregateRoutes(sourceRegistry, auditLogger, queryCache,
                Duration.ofSeconds(config.getQueryCache().getSearchTtlSeconds()));
        var timelineRoutes     = new TimelineRoutes(sourceRegistry, auditLogger, piiMasker, queryCache,
                Duration.ofSeconds(config.getQueryCache().getTimelineTtlSeconds()));
        var datasourceRoutes   = new DatasourceRoutes(sourceRegistry);
        var pluginRoutes       = new PluginRoutes(sourceRegistry);
        var statisticsRoutes   = new StatisticsRoutes(sourceRegistry);
        var bisectRoutes       = new BisectRoutes(bisectEngine);
        var anomalyRoutes      = new AnomalyRoutes(sourceRegistry, config.getAnomaly(), auditLogger);
        var exportRoutes       = new ExportRoutes(exportEngine, auditLogger);
        var asyncExportRoutes  = new AsyncExportRoutes(exportService);
        var healthRoutes       = new HealthRoutes(reader, config.getVersion());
        var metricsRoutes      = new MetricsRoutes();
        var openApiRoutes      = new OpenApiRoutes();
        var liveTailWs         = new LiveTailWebSocket(sourceRegistry, pluginManager, auditLogger, defaultSourceId, sourceStreamBindings);

        var authConfig = config.getServer().getAuth();

        // ── Javalin 7: all routes + handlers inside cfg.routes ────────────
        this.app = Javalin.create(cfg -> {
            cfg.staticFiles.add("/web"); // Embedded React build
            cfg.jsonMapper(new JavalinJackson());
            cfg.http.compressionStrategy = new CompressionStrategy(null, new Gzip());

            // ── Before filters ────────────────────────────────────────────
            cfg.routes.before(ctx -> {
                ctx.attribute("startNs", System.nanoTime());

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

                // 4.2 Structured logging context (MDC)
                new RequestContextMdcFilter().handle(ctx);
            });

            // ── After filter ──────────────────────────────────────────────
            cfg.routes.after(ctx -> {
                try {
                    // 4.1 HTTP metrics (templated path only)
                    String method = ctx.method().name();
                    var ep = ctx.endpoints().lastHttpEndpoint();
                    String path = ep != null ? ep.path : ctx.path();
                    int status = ctx.statusCode();
                    EventLensMetrics.recordHttpRequest(method, path, status);

                    Long startNs = ctx.attribute("startNs");
                    if (startNs != null) {
                        long durationNs = Math.max(0, System.nanoTime() - startNs);
                        EventLensMetrics.recordHttpDuration(method, path, durationNs);
                    }
                } finally {
                    RequestContextMdcFilter.clear();
                }
            });

            // Strict CORS: allowlist only; GET + OPTIONS (read-only)
            cfg.routes.before(ctx -> {
                String origin = ctx.header("Origin");
                if (origin == null) return;

                var allowed = config.getServer().getAllowedOrigins();
                if (allowed == null || allowed.isEmpty() || !allowed.contains(origin)) {
                    ctx.status(403).result("Origin not allowed");
                    ctx.skipRemainingHandlers();
                    return;
                }

                ctx.header("Access-Control-Allow-Origin", origin);
                ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
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
                cfg.routes.before("/api/*", ctx -> {
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

            if (authConfig.isEnabled()) {
                if ("changeme".equals(authConfig.getPassword())) {
                    log.warn("Basic auth is enabled with default password 'changeme'. Change server.auth.password in production.");
                }
                String expectedAuth = authConfig.getUsername() + ":" + authConfig.getPassword();

                cfg.routes.before("/api/*", ctx -> {
                    String clientIp = extractClientIp(ctx);
                    String requestId = ctx.attribute("requestId");
                    String userAgent = ctx.userAgent();
                    String suppliedUser = ctx.basicAuthCredentials() != null
                            ? ctx.basicAuthCredentials().getUsername() : null;
                    String auth = ctx.basicAuthCredentials() != null
                            ? suppliedUser + ":" + ctx.basicAuthCredentials().getPassword()
                            : null;

                    if (!expectedAuth.equals(auth)) {
                        // 1.8 — emit LOGIN_FAILED
                        auditLogger.log(AuditEvent.builder()
                                .action(AuditEvent.ACTION_LOGIN_FAILED)
                                .resourceType(AuditEvent.RT_AUTH)
                                .userId(suppliedUser != null ? suppliedUser : "anonymous")
                                .authMethod("basic")
                                .clientIp(clientIp)
                                .requestId(requestId != null ? requestId : "unknown")
                                .userAgent(userAgent)
                                .details(Map.of("reason", "invalid_credentials", "path", ctx.path()))
                                .build());

                        ctx.status(401)
                                .header("WWW-Authenticate", "Basic realm=\"EventLens\"")
                                .json(Map.of("error", "Unauthorized"));
                        ctx.skipRemainingHandlers();
                    } else {
                        // 1.8 — emit LOGIN success (once per request, not once per session)
                        auditLogger.log(AuditEvent.builder()
                                .action(AuditEvent.ACTION_LOGIN)
                                .resourceType(AuditEvent.RT_AUTH)
                                .userId(suppliedUser)
                                .authMethod("basic")
                                .clientIp(clientIp)
                                .requestId(requestId != null ? requestId : "unknown")
                                .userAgent(userAgent)
                                .details(Map.of("path", ctx.path()))
                                .build());
                        // Store principal for downstream audit events
                        ctx.attribute("auditUserId", suppliedUser);
                        ctx.attribute("auditAuthMethod", "basic");
                    }
                });
                cfg.routes.before("/ws/*", ctx -> {
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

            // ── Routes ────────────────────────────────────────────────────

            // Health (3.3) + legacy alias
            cfg.routes.get("/api/v1/health/live", healthRoutes::live);
            cfg.routes.get("/api/v1/health/ready", healthRoutes::ready);
            // Backwards-compatible endpoint used by tests and existing deployments
            cfg.routes.get("/api/health", healthRoutes::ready);

            // Metrics (4.1)
            cfg.routes.get("/api/v1/metrics", metricsRoutes::metrics);

            // OpenAPI (5.2)
            cfg.routes.get("/api/v1/openapi.json", openApiRoutes::spec);

            // Aggregates (v1)
            cfg.routes.get("/api/v1/aggregates/search", aggregateRoutes::search);
            cfg.routes.get("/api/v1/meta/types", aggregateRoutes::types);
            cfg.routes.get("/api/v1/events/recent", aggregateRoutes::recentEvents);
            cfg.routes.get("/api/v1/datasources", datasourceRoutes::list);
            cfg.routes.get("/api/v1/datasources/{id}/health", datasourceRoutes::health);
            cfg.routes.get("/api/v1/plugins", pluginRoutes::list);
            cfg.routes.get("/api/v1/statistics", statisticsRoutes::get);

            // Legacy aggregate routes (no redirect, but marked deprecated)
            cfg.routes.get("/api/aggregates/search", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/search");
                aggregateRoutes.search(ctx);
            });
            cfg.routes.get("/api/meta/types", ctx -> {
                markDeprecated(ctx, "/api/v1/meta/types");
                aggregateRoutes.types(ctx);
            });
            cfg.routes.get("/api/events/recent", ctx -> {
                markDeprecated(ctx, "/api/v1/events/recent");
                aggregateRoutes.recentEvents(ctx);
            });

            // Timeline / replay (v1)
            cfg.routes.get("/api/v1/aggregates/{id}/timeline", timelineRoutes::getTimeline);
            cfg.routes.get("/api/v1/aggregates/{id}/replay", timelineRoutes::replay);
            cfg.routes.get("/api/v1/aggregates/{id}/replay/{seq}", timelineRoutes::replayTo);
            cfg.routes.get("/api/v1/aggregates/{id}/transitions", timelineRoutes::transitions);

            // Legacy timeline / replay routes
            cfg.routes.get("/api/aggregates/{id}/timeline", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/" + ctx.pathParam("id") + "/timeline");
                timelineRoutes.getTimeline(ctx);
            });
            cfg.routes.get("/api/aggregates/{id}/replay", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/" + ctx.pathParam("id") + "/replay");
                timelineRoutes.replay(ctx);
            });
            cfg.routes.get("/api/aggregates/{id}/replay/{seq}", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/" + ctx.pathParam("id") + "/replay/" + ctx.pathParam("seq"));
                timelineRoutes.replayTo(ctx);
            });
            cfg.routes.get("/api/aggregates/{id}/transitions", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/" + ctx.pathParam("id") + "/transitions");
                timelineRoutes.transitions(ctx);
            });

            // Bisect (v1 + legacy)
            cfg.routes.post("/api/v1/aggregates/{id}/bisect", bisectRoutes::bisect);
            cfg.routes.post("/api/aggregates/{id}/bisect", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/" + ctx.pathParam("id") + "/bisect");
                bisectRoutes.bisect(ctx);
            });

            // Anomalies (v1)
            cfg.routes.get("/api/v1/aggregates/{id}/anomalies", anomalyRoutes::scanAggregate);
            cfg.routes.get("/api/v1/anomalies/recent", anomalyRoutes::scanRecent);

            // Legacy Anomalies
            cfg.routes.get("/api/aggregates/{id}/anomalies", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/" + ctx.pathParam("id") + "/anomalies");
                anomalyRoutes.scanAggregate(ctx);
            });
            cfg.routes.get("/api/anomalies/recent", ctx -> {
                markDeprecated(ctx, "/api/v1/anomalies/recent");
                anomalyRoutes.scanRecent(ctx);
            });

            // Export (v1 + legacy)
            cfg.routes.get("/api/v1/aggregates/{id}/export", exportRoutes::export);
            cfg.routes.get("/api/aggregates/{id}/export", ctx -> {
                markDeprecated(ctx, "/api/v1/aggregates/" + ctx.pathParam("id") + "/export");
                exportRoutes.export(ctx);
            });

            // Async Export (2.6) — v1 + legacy
            cfg.routes.post("/api/v1/events/export", asyncExportRoutes::start);
            cfg.routes.get("/api/v1/events/export/{exportId}", asyncExportRoutes::status);
            cfg.routes.get("/api/v1/events/export/{exportId}/download", asyncExportRoutes::download);

            cfg.routes.post("/api/events/export", ctx -> {
                markDeprecated(ctx, "/api/v1/events/export");
                asyncExportRoutes.start(ctx);
            });
            cfg.routes.get("/api/events/export/{exportId}", ctx -> {
                markDeprecated(ctx, "/api/v1/events/export/" + ctx.pathParam("exportId"));
                asyncExportRoutes.status(ctx);
            });
            cfg.routes.get("/api/events/export/{exportId}/download", ctx -> {
                markDeprecated(ctx, "/api/v1/events/export/" + ctx.pathParam("exportId") + "/download");
                asyncExportRoutes.download(ctx);
            });

            // WebSocket live tail
            cfg.routes.ws("/ws/live", ws -> {
                liveTailWs.configureHandlers(ws);
            });

            // ── Error handling ────────────────────────────────────────────
            cfg.routes.exception(io.eventlens.core.InputValidator.ValidationException.class,
                    (e, ctx) -> ctx.status(400).json(Map.of(
                            "error", "validation_error",
                            "field", e.getField(),
                            "message", e.getMessage()
                    )));
            cfg.routes.exception(QueryTimeoutException.class,
                    (e, ctx) -> ctx.status(504).json(Map.of(
                            "error", "query_timeout",
                            "message", e.getMessage(),
                            "timeoutSeconds", e.getTimeoutSeconds()
                    )));
            cfg.routes.exception(IllegalStateException.class,
                    (e, ctx) -> ctx.status(429).json(Map.of(
                            "error", "too_many_requests",
                            "message", e.getMessage()
                    )));
            cfg.routes.exception(IllegalArgumentException.class,
                    (e, ctx) -> ctx.status(400).json(Map.of("error", e.getMessage())));
            cfg.routes.exception(io.eventlens.core.exception.EventLensException.class,
                    (e, ctx) -> ctx.status(500).json(Map.of("error", e.getMessage())));
            cfg.routes.exception(Exception.class, (e, ctx) -> {
                log.error("Unhandled error", e);
                ctx.status(500).json(Map.of("error", "Internal server error"));
            });
        });
    }

    public void start() {
        app.start(port);
        GracefulShutdown.register(app, java.util.List.of(
                () -> {
                    try {
                        exportService.close();
                    } catch (Exception ignored) {
                    }
                },
                () -> {
                    if (reader instanceof AutoCloseable closeable) {
                        closeable.close();
                    }
                }
        ));
        log.info("EventLens running at http://localhost:{}", port);
    }

    public void stop() {
        app.stop();
    }

    public Javalin getApp() {
        return app;
    }

    private static void markDeprecated(io.javalin.http.Context ctx, String successor) {
        ctx.header("Deprecation", "true");
        ctx.header("Sunset", "2026-01-01");
        ctx.header("Link", "<" + successor + ">; rel=\"successor-version\"");
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



