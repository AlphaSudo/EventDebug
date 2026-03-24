# Contributing to EventLens

Thank you for your interest in contributing! Here's how to get started.

## Prerequisites

- Java 21 (JDK with preview features)
- Node.js 20 LTS (for the React UI)
- Docker / Podman (for integration tests)

## Building

```bash
./gradlew build
```

This compiles all modules, runs the React Vite build, and produces the fat JAR at:
`eventlens-app/build/libs/eventlens.jar`

## Running Tests

```bash
# Unit tests + contract tests
./gradlew test

# Full verification gate
./gradlew check

# Integration and contract tests (requires Docker/Podman for Testcontainers)
./gradlew test --info
```

Built-in plugins should keep passing the shared contract harness in `eventlens-plugin-test`.

For a compact v3 release smoke pass, run:

```bash
pwsh ./scripts/v3-release-smoke.ps1
```

## Running Locally

```bash
# Start PostgreSQL + Kafka
docker compose up -d postgres kafka

# Run with the seed config
java --enable-preview -jar eventlens-app/build/libs/eventlens.jar serve
```

## Code Style

- Java 21 with preview features enabled
- All public classes and methods must have Javadoc
- Tests must use JUnit 5 + AssertJ
- No `System.out.println` in server code — use SLF4J logging

## Submitting a Pull Request

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit with descriptive messages
4. Open a PR against `main`

## Reporting Issues

Please open a GitHub Issue with:
- EventLens version
- Java version (`java -version`)
- Steps to reproduce
- Expected vs. actual behaviour

## Plugin Development

- Start with [docs/plugin-authoring.md](C:/Java%20Developer/EventDebug/docs/plugin-authoring.md).
- Reuse the shared contract harness from ventlens-plugin-test for new source or stream plugins.
- Register plugin entry points with META-INF/services/... so discovery works from classpath and /plugins.

