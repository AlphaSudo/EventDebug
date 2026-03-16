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
2. **Authentication & HTTPS**: When exposing EventLens in a shared environment, enable basic authentication (`server.auth.enabled: true` in your configuration) and place EventLens behind a reverse proxy with TLS (HTTPS) enabled.
3. **CORS Restrictions**: For production deployments, change `server.allowed-origins` in your configuration to specifically whitelist the domains that are permitted to reach your dashboard.
4. **Regular Updates**: Keep your EventLens deployment updated to the latest version to receive security patches and updates to dependencies.

## Information about Dependencies

EventLens strives to keep all dependencies and packages up to date. We rely on standard open-source tooling like Dependabot to alert us to potential problems in our upstream dependencies.

If you are using a Docker image for EventLens, we periodically rebuild images to ensure the base images contain the latest security updates. Please make sure to pull the latest `alphasudo2/eventlens-app:latest` or specific version tag to stay protected.
