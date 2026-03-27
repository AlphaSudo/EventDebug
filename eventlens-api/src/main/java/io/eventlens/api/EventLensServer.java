package io.eventlens.api;

import io.eventlens.api.cache.QueryResultCache;
import io.eventlens.api.routes.*;
import io.eventlens.api.source.SourceRegistry;
import io.eventlens.api.websocket.LiveTailWebSocket;
import io.eventlens.api.export.ExportService;
import io.eventlens.api.http.SecurityContext;
import io.eventlens.api.shutdown.GracefulShutdown;
import io.eventlens.api.metrics.EventLensMetrics;
import io.eventlens.api.routes.MetricsRoutes;
import io.eventlens.api.http.RequestContextMdcFilter;
import io.eventlens.api.security.ApiKeyAuthenticator;
import io.eventlens.api.security.BasicAuthenticator;
import io.eventlens.api.security.RouteAuthorizer;
import io.eventlens.api.security.SessionAuthenticator;
import io.eventlens.api.security.oidc.OidcIdTokenValidator;
import io.eventlens.api.security.oidc.OidcLoginStateService;
import io.eventlens.api.security.oidc.OidcProviderClient;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.RateLimiter;
import io.eventlens.core.audit.AuditEvent;
import io.eventlens.core.audit.AuditLogger;
import io.eventlens.core.engine.*;
import io.eventlens.core.exception.QueryTimeoutException;
import io.eventlens.core.metadata.MetadataDatabase;
import io.eventlens.core.pii.PiiMasker;
import io.eventlens.core.pii.SensitiveDataProtector;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.security.ApiKeyService;
import io.eventlens.core.security.AuthorizationService;
import io.eventlens.core.security.Principal;
import io.eventlens.core.security.SessionService;
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
    private final MetadataDatabase metadataDatabase;

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
        this(config, reader, replayEngine, reducerRegistry, pluginManager, defaultSourceId, bisectEngine, anomalyDetector, exportEngine, diffEngine, Map.of(), MetadataDatabase.disabled());
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
        this(config, reader, replayEngine, reducerRegistry, pluginManager, defaultSourceId, bisectEngine, anomalyDetector, exportEngine, diffEngine, sourceStreamBindings, MetadataDatabase.disabled());
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
            Map<String, String> sourceStreamBindings,
            MetadataDatabase metadataDatabase) {
        this.port = config.getServer().getPort();
        this.reader = reader;
        this.metadataDatabase = metadataDatabase == null ? MetadataDatabase.disabled() : metadataDatabase;

        // ── 1.8 Audit Logger ──────────────────────────────────────────────
        final AuditLogger auditLogger = new AuditLogger(
                config.getAudit().isEnabled(),
                this.metadataDatabase.isEnabled() ? this.metadataDatabase.repositories().auditLogs() : null,
                () -> EventLensMetrics.recordAuditWriteFailure("metadata"));

        // ── 1.9 PII Masker ────────────────────────────────────────────────
        final PiiMasker piiMasker = new PiiMasker(
                config.getDataProtection().getPii());
        final SensitiveDataProtector sensitiveDataProtector = new SensitiveDataProtector(piiMasker);

        // ── 2.6 Async Export ───────────────────────────────────────────────
        this.exportService = new ExportService(reader, auditLogger, config.getExport(), sensitiveDataProtector);

        // 4.1 Metrics: JVM binders + per-request instrumentation
        EventLensMetrics.initJvmMetrics(EventLensMetrics.registry);

        var sourceRegistry = new SourceRegistry(defaultSourceId, reader, replayEngine, reducerRegistry, pluginManager);
        var queryCache = new QueryResultCache(
                config.getQueryCache().isEnabled(),
                config.getQueryCache().getMaxEntries());
        var authorizationConfig = config.getSecurity() != null ? config.getSecurity().getAuthorization() : null;
        var authorizationService = new AuthorizationService(authorizationConfig);
        var routeAuthorizer = new RouteAuthorizer(authorizationService);

        // ── Security middleware preparation ────────────────────────────────
        var rateLimitCfg = config.getServer().getSecurity() != null
                ? config.getServer().getSecurity().getRateLimit()
                : null;
        final RateLimiter rateLimiter = (rateLimitCfg != null && rateLimitCfg.isEnabled())
                ? new RateLimiter(rateLimitCfg)
                : null;

        // ── Route handler instances ───────────────────────────────────────
        var aggregateRoutes    = new AggregateRoutes(sourceRegistry, auditLogger, queryCache,
                Duration.ofSeconds(config.getQueryCache().getSearchTtlSeconds()), routeAuthorizer);
        var timelineRoutes     = new TimelineRoutes(sourceRegistry, auditLogger, sensitiveDataProtector, queryCache,
                Duration.ofSeconds(config.getQueryCache().getTimelineTtlSeconds()), routeAuthorizer);
        var datasourceRoutes   = new DatasourceRoutes(sourceRegistry, routeAuthorizer);
        var pluginRoutes       = new PluginRoutes(sourceRegistry, routeAuthorizer);
        var statisticsRoutes   = new StatisticsRoutes(sourceRegistry, routeAuthorizer);
        var bisectRoutes       = new BisectRoutes(bisectEngine, routeAuthorizer);
        var anomalyRoutes      = new AnomalyRoutes(sourceRegistry, config.getAnomaly(), auditLogger, routeAuthorizer, sensitiveDataProtector);
        var exportRoutes       = new ExportRoutes(sourceRegistry, exportEngine, auditLogger, routeAuthorizer, sensitiveDataProtector);
        var asyncExportRoutes  = new AsyncExportRoutes(exportService, routeAuthorizer);
        var healthRoutes       = new HealthRoutes(reader, config.getVersion(), this.metadataDatabase);
        var metricsRoutes      = new MetricsRoutes(routeAuthorizer);
        var auditRoutes        = new AuditRoutes(this.metadataDatabase.isEnabled() ? this.metadataDatabase.repositories().auditLogs() : null, routeAuthorizer);
        var openApiRoutes      = new OpenApiRoutes(routeAuthorizer);
        var piiRevealRoutes    = new PiiRevealRoutes(sourceRegistry, auditLogger, routeAuthorizer);
        var liveTailWs         = new LiveTailWebSocket(sourceRegistry, pluginManager, auditLogger, defaultSourceId, sourceStreamBindings);

        var authConfig = config.getServer().getAuth();
        var v5AuthConfig = config.getSecurity() != null ? config.getSecurity().getAuth() : null;
        var authProvider = v5AuthConfig != null ? v5AuthConfig.getProvider() : "disabled";
        var apiKeysConfig = v5AuthConfig != null ? v5AuthConfig.getApiKeys() : new EventLensConfig.ApiKeysConfig();
        boolean apiKeysEnabled = apiKeysConfig != null && apiKeysConfig.isEnabled() && this.metadataDatabase.isEnabled();
        boolean sessionProviderEnabled = "basic".equalsIgnoreCase(authProvider) || "oidc".equalsIgnoreCase(authProvider);
        boolean oidcEnabled = "oidc".equalsIgnoreCase(authProvider);
        boolean basicCompatibilityEnabled = authConfig.isEnabled();
        boolean authEnabled = basicCompatibilityEnabled || sessionProviderEnabled || apiKeysEnabled;
        var sessionConfig = v5AuthConfig != null ? v5AuthConfig.getSession() : new EventLensConfig.SessionConfig();
        var oidcCallbackPath = oidcEnabled ? v5AuthConfig.getOidc().getRedirectPath() : "/api/v1/auth/callback";
        var basicAuthenticator = new BasicAuthenticator(
                authConfig.getUsername(),
                authConfig.getPassword(),
                "EventLens");
        var apiKeyService = apiKeysEnabled
                ? new ApiKeyService(this.metadataDatabase.repositories().apiKeys(), apiKeysConfig)
                : null;
        var apiKeyAuthenticator = apiKeyService != null
                ? new ApiKeyAuthenticator(apiKeyService, apiKeysConfig.getHeaderName())
                : null;
        var sessionService = this.metadataDatabase.isEnabled()
                ? new SessionService(this.metadataDatabase.repositories().sessions(), sessionConfig)
                : null;
        if (sessionService != null) {
            EventLensMetrics.bindActiveSessionsGauge(state -> ((SessionService) state).activeSessionCount(), sessionService);
        }
        var sessionAuthenticator = sessionService != null
                ? new SessionAuthenticator(sessionService, sessionConfig.getCookieName())
                : null;
        var authRoutes = sessionService != null
                ? new AuthRoutes(sessionService, sessionConfig, basicAuthenticator, auditLogger, authProvider, basicCompatibilityEnabled)
                : null;
        var oidcProviderClient = new OidcProviderClient();
        var oidcRoutes = oidcEnabled && sessionService != null
                ? new OidcAuthRoutes(
                        sessionService,
                        sessionConfig,
                        v5AuthConfig.getOidc(),
                        oidcProviderClient,
                        new OidcIdTokenValidator(oidcProviderClient),
                        new OidcLoginStateService(this.metadataDatabase.repositories().sessions()),
                        auditLogger)
                : null;
        var apiKeyRoutes = apiKeyService != null ? new ApiKeyRoutes(apiKeyService, routeAuthorizer, auditLogger) : null;

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
                SecurityContext.setPrincipal(ctx, Principal.anonymous());

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
                    String clientIp = SecurityContext.clientIp(ctx);
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

            if (authEnabled) {
                if ("changeme".equals(authConfig.getPassword())) {
                    log.warn("Basic auth is enabled with default password 'changeme'. Change server.auth.password in production.");
                }

                cfg.routes.before("/api/*", ctx -> {
                    if (isPublicAuthPath(ctx.path(), oidcCallbackPath)) {
                        if (sessionAuthenticator != null && hasSessionCookie(ctx, sessionConfig.getCookieName())) {
                            var sessionResult = sessionAuthenticator.authenticate(ctx);
                            if (sessionResult.success()) {
                                SecurityContext.setPrincipal(ctx, sessionResult.principal());
                                new RequestContextMdcFilter().handle(ctx);
                            }
                        }
                        return;
                    }

                    var authHolder = resolveAuthentication(
                            ctx,
                            apiKeyAuthenticator,
                            sessionAuthenticator,
                            basicCompatibilityEnabled ? basicAuthenticator : null,
                            apiKeysConfig != null ? apiKeysConfig.getHeaderName() : "X-API-Key",
                            sessionConfig.getCookieName());
                    var authResult = authHolder.result();
                    if (!authResult.success()) {
                        EventLensMetrics.recordAuthAttempt(authHolder.method(), "failure");
                        // 1.8 - emit LOGIN_FAILED
                        auditLogger.log(SecurityContext.audit(ctx)
                                .action(AuditEvent.ACTION_LOGIN_FAILED)
                                .resourceType(AuditEvent.RT_AUTH)
                                .userId(authResult.attemptedUserId() != null ? authResult.attemptedUserId() : "anonymous")
                                .authMethod(authHolder.method())
                                .details(Map.of("reason", authResult.failureReason(), "path", ctx.path()))
                                .build());

                        ctx.status(401);
                        if (authResult.challengeHeader() != null) {
                            ctx.header("WWW-Authenticate", authResult.challengeHeader());
                        }
                        ctx.json(Map.of("error", "Unauthorized"));
                        ctx.skipRemainingHandlers();
                    } else {
                        EventLensMetrics.recordAuthAttempt(authHolder.method(), "success");
                        SecurityContext.setPrincipal(ctx, authResult.principal());
                        new RequestContextMdcFilter().handle(ctx);
                        if (isMutatingMethod(ctx.method().name()) && SecurityContext.session(ctx) != null) {
                            String expectedCsrf = SecurityContext.csrfToken(ctx);
                            String suppliedCsrf = ctx.header("X-CSRF-Token");
                            if (expectedCsrf == null || !expectedCsrf.equals(suppliedCsrf)) {
                                ctx.status(403).json(Map.of("error", "csrf_required"));
                                ctx.skipRemainingHandlers();
                                return;
                            }
                        }
                        if ("basic".equals(authHolder.method())) {
                            auditLogger.log(SecurityContext.audit(ctx)
                                    .action(AuditEvent.ACTION_LOGIN)
                                    .resourceType(AuditEvent.RT_AUTH)
                                    .details(Map.of("path", ctx.path()))
                                    .build());
                        }
                    }
                });
                cfg.routes.before("/ws/*", ctx -> {
                    var authHolder = resolveAuthentication(
                            ctx,
                            apiKeyAuthenticator,
                            sessionAuthenticator,
                            basicCompatibilityEnabled ? basicAuthenticator : null,
                            apiKeysConfig != null ? apiKeysConfig.getHeaderName() : "X-API-Key",
                            sessionConfig.getCookieName());
                    var authResult = authHolder.result();
                    if (!authResult.success()) {
                        EventLensMetrics.recordAuthAttempt(authHolder.method(), "failure");
                        ctx.status(401);
                        if (authResult.challengeHeader() != null) {
                            ctx.header("WWW-Authenticate", authResult.challengeHeader());
                        }
                        ctx.json(Map.of("error", "Unauthorized"));
                        ctx.skipRemainingHandlers();
                    } else {
                        EventLensMetrics.recordAuthAttempt(authHolder.method(), "success");
                        SecurityContext.setPrincipal(ctx, authResult.principal());
                        new RequestContextMdcFilter().handle(ctx);
                    }
                });
                log.info("Auth enabled for /api/* and /ws/* with provider={} basicCompatibility={}", authProvider, basicCompatibilityEnabled);
            }

            // ── Routes ────────────────────────────────────────────────────

            // Health (3.3) + legacy alias
            cfg.routes.get("/api/v1/health/live", healthRoutes::live);
            cfg.routes.get("/api/v1/health/ready", healthRoutes::ready);
            // Backwards-compatible endpoint used by tests and existing deployments
            cfg.routes.get("/api/health", healthRoutes::ready);

            // Metrics (4.1)
            cfg.routes.get("/api/v1/metrics", metricsRoutes::metrics);
            cfg.routes.get("/api/v1/audit", auditRoutes::recent);
            if (apiKeyRoutes != null) {
                cfg.routes.get("/api/v1/admin/api-keys", apiKeyRoutes::list);
                cfg.routes.post("/api/v1/admin/api-keys", apiKeyRoutes::create);
                cfg.routes.post("/api/v1/admin/api-keys/{id}/revoke", apiKeyRoutes::revoke);
            }

            // OpenAPI (5.2)
            cfg.routes.get("/api/v1/openapi.json", openApiRoutes::spec);

            if (authRoutes != null) {
                cfg.routes.get("/api/v1/auth/session", authRoutes::session);
                cfg.routes.post("/api/v1/auth/logout", authRoutes::logout);
                cfg.routes.post("/api/v1/auth/login/basic", authRoutes::createBasicSession);
            }
            if (oidcRoutes != null) {
                cfg.routes.get("/api/v1/auth/login/oidc", oidcRoutes::start);
                cfg.routes.get(v5AuthConfig.getOidc().getRedirectPath(), oidcRoutes::callback);
            }

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
            cfg.routes.post("/api/v1/aggregates/{id}/events/{seq}/reveal", piiRevealRoutes::revealEvent);
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
                    try {
                        metadataDatabase.close();
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
        try {
            exportService.close();
        } catch (Exception ignored) {
        }
        try {
            metadataDatabase.close();
        } catch (Exception ignored) {
        }
        try {
            if (reader instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception ignored) {
        }
    }

    public Javalin getApp() {
        return app;
    }

    private static void markDeprecated(io.javalin.http.Context ctx, String successor) {
        ctx.header("Deprecation", "true");
        ctx.header("Sunset", "2026-01-01");
        ctx.header("Link", "<" + successor + ">; rel=\"successor-version\"");
    }

    private static boolean isPublicAuthPath(String path, String oidcCallbackPath) {
        return "/api/v1/auth/session".equals(path)
                || "/api/v1/auth/logout".equals(path)
                || "/api/v1/auth/login/basic".equals(path)
                || "/api/v1/auth/login/oidc".equals(path)
                || oidcCallbackPath.equals(path);
    }

    private static boolean hasSessionCookie(io.javalin.http.Context ctx, String cookieName) {
        String value = ctx.cookie(cookieName);
        return value != null && !value.isBlank();
    }

    private static boolean isMutatingMethod(String method) {
        return "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method);
    }

    private static AuthenticationResultHolder resolveAuthentication(
            io.javalin.http.Context ctx,
            ApiKeyAuthenticator apiKeyAuthenticator,
            SessionAuthenticator sessionAuthenticator,
            BasicAuthenticator basicAuthenticator,
            String apiKeyHeaderName,
            String sessionCookieName) {
        if (apiKeyAuthenticator != null && hasApiKeyHeader(ctx, apiKeyHeaderName)) {
            return new AuthenticationResultHolder("api-key", apiKeyAuthenticator.authenticate(ctx));
        }
        if (sessionAuthenticator != null && hasSessionCookie(ctx, sessionCookieName)) {
            return new AuthenticationResultHolder("session", sessionAuthenticator.authenticate(ctx));
        }
        if (basicAuthenticator != null) {
            return new AuthenticationResultHolder("basic", basicAuthenticator.authenticate(ctx));
        }
        return new AuthenticationResultHolder("session", io.eventlens.api.security.AuthenticationResult.failure(null, "missing_session", null));
    }

    private static boolean hasApiKeyHeader(io.javalin.http.Context ctx, String headerName) {
        String value = ctx.header(headerName);
        return value != null && !value.isBlank();
    }

    private record AuthenticationResultHolder(String method, io.eventlens.api.security.AuthenticationResult result) {
    }
}
