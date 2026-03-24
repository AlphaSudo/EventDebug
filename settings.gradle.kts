rootProject.name = "eventlens"

include(
    "eventlens-spi",       // Plugin interfaces and shared types
    "eventlens-core",      // Domain model and engines
    "eventlens-pg",        // PostgreSQL reader
    "eventlens-kafka",     // Kafka consumer (optional)
    "eventlens-api",       // REST + WebSocket
    "eventlens-cli",       // CLI commands
    "eventlens-ui",        // React frontend (builds into resources)
    "eventlens-app"        // Fat JAR assembly
)
