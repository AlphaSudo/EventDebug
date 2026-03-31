package io.eventlens.api.security.oidc;

public record OidcTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String idToken
) {
}
