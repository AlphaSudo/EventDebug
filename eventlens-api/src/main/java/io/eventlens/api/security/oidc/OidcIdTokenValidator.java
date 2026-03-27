package io.eventlens.api.security.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.eventlens.core.EventLensConfig;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class OidcIdTokenValidator {

    private final OidcProviderClient providerClient;
    private final Clock clock;

    public OidcIdTokenValidator(OidcProviderClient providerClient) {
        this(providerClient, Clock.systemUTC());
    }

    OidcIdTokenValidator(OidcProviderClient providerClient, Clock clock) {
        this.providerClient = providerClient;
        this.clock = clock;
    }

    public ValidatedOidcIdentity validate(
            String idToken,
            OidcProviderMetadata metadata,
            EventLensConfig.OidcConfig config,
            String expectedNonce) {
        try {
            SignedJWT jwt = SignedJWT.parse(idToken);
            JWKSet jwkSet = JWKSet.parse(providerClient.fetchJwks(metadata.jwksUri()).toString());
            verifySignature(jwt, jwkSet);

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            validateClaims(claims, metadata, config, expectedNonce);

            String subject = required(claims.getSubject(), "sub");
            String displayName = firstNonBlank(
                    claims.getStringClaim("name"),
                    claims.getStringClaim("preferred_username"),
                    claims.getStringClaim("email"),
                    subject
            );
            String email = claims.getStringClaim("email");
            return new ValidatedOidcIdentity(subject, displayName, email);
        } catch (ParseException e) {
            throw new IllegalStateException("Invalid OIDC ID token", e);
        }
    }

    private void validateClaims(
            JWTClaimsSet claims,
            OidcProviderMetadata metadata,
            EventLensConfig.OidcConfig config,
            String expectedNonce) throws ParseException {
        String issuer = required(claims.getIssuer(), "iss");
        if (!Objects.equals(metadata.issuer(), issuer) && !Objects.equals(config.getIssuer(), issuer)) {
            throw new IllegalStateException("OIDC issuer mismatch");
        }

        List<String> audience = claims.getAudience();
        if (audience == null || !audience.contains(config.getClientId())) {
            throw new IllegalStateException("OIDC audience mismatch");
        }

        Instant now = clock.instant();
        if (claims.getExpirationTime() == null || !claims.getExpirationTime().toInstant().isAfter(now.minusSeconds(30))) {
            throw new IllegalStateException("OIDC ID token is expired");
        }
        if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(30))) {
            throw new IllegalStateException("OIDC ID token is not yet valid");
        }

        String nonce = claims.getStringClaim("nonce");
        if (!Objects.equals(expectedNonce, nonce)) {
            throw new IllegalStateException("OIDC nonce mismatch");
        }

        if (audience.size() > 1) {
            String authorizedParty = claims.getStringClaim("azp");
            if (!Objects.equals(config.getClientId(), authorizedParty)) {
                throw new IllegalStateException("OIDC azp mismatch");
            }
        }
    }

    private static void verifySignature(SignedJWT jwt, JWKSet jwkSet) {
        try {
            JWK key = selectKey(jwt, jwkSet);
            if (key == null) {
                throw new IllegalStateException("No matching OIDC signing key found");
            }
            JWSVerifier verifier = verifierFor(key);
            if (!jwt.verify(verifier)) {
                throw new IllegalStateException("OIDC ID token signature validation failed");
            }
        } catch (JOSEException e) {
            throw new IllegalStateException("OIDC signature validation failed", e);
        }
    }

    private static JWK selectKey(SignedJWT jwt, JWKSet jwkSet) {
        String keyId = jwt.getHeader().getKeyID();
        if (keyId != null) {
            return jwkSet.getKeys().stream()
                    .filter(key -> keyId.equals(key.getKeyID()))
                    .findFirst()
                    .orElse(null);
        }
        return jwkSet.getKeys().stream().findFirst().orElse(null);
    }

    private static JWSVerifier verifierFor(JWK key) throws JOSEException {
        if (key instanceof RSAKey rsaKey) {
            return new RSASSAVerifier(rsaKey.toRSAPublicKey());
        }
        if (key instanceof ECKey ecKey) {
            return new ECDSAVerifier(ecKey.toECPublicKey());
        }
        if (key instanceof OctetSequenceKey octKey) {
            return new MACVerifier(octKey.toByteArray());
        }
        throw new IllegalStateException("Unsupported OIDC signing key type: " + key.getKeyType());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("OIDC claim is missing: " + field);
        }
        return value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
