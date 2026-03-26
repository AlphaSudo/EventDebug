package io.eventlens.api.security;

import io.eventlens.core.security.Principal;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Basic-auth implementation for the v5 auth seam.
 */
public final class BasicAuthenticator implements RequestAuthenticator {

    private final String expectedUsername;
    private final String expectedPassword;
    private final String challengeHeader;

    public BasicAuthenticator(String expectedUsername, String expectedPassword, String realm) {
        this.expectedUsername = expectedUsername == null ? "" : expectedUsername;
        this.expectedPassword = expectedPassword == null ? "" : expectedPassword;
        this.challengeHeader = "Basic realm=\"" + (realm == null || realm.isBlank() ? "EventLens" : realm) + "\"";
    }

    @Override
    public AuthenticationResult authenticate(Context ctx) {
        var credentials = ctx.basicAuthCredentials();
        if (credentials == null) {
            return AuthenticationResult.failure(null, "missing_credentials", challengeHeader);
        }

        String suppliedUser = credentials.getUsername();
        String suppliedPassword = credentials.getPassword();

        boolean userMatches = constantTimeEquals(expectedUsername, suppliedUser);
        boolean passwordMatches = constantTimeEquals(expectedPassword, suppliedPassword);
        if (userMatches && passwordMatches) {
            return AuthenticationResult.success(Principal.basic(suppliedUser));
        }

        return AuthenticationResult.failure(suppliedUser, "invalid_credentials", challengeHeader);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] left = (expected == null ? "" : expected).getBytes(StandardCharsets.UTF_8);
        byte[] right = (actual == null ? "" : actual).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
