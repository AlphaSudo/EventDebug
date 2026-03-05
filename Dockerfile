### Multi-stage Dockerfile for EventLens

## 1) Build stage – compile and assemble the fat JAR
FROM eclipse-temurin:21-jdk AS build

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

# Build the shaded application JAR
RUN ./gradlew :eventlens-app:shadowJar --no-daemon

## 2) Runtime stage – slim JRE image
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the fat JAR from the build stage
COPY --from=build /workspace/eventlens-app/build/libs/eventlens.jar /app/eventlens.jar

# Default configuration path (can be overridden with volume mounts or env)
COPY --from=build /workspace/eventlens.yaml /app/eventlens.yaml

EXPOSE 9090

ENV EVENTLENS_CONFIG=/app/eventlens.yaml

# Use preview features because the project compiles/runs with --enable-preview
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/eventlens.jar"]

