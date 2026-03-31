package io.eventlens.api.security;

import io.eventlens.core.security.Principal;

/**
 * Result of a single authentication attempt.
 */
public record AuthenticationResult(
        boolean success,
        Principal principal,
        String attemptedUserId,
        String failureReason,
        String challengeHeader
) {

    public static AuthenticationResult success(Principal principal) {
        return new AuthenticationResult(true, principal, principal.userId(), null, null);
    }

    public static AuthenticationResult failure(String attemptedUserId, String failureReason, String challengeHeader) {
        return new AuthenticationResult(false, Principal.anonymous(), attemptedUserId, failureReason, challengeHeader);
    }
}
