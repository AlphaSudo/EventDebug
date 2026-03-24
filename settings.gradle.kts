rootProject.name = "eventlens"

include(
    "eventlens-spi",       // Plugin interfaces and shared types
    "eventlens-core",      // Domain model and engines
    "eventlens-source-postgres", // PostgreSQL source plugin
    "eventlens-stream-kafka",    // Kafka stream plugin
    "eventlens-api",       // REST + WebSocket
    "eventlens-cli",       // CLI commands
    "eventlens-ui",        // React frontend (builds into resources)
    "eventlens-app"        // Fat JAR assembly
)
