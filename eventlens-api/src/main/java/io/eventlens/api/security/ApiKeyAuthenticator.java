package io.eventlens.api.security;

import io.eventlens.core.metadata.ApiKeyRecord;
import io.eventlens.core.security.ApiKeyService;
import io.eventlens.core.security.Principal;
import io.javalin.http.Context;

import java.util.Optional;
import java.util.Set;

public final class ApiKeyAuthenticator implements RequestAuthenticator {

    private final ApiKeyService apiKeyService;
    private final String headerName;

    public ApiKeyAuthenticator(ApiKeyService apiKeyService, String headerName) {
        this.apiKeyService = apiKeyService;
        this.headerName = headerName;
    }

    @Override
    public AuthenticationResult authenticate(Context ctx) {
        String rawApiKey = ctx.header(headerName);
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return AuthenticationResult.failure(null, "missing_api_key", null);
        }

        Optional<ApiKeyRecord> record = apiKeyService.authenticate(rawApiKey);
        if (record.isEmpty()) {
            return AuthenticationResult.failure(null, "invalid_api_key", null);
        }

        ApiKeyRecord apiKey = record.get();
        Principal principal = new Principal(
                apiKey.principalUserId(),
                apiKey.principalUserId(),
                "api-key",
                Set.copyOf(apiKey.scopes()),
                true
        );
        return AuthenticationResult.success(principal);
    }
}
