# Security Policy

## Supported Versions

Currently, only the latest major version of EventLens receives security updates.

| Version | Supported          |
| ------- | ------------------ |
| v1.x.x  | :white_check_mark: |
| < v1.0  | :x:                |

## Reporting a Vulnerability

We take the security of EventLens seriously. If you discover a security vulnerability, please report it privately.

**Do not report security vulnerabilities through public GitHub issues.**

Instead, please send an email to the maintainers or use GitHub Security Advisories to privately report the issue. You can expect an initial response within 48 hours.

## Best Practices for Using EventLens Safely

EventLens is designed to be a safe, read-only window into your event store, but you should still follow these best practices when deploying it:

1. **Read-Only Database Access**: Ensure EventLens connects to your database using a read-only role, as it never mutates data.
2. **Production Mode**: For shared environments, enable `security.production-mode: true`. This turns unsafe startup combinations into hard configuration failures instead of soft warnings.
3. **Authentication & HTTPS**: In shared environments, enable either legacy basic auth, OIDC browser sessions, or API keys, and place EventLens behind TLS (HTTPS). Production mode rejects completely unauthenticated deployments.
4. **CORS Restrictions**: In production mode, `server.allowed-origins` must be an explicit allowlist. Wildcard origins are rejected.
5. **Audit & Metadata**: Keep `audit.enabled: true` when using OIDC, RBAC, API keys, or PII reveal. Use file-backed SQLite metadata instead of in-memory metadata for persisted sessions, audit, and API keys.
6. **Regular Updates**: Keep your EventLens deployment updated to the latest version to receive security patches and updates to dependencies.

## Information about Dependencies

EventLens strives to keep all dependencies and packages up to date. We rely on standard open-source tooling like Dependabot to alert us to potential problems in our upstream dependencies.

If you are using a Docker image for EventLens, we periodically rebuild images to ensure the base images contain the latest security updates. Please make sure to pull the latest `alphasudo2/eventlens-app:latest` or specific version tag to stay protected.

Release builds now generate:
- an OWASP dependency check report
- a CycloneDX SBOM (`./gradlew sbom`)

These artifacts should be retained with the release so operators can review the exact dependency surface they are deploying.
