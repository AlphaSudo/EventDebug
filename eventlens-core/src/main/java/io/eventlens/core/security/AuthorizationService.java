package io.eventlens.core.security;

import io.eventlens.core.EventLensConfig;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AuthorizationService {

    private final EventLensConfig.AuthorizationConfig config;
    private final Map<String, EventLensConfig.RoleConfig> rolesById;

    public AuthorizationService(EventLensConfig.AuthorizationConfig config) {
        this.config = config == null ? new EventLensConfig.AuthorizationConfig() : config;
        this.rolesById = new LinkedHashMap<>();
        for (EventLensConfig.RoleConfig role : this.config.getRoles()) {
            if (role.getId() != null && !role.getId().isBlank()) {
                rolesById.put(role.getId(), role);
            }
        }
    }

    public AuthorizationDecision authorize(
            Principal principal,
            Permission permission,
            String sourceId,
            String aggregateType) {
        if (!config.isEnabled()) {
            return AuthorizationDecision.allow(permission, Set.of());
        }
        if (principal == null || !principal.authenticated()) {
            return AuthorizationDecision.deny(AuthorizationDecisionReason.DENY_AUTH_REQUIRED, permission, Set.of());
        }

        Set<String> resolvedRoleIds = resolveRoleIds(principal);
        if (resolvedRoleIds.isEmpty()) {
            return AuthorizationDecision.deny(AuthorizationDecisionReason.DENY_MISSING_PERMISSION, permission, Set.of());
        }

        boolean hasPermission = false;
        boolean deniedBySource = false;
        boolean deniedByAggregateType = false;

        for (String roleId : resolvedRoleIds) {
            EventLensConfig.RoleConfig role = rolesById.get(roleId);
            if (role == null) {
                continue;
            }
            if (!hasPermission(role, permission)) {
                continue;
            }

            hasPermission = true;
            if (!matchesScope(role.getAllowedSources(), sourceId)) {
                deniedBySource = true;
                continue;
            }
            if (!matchesScope(role.getAllowedAggregateTypes(), aggregateType)) {
                deniedByAggregateType = true;
                continue;
            }
            return AuthorizationDecision.allow(permission, resolvedRoleIds);
        }

        if (!hasPermission) {
            return AuthorizationDecision.deny(AuthorizationDecisionReason.DENY_MISSING_PERMISSION, permission, resolvedRoleIds);
        }
        if (deniedBySource) {
            return AuthorizationDecision.deny(AuthorizationDecisionReason.DENY_SOURCE_SCOPE, permission, resolvedRoleIds);
        }
        if (deniedByAggregateType) {
            return AuthorizationDecision.deny(AuthorizationDecisionReason.DENY_AGGREGATE_TYPE_SCOPE, permission, resolvedRoleIds);
        }
        return AuthorizationDecision.deny(AuthorizationDecisionReason.DENY_MISSING_PERMISSION, permission, resolvedRoleIds);
    }

    private Set<String> resolveRoleIds(Principal principal) {
        Set<String> roles = new LinkedHashSet<>(principal.roles());
        roles.addAll(config.getDefaultRoles());
        List<String> principalRoles = config.getPrincipalRoles().get(principal.userId());
        if (principalRoles != null) {
            roles.addAll(principalRoles);
        }
        roles.removeIf(roleId -> !rolesById.containsKey(roleId));
        return Set.copyOf(roles);
    }

    private static boolean hasPermission(EventLensConfig.RoleConfig role, Permission permission) {
        return role.getPermissions().stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'))
                .anyMatch(permission.name()::equals);
    }

    private static boolean matchesScope(List<String> allowedValues, String value) {
        if (allowedValues == null || allowedValues.isEmpty() || value == null || value.isBlank()) {
            return true;
        }
        return allowedValues.stream().anyMatch(candidate -> candidate.equalsIgnoreCase(value));
    }
}
