package io.eventlens.api.security;

import java.security.SecureRandom;
import java.util.Base64;

public final class CsrfTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private CsrfTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
