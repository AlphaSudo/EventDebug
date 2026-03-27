package io.eventlens.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.eventlens.core.EventLensConfig;
import io.eventlens.core.aggregator.ReducerRegistry;
import io.eventlens.core.engine.AnomalyDetector;
import io.eventlens.core.engine.BisectEngine;
import io.eventlens.core.engine.DiffEngine;
import io.eventlens.core.engine.ExportEngine;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.metadata.MetadataDatabase;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.core.plugin.PluginManager;
import io.eventlens.core.spi.EventStoreReader;
import io.eventlens.api.security.oidc.OidcLoginStateService;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationIntegrationTest {

    private EventLensServer server;
    private FakeOidcProvider oidcProvider;
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (oidcProvider != null) {
            oidcProvider.stop();
        }
    }

    @Test
    void protectedApiRejectsMissingBasicCredentials() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Basic realm=\"EventLens\"");
    }

    @Test
    void protectedApiAcceptsValidBasicCredentials() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ORD-1001");
    }

    @Test
    void authDisabledStillAllowsExistingReadFlow() throws Exception {
        int port = freePort();
        server = startServer(port, false);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("ORD-1001");
    }

    @Test
    void basicLoginCanCreateSessionCookieForSubsequentRequests() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        var loginRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/auth/login/basic".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"returnHash\":\"#/timeline\"}"))
                .build();

        HttpResponse<String> loginResponse = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
        String setCookie = loginResponse.headers().firstValue("Set-Cookie").orElse("");

        assertThat(loginResponse.statusCode()).isEqualTo(200);
        assertThat(setCookie).contains("eventlens_test_session=");

        String cookieHeader = setCookie.substring(0, setCookie.indexOf(';'));
        var apiRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .header("Cookie", cookieHeader)
                .GET()
                .build();

        HttpResponse<String> apiResponse = client.send(apiRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(apiResponse.statusCode()).isEqualTo(200);
        assertThat(apiResponse.body()).contains("ORD-1001");
        assertThat(loginResponse.body()).contains("\"csrfToken\"");
    }

    @Test
    void logoutRequiresCsrfTokenForSessionAuthenticatedBrowserFlow() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        var loginRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/auth/login/basic".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"returnHash\":\"#/timeline\"}"))
                .build();
        HttpResponse<String> loginResponse = client.send(loginRequest, HttpResponse.BodyHandlers.ofString());
        String setCookie = loginResponse.headers().firstValue("Set-Cookie").orElse("");
        String cookieHeader = setCookie.substring(0, setCookie.indexOf(';'));
        String csrfToken = extractJsonField(loginResponse.body(), "csrfToken");

        var logoutWithoutCsrf = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/auth/logout".formatted(port)))
                .header("Cookie", cookieHeader)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> forbidden = client.send(logoutWithoutCsrf, HttpResponse.BodyHandlers.ofString());
        assertThat(forbidden.statusCode()).isEqualTo(403);

        var logoutWithCsrf = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/auth/logout".formatted(port)))
                .header("Cookie", cookieHeader)
                .header("X-CSRF-Token", csrfToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> allowed = client.send(logoutWithCsrf, HttpResponse.BodyHandlers.ofString());
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.body()).contains("\"authenticated\":false");
    }

    @Test
    void oidcLoginCreatesBrowserSessionAndRedirectsBackToHashRoute() throws Exception {
        int providerPort = freePort();
        int appPort = freePort();
        oidcProvider = new FakeOidcProvider(providerPort);
        oidcProvider.start();

        server = startOidcServer(appPort, "http://localhost:" + providerPort);

        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var startRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/auth/login/oidc?returnHash=%%23/stats".formatted(appPort)))
                .GET()
                .build();
        HttpResponse<String> startResponse = client.send(startRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(startResponse.statusCode()).isEqualTo(302);
        String providerAuthorize = startResponse.headers().firstValue("Location").orElseThrow();
        assertThat(providerAuthorize).contains("state=");

        var authorizeRequest = HttpRequest.newBuilder()
                .uri(URI.create(providerAuthorize))
                .GET()
                .build();
        HttpResponse<String> authorizeResponse = client.send(authorizeRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(authorizeResponse.statusCode()).isEqualTo(302);
        String callbackUrl = authorizeResponse.headers().firstValue("Location").orElseThrow();
        assertThat(callbackUrl).contains("/api/v1/auth/callback");

        var callbackRequest = HttpRequest.newBuilder()
                .uri(URI.create(callbackUrl))
                .GET()
                .build();
        HttpResponse<String> callbackResponse = client.send(callbackRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(callbackResponse.statusCode()).isEqualTo(302);
        assertThat(callbackResponse.headers().firstValue("Location")).hasValue("/#/stats");
        String sessionCookie = callbackResponse.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("eventlens_test_session="))
                .findFirst()
                .orElse("");
        assertThat(sessionCookie).contains("eventlens_test_session=");

        var sessionRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/auth/session".formatted(appPort)))
                .GET()
                .build();
        HttpResponse<String> sessionResponse = client.send(sessionRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(sessionResponse.statusCode()).isEqualTo(200);
        assertThat(sessionResponse.body()).contains("\"authenticated\":true");
        assertThat(sessionResponse.body()).contains("\"authMethod\":\"oidc\"");

        var apiRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(appPort)))
                .GET()
                .build();
        HttpResponse<String> apiResponse = client.send(apiRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(apiResponse.statusCode()).isEqualTo(200);
        assertThat(apiResponse.body()).contains("ORD-1001");
    }

    @Test
    void authorizationRejectsAuthenticatedUserWithoutPermission() throws Exception {
        int port = freePort();
        server = startServer(port, true, null, cfg -> {
            cfg.getSecurity().getAuthorization().setEnabled(true);
            cfg.getSecurity().getAuthorization().setPrincipalRoles(Map.of("admin", List.of("datasource-reader")));

            var role = new EventLensConfig.RoleConfig();
            role.setId("datasource-reader");
            role.setPermissions(List.of("VIEW_DATASOURCES"));
            cfg.getSecurity().getAuthorization().setRoles(List.of(role));
        });

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"reason\":\"DENY_MISSING_PERMISSION\"");
        assertThat(response.body()).contains("\"permission\":\"SEARCH_AGGREGATES\"");
    }

    @Test
    void authorizationRejectsSourceOutsideRoleScope() throws Exception {
        int port = freePort();
        server = startServer(port, true, null, cfg -> {
            cfg.getSecurity().getAuthorization().setEnabled(true);
            cfg.getSecurity().getAuthorization().setPrincipalRoles(Map.of("admin", List.of("stats-reader")));

            var role = new EventLensConfig.RoleConfig();
            role.setId("stats-reader");
            role.setPermissions(List.of("VIEW_STATISTICS"));
            role.setAllowedSources(List.of("restricted"));
            cfg.getSecurity().getAuthorization().setRoles(List.of(role));
        });

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/statistics?source=default".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"reason\":\"DENY_SOURCE_SCOPE\"");
        assertThat(response.body()).contains("\"source\":\"default\"");
    }

    @Test
    void timelineMasksSensitivePayloadsByDefault() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/ORD-1001/timeline".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("***@***.***");
        assertThat(response.body()).doesNotContain("alice@example.com");
    }

    @Test
    void exportMasksSensitivePayloadsByDefault() throws Exception {
        int port = freePort();
        server = startServer(port, true);

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/ORD-1001/export?format=json".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("***@***.***");
        assertThat(response.body()).doesNotContain("alice@example.com");
    }

    @Test
    void revealRequiresPermissionAndReasonThenReturnsUnmaskedPayload() throws Exception {
        int port = freePort();
        server = startServer(port, true, null, cfg -> {
            cfg.getSecurity().getAuthorization().setEnabled(true);
            cfg.getSecurity().getAuthorization().setPrincipalRoles(Map.of("admin", List.of("pii-operator")));

            var role = new EventLensConfig.RoleConfig();
            role.setId("pii-operator");
            role.setPermissions(List.of("SEARCH_AGGREGATES", "VIEW_TIMELINE", "REVEAL_PII"));
            cfg.getSecurity().getAuthorization().setRoles(List.of(role));
        });

        var client = HttpClient.newHttpClient();

        var missingReasonRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/ORD-1001/events/1/reveal".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> badRequest = client.send(missingReasonRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(badRequest.statusCode()).isEqualTo(400);

        var allowedRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/ORD-1001/events/1/reveal".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"Investigating ticket SEC-123\"}"))
                .build();
        HttpResponse<String> allowed = client.send(allowedRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.body()).contains("alice@example.com");
    }

    @Test
    void revealRejectsUserWithoutRevealPermission() throws Exception {
        int port = freePort();
        server = startServer(port, true, null, cfg -> {
            cfg.getSecurity().getAuthorization().setEnabled(true);
            cfg.getSecurity().getAuthorization().setPrincipalRoles(Map.of("admin", List.of("reader")));

            var role = new EventLensConfig.RoleConfig();
            role.setId("reader");
            role.setPermissions(List.of("SEARCH_AGGREGATES", "VIEW_TIMELINE"));
            cfg.getSecurity().getAuthorization().setRoles(List.of(role));
        });

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/ORD-1001/events/1/reveal".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"Need raw payload\"}"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"reason\":\"DENY_MISSING_PERMISSION\"");
        assertThat(response.body()).contains("\"permission\":\"REVEAL_PII\"");
    }

    @Test
    void auditEndpointReturnsPersistedAuditEntries() throws Exception {
        int port = freePort();
        server = startServer(port, true, null, cfg -> {
            cfg.getSecurity().getAuthorization().setEnabled(true);
            cfg.getSecurity().getAuthorization().setPrincipalRoles(Map.of("admin", List.of("auditor")));

            var role = new EventLensConfig.RoleConfig();
            role.setId("auditor");
            role.setPermissions(List.of("SEARCH_AGGREGATES", "VIEW_AUDIT_LOG"));
            cfg.getSecurity().getAuthorization().setRoles(List.of(role));
        });

        var client = HttpClient.newHttpClient();
        var searchRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/aggregates/search?q=ord".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();
        HttpResponse<String> searchResponse = client.send(searchRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(searchResponse.statusCode()).isEqualTo(200);

        var auditRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/audit?limit=10&action=SEARCH".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();
        HttpResponse<String> auditResponse = client.send(auditRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(auditResponse.statusCode()).isEqualTo(200);
        var auditJson = new ObjectMapper().readTree(auditResponse.body());
        assertThat(auditJson.path("entries").isArray()).isTrue();
        assertThat(auditJson.path("entries"))
                .anySatisfy(node -> {
                    assertThat(node.path("action").asText()).isEqualTo("SEARCH");
                    assertThat(node.path("userId").asText()).isEqualTo("admin");
                });
    }

    @Test
    void auditEndpointRejectsUserWithoutAuditPermission() throws Exception {
        int port = freePort();
        server = startServer(port, true, null, cfg -> {
            cfg.getSecurity().getAuthorization().setEnabled(true);
            cfg.getSecurity().getAuthorization().setPrincipalRoles(Map.of("admin", List.of("reader")));

            var role = new EventLensConfig.RoleConfig();
            role.setId("reader");
            role.setPermissions(List.of("SEARCH_AGGREGATES"));
            cfg.getSecurity().getAuthorization().setRoles(List.of(role));
        });

        var client = HttpClient.newHttpClient();
        var request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/audit?limit=10".formatted(port)))
                .header("Authorization", basicAuth("admin", "correct horse battery"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"permission\":\"VIEW_AUDIT_LOG\"");
    }

    private EventLensServer startServer(int port, boolean authEnabled) {
        return startServer(port, authEnabled, null, cfg -> {});
    }

    private EventLensServer startOidcServer(int port, String issuer) {
        return startServer(port, false, issuer, cfg -> {});
    }

    private EventLensServer startServer(int port, boolean authEnabled, String oidcIssuer) {
        return startServer(port, authEnabled, oidcIssuer, cfg -> {});
    }

    private EventLensServer startServer(int port, boolean authEnabled, String oidcIssuer, Consumer<EventLensConfig> configCustomizer) {
        EventStoreReader reader = new EventStoreReader() {
            @Override
            public List<StoredEvent> getEvents(String aggregateId) {
                if (!"ORD-1001".equals(aggregateId)) {
                    return List.of();
                }
                return List.of(
                        new StoredEvent(
                                "evt-1", "ORD-1001", "Order", 1, "ORDER_CREATED",
                                "{\"email\":\"alice@example.com\",\"status\":\"created\"}",
                                "{\"source\":\"default\"}",
                                Instant.parse("2026-01-01T00:00:00Z"),
                                1L
                        ),
                        new StoredEvent(
                                "evt-2", "ORD-1001", "Order", 2, "ORDER_CONFIRMED",
                                "{\"status\":\"confirmed\"}",
                                "{\"source\":\"default\"}",
                                Instant.parse("2026-01-01T00:01:00Z"),
                                2L
                        )
                );
            }

            @Override
            public List<StoredEvent> getEventsUpTo(String aggregateId, long maxSequence) {
                return getEvents(aggregateId).stream()
                        .filter(event -> event.sequenceNumber() <= maxSequence)
                        .toList();
            }

            @Override
            public List<String> findAggregateIds(String aggregateType, int limit, int offset) {
                return List.of("ORD-1001");
            }

            @Override
            public List<StoredEvent> getRecentEvents(int limit) {
                return List.of(new StoredEvent(
                        "evt-1", "ORD-1001", "Order", 1, "ORDER_CREATED",
                        "{}", "{}", Instant.now(), 1L));
            }

            @Override
            public List<StoredEvent> getEventsAfter(long globalPosition, int limit) {
                return List.of();
            }

            @Override
            public long countEvents(String aggregateId) {
                return getEvents(aggregateId).size();
            }

            @Override
            public List<String> getAggregateTypes() {
                return List.of("Order");
            }

            @Override
            public List<String> searchAggregates(String query, int limit) {
                return List.of("ORD-1001");
            }
        };

        var cfg = new EventLensConfig();
        cfg.getServer().setPort(port);
        cfg.getServer().getAuth().setEnabled(authEnabled);
        cfg.getServer().getAuth().setUsername("admin");
        cfg.getServer().getAuth().setPassword("correct horse battery");
        cfg.getServer().getSecurity().getRateLimit().setEnabled(false);
        cfg.getDataProtection().getPii().setEnabled(true);
        cfg.getSecurity().getMetadata().setEnabled(true);
        cfg.getSecurity().getMetadata().setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("auth-" + port + ".db"));
        cfg.getSecurity().getAuth().getSession().setCookieName("eventlens_test_session");
        cfg.getSecurity().getAuth().getSession().setSecureCookie(false);
        if (oidcIssuer != null) {
            cfg.getSecurity().getAuth().setProvider("oidc");
            cfg.getSecurity().getAuth().getOidc().setIssuer(oidcIssuer);
            cfg.getSecurity().getAuth().getOidc().setClientId("eventlens-ui");
            cfg.getSecurity().getAuth().getOidc().setClientSecret("super-secret");
            cfg.getSecurity().getAuth().getOidc().setRedirectPath("/api/v1/auth/callback");
        }
        configCustomizer.accept(cfg);

        var replayEngine = new ReplayEngine(reader, new ReducerRegistry());
        var bisectEngine = new BisectEngine(replayEngine, reader);
        var anomalyDetector = new AnomalyDetector(reader, replayEngine, cfg.getAnomaly());
        var exportEngine = new ExportEngine(reader, replayEngine);
        var diffEngine = new DiffEngine(replayEngine);
        var metadataDatabase = MetadataDatabase.open(cfg.getSecurity().getMetadata());

        EventLensServer eventLensServer = new EventLensServer(
                cfg,
                reader,
                replayEngine,
                new ReducerRegistry(),
                new PluginManager(30),
                "default",
                bisectEngine,
                anomalyDetector,
                exportEngine,
                diffEngine,
                java.util.Map.of(),
                metadataDatabase);
        eventLensServer.start();
        return eventLensServer;
    }

    private static String basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String extractJsonField(String json, String field) {
        String needle = "\"" + field + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) {
            return "";
        }
        int valueStart = start + needle.length();
        int valueEnd = json.indexOf('"', valueStart);
        return valueEnd > valueStart ? json.substring(valueStart, valueEnd) : "";
    }

    private static final class FakeOidcProvider {

        private final int port;
        private final Javalin app;
        private final String issuer;
        private final RSAKey rsaJwk;
        private final RSASSASigner signer;
        private final Map<String, AuthorizationState> codes = new ConcurrentHashMap<>();
        private final ObjectMapper objectMapper = new ObjectMapper();

        private FakeOidcProvider(int port) throws Exception {
            this.port = port;
            this.issuer = "http://localhost:" + port;
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            this.rsaJwk = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate())
                    .keyID("test-key")
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            this.signer = new RSASSASigner(rsaJwk);

            this.app = Javalin.create(config -> {
                config.routes.get("/.well-known/openid-configuration", ctx -> ctx.json(Map.of(
                        "issuer", issuer,
                        "authorization_endpoint", issuer + "/authorize",
                        "token_endpoint", issuer + "/token",
                        "jwks_uri", issuer + "/jwks"
                )));
                config.routes.get("/jwks", ctx -> {
                    JWKSet jwkSet = new JWKSet(rsaJwk.toPublicJWK());
                    ctx.result(objectMapper.writeValueAsString(jwkSet.toJSONObject()));
                });
                config.routes.get("/authorize", ctx -> {
                    String state = ctx.queryParam("state");
                    String redirectUri = ctx.queryParam("redirect_uri");
                    String nonce = ctx.queryParam("nonce");
                    String codeChallenge = ctx.queryParam("code_challenge");
                    String code = "code-" + System.nanoTime();
                    codes.put(code, new AuthorizationState(nonce, codeChallenge));
                    ctx.redirect(redirectUri + "?code=" + code + "&state=" + state);
                });
                config.routes.post("/token", ctx -> {
                    String form = ctx.body();
                    Map<String, String> params = parseForm(form);
                    String code = params.get("code");
                    AuthorizationState state = codes.remove(code);
                    if (state == null) {
                        ctx.status(400).json(Map.of("error", "invalid_grant"));
                        return;
                    }
                    String codeVerifier = params.get("code_verifier");
                    if (!OidcLoginStateService.codeChallenge(codeVerifier).equals(state.codeChallenge())) {
                        ctx.status(400).json(Map.of("error", "invalid_grant"));
                        return;
                    }

                    String idToken = signedToken(state.nonce());
                    ctx.json(Map.of(
                            "access_token", "access-token",
                            "token_type", "Bearer",
                            "expires_in", 300,
                            "id_token", idToken
                    ));
                });
            });
        }

        private void start() {
            app.start(port);
        }

        private void stop() {
            app.stop();
        }

        private String signedToken(String nonce) throws JOSEException {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience("eventlens-ui")
                    .subject("oidc-user-1")
                    .claim("name", "OIDC Test User")
                    .claim("email", "oidc@example.test")
                    .claim("nonce", nonce)
                    .issueTime(java.util.Date.from(now))
                    .expirationTime(java.util.Date.from(now.plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaJwk.getKeyID()).build(),
                    claims
            );
            jwt.sign(signer);
            return jwt.serialize();
        }

        private static Map<String, String> parseForm(String body) {
            return java.util.Arrays.stream(body.split("&"))
                    .map(part -> part.split("=", 2))
                    .collect(java.util.stream.Collectors.toMap(
                            part -> java.net.URLDecoder.decode(part[0], StandardCharsets.UTF_8),
                            part -> part.length > 1 ? java.net.URLDecoder.decode(part[1], StandardCharsets.UTF_8) : "",
                            (left, right) -> right
                    ));
        }

        private record AuthorizationState(String nonce, String codeChallenge) {
        }
    }
}
