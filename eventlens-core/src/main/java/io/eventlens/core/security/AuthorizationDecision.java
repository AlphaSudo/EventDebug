package io.eventlens.core.security;

import java.util.Set;

public record AuthorizationDecision(
        boolean allowed,
        AuthorizationDecisionReason reason,
        Permission permission,
        Set<String> resolvedRoles
) {
    public static AuthorizationDecision allow(Permission permission, Set<String> resolvedRoles) {
        return new AuthorizationDecision(true, AuthorizationDecisionReason.ALLOW, permission, resolvedRoles);
    }

    public static AuthorizationDecision deny(
            AuthorizationDecisionReason reason,
            Permission permission,
            Set<String> resolvedRoles) {
        return new AuthorizationDecision(false, reason, permission, resolvedRoles);
    }
}
