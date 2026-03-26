package io.eventlens.core.security;

import java.util.Set;

/**
 * Request-scoped authenticated identity used by the API layer.
 *
 * <p>v5 starts with basic-auth and anonymous principals, but the shape is
 * intentionally ready for future OIDC/session/API-key expansion without
 * changing every call site again.
 */
public record Principal(
        String userId,
        String displayName,
        String authMethod,
        Set<String> roles,
        boolean authenticated
) {

    public Principal {
        userId = userId == null || userId.isBlank() ? "anonymous" : userId;
        displayName = displayName == null || displayName.isBlank() ? userId : displayName;
        authMethod = authMethod == null || authMethod.isBlank() ? "anonymous" : authMethod;
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static Principal anonymous() {
        return new Principal("anonymous", "anonymous", "anonymous", Set.of(), false);
    }

    public static Principal basic(String username) {
        return new Principal(username, username, "basic", Set.of(), true);
    }
}
