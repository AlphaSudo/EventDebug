package io.eventlens.core.security;

public enum AuthorizationDecisionReason {
    ALLOW,
    DENY_AUTH_REQUIRED,
    DENY_MISSING_PERMISSION,
    DENY_SOURCE_SCOPE,
    DENY_AGGREGATE_TYPE_SCOPE
}
