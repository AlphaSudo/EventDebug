package io.eventlens.api.security.oidc;

public record PendingOidcLogin(
        String stateId,
        String nonce,
        String codeVerifier,
        String returnHash
) {
}
