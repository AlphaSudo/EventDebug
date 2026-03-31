package io.eventlens.api.security;

import io.javalin.http.Context;

/**
 * Authenticates an incoming request without coupling the caller to a specific
 * auth mechanism.
 */
public interface RequestAuthenticator {
    AuthenticationResult authenticate(Context ctx);
}
