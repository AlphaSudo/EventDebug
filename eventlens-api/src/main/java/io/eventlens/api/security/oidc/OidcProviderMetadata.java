package io.eventlens.api.security.oidc;

public record OidcProviderMetadata(
        String issuer,
        String authorizationEndpoint,
        String tokenEndpoint,
        String jwksUri
) {
}
