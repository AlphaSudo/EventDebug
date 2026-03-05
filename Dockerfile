### Multi-stage Dockerfile for EventLens

## 1) Build stage â€“ compile and assemble the fat JAR
FROM docker.io/library/eclipse-temurin:21-jdk AS build

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

# Ensure Unix line endings on ALL text files, then build the shaded JAR
RUN find . -type f \( -name "*.kts" -o -name "*.properties" -o -name "*.java" -o -name "*.kt" -o -name "*.xml" -o -name "*.yaml" -o -name "*.yml" -o -name "gradlew" \) -exec sed -i 's/\r$//' {} + && chmod +x gradlew && ./gradlew :eventlens-app:shadowJar --no-daemon

## 2) Runtime stage â€“ slim JRE image
FROM docker.io/library/eclipse-temurin:21-jre

WORKDIR /app

# Copy the fat JAR from the build stage
COPY --from=build /workspace/eventlens-app/build/libs/eventlens.jar /app/eventlens.jar

# Default configuration path (can be overridden with volume mounts or env)
COPY --from=build /workspace/eventlens.yaml /app/eventlens.yaml

EXPOSE 9090

ENV EVENTLENS_CONFIG=/app/eventlens.yaml

# Use preview features because the project compiles/runs with --enable-preview
ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/eventlens.jar"]

