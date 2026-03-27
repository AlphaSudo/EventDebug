package io.eventlens.api.security.oidc;

public record ValidatedOidcIdentity(
        String subject,
        String displayName,
        String email
) {
}
