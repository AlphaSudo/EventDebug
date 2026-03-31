package io.eventlens.api.security.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventlens.core.EventLensConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class OidcProviderClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OidcProviderClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper());
    }

    OidcProviderClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public OidcProviderMetadata discover(String issuer) {
        String normalizedIssuer = trimTrailingSlash(issuer);
        String wellKnown = normalizedIssuer + "/.well-known/openid-configuration";
        JsonNode json = getJson(wellKnown);
        return new OidcProviderMetadata(
                readRequiredText(json, "issuer"),
                readRequiredText(json, "authorization_endpoint"),
                readRequiredText(json, "token_endpoint"),
                readRequiredText(json, "jwks_uri"));
    }

    public OidcTokenResponse exchangeCode(
            OidcProviderMetadata metadata,
            EventLensConfig.OidcConfig config,
            String redirectUri,
            String code,
            String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", codeVerifier);
        String body = toFormBody(form);

        String credentials = config.getClientId() + ":" + config.getClientSecret();
        String basicAuth = java.util.Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(metadata.tokenEndpoint()))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        JsonNode json = sendJson(request, "OIDC token exchange");
        return new OidcTokenResponse(
                readOptionalText(json, "access_token"),
                readOptionalText(json, "token_type"),
                json.path("expires_in").asLong(0),
                readRequiredText(json, "id_token"));
    }

    public JsonNode fetchJwks(String jwksUri) {
        return getJson(jwksUri);
    }

    private JsonNode getJson(String uri) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return sendJson(request, "OIDC metadata fetch");
    }

    private JsonNode sendJson(HttpRequest request, String operation) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(operation + " failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " failed", e);
        }
    }

    private static String readRequiredText(JsonNode node, String field) {
        String value = readOptionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("OIDC metadata field is missing: " + field);
        }
        return value;
    }

    private static String readOptionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String toFormBody(Map<String, String> form) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            body.append('=');
            body.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
