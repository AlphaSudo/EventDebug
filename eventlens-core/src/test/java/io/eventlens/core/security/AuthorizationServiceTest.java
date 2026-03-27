package io.eventlens.core.security;

import io.eventlens.core.EventLensConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationServiceTest {

    @Test
    void disabledAuthorizationAllowsRequests() {
        AuthorizationService service = new AuthorizationService(new EventLensConfig.AuthorizationConfig());

        AuthorizationDecision decision = service.authorize(
                Principal.anonymous(),
                Permission.VIEW_TIMELINE,
                "primary",
                "Order");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(AuthorizationDecisionReason.ALLOW);
    }

    @Test
    void enabledAuthorizationDeniesMissingPermission() {
        EventLensConfig.AuthorizationConfig config = new EventLensConfig.AuthorizationConfig();
        config.setEnabled(true);

        EventLensConfig.RoleConfig role = new EventLensConfig.RoleConfig();
        role.setId("viewer");
        role.setPermissions(List.of("view_timeline"));
        config.setRoles(List.of(role));
        config.setDefaultRoles(List.of("viewer"));

        AuthorizationService service = new AuthorizationService(config);
        AuthorizationDecision decision = service.authorize(
                new Principal("alice", "alice", "oidc", Set.of(), true),
                Permission.EXPORT_AGGREGATE,
                "primary",
                "Order");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AuthorizationDecisionReason.DENY_MISSING_PERMISSION);
    }

    @Test
    void enabledAuthorizationDeniesSourceOutsideScope() {
        EventLensConfig.AuthorizationConfig config = new EventLensConfig.AuthorizationConfig();
        config.setEnabled(true);

        EventLensConfig.RoleConfig role = new EventLensConfig.RoleConfig();
        role.setId("viewer");
        role.setPermissions(List.of("view_timeline"));
        role.setAllowedSources(List.of("primary"));
        config.setRoles(List.of(role));
        config.setPrincipalRoles(Map.of("alice", List.of("viewer")));

        AuthorizationService service = new AuthorizationService(config);
        AuthorizationDecision decision = service.authorize(
                new Principal("alice", "alice", "oidc", Set.of(), true),
                Permission.VIEW_TIMELINE,
                "secondary",
                "Order");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(AuthorizationDecisionReason.DENY_SOURCE_SCOPE);
    }

    @Test
    void enabledAuthorizationAllowsMatchingRoleAndScope() {
        EventLensConfig.AuthorizationConfig config = new EventLensConfig.AuthorizationConfig();
        config.setEnabled(true);

        EventLensConfig.RoleConfig role = new EventLensConfig.RoleConfig();
        role.setId("operator");
        role.setPermissions(List.of("view_timeline", "export_aggregate"));
        role.setAllowedSources(List.of("primary"));
        role.setAllowedAggregateTypes(List.of("Order"));
        config.setRoles(List.of(role));
        config.setPrincipalRoles(Map.of("alice", List.of("operator")));

        AuthorizationService service = new AuthorizationService(config);
        AuthorizationDecision decision = service.authorize(
                new Principal("alice", "alice", "oidc", Set.of(), true),
                Permission.EXPORT_AGGREGATE,
                "primary",
                "Order");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(AuthorizationDecisionReason.ALLOW);
        assertThat(decision.resolvedRoles()).contains("operator");
    }
}
