rootProject.name = "eventlens"

include(
    "eventlens-core",      // Domain model, engines, SPI
    "eventlens-pg",        // PostgreSQL reader
    "eventlens-kafka",     // Kafka consumer (optional)
    "eventlens-api",       // REST + WebSocket
    "eventlens-cli",       // CLI commands
    "eventlens-ui",        // React frontend (builds into resources)
    "eventlens-app"        // Fat JAR assembly
)
