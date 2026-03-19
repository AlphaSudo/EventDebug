### Multi-stage Dockerfile for EventLens

## 1) Build stage – compile and assemble the fat JAR
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:21-jdk AS build

# Install Node.js 20 LTS (needed by eventlens-ui for npm/Vite build)
RUN apt-get update && apt-get install -y curl && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && apt-get install -y nodejs && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Gradle wrapper and settings (better layer cache)
COPY settings.gradle.kts build.gradle.kts gradlew gradlew.bat ./
COPY gradle ./gradle

# Project sources
COPY eventlens-core ./eventlens-core
COPY eventlens-pg ./eventlens-pg
COPY eventlens-kafka ./eventlens-kafka
COPY eventlens-api ./eventlens-api
COPY eventlens-cli ./eventlens-cli
COPY eventlens-ui ./eventlens-ui
COPY eventlens-app ./eventlens-app
COPY eventlens.yaml ./eventlens.yaml

# Ensure Unix line endings on ALL text files, then build the shaded JAR (tests run outside Docker)
RUN find . -type f \( -name "*.kts" -o -name "*.properties" -o -name "*.java" -o -name "*.kt" -o -name "*.xml" -o -name "*.yaml" -o -name "*.yml" -o -name "gradlew" \) -exec sed -i 's/\r$//' {} + && chmod +x gradlew && ./gradlew :eventlens-app:shadowJar -x test --no-daemon

## 2) Runtime stage – slim multi-arch JRE image (7.1)
FROM docker.io/library/eclipse-temurin:21-jre-alpine AS base

ARG TARGETARCH

RUN addgroup -g 1000 eventlens && \
    adduser -u 1000 -G eventlens -s /bin/sh -D eventlens

WORKDIR /app

# Copy the fat JAR from the build stage
COPY --from=build --chown=eventlens:eventlens /workspace/eventlens-app/build/libs/eventlens.jar /app/eventlens.jar

# Default configuration path (can be overridden with volume mounts or env)
COPY --from=build --chown=eventlens:eventlens /workspace/eventlens.yaml /app/eventlens.yaml

# Health check dependency
RUN apk add --no-cache curl

USER eventlens

EXPOSE 9090

ENV EVENTLENS_CONFIG=/app/eventlens.yaml

HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=20s \
  CMD curl -sf http://localhost:9090/api/v1/health/live || exit 1

ENTRYPOINT ["java", \
  "--enable-preview", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/urandom", \
  "-jar", "/app/eventlens.jar"]

