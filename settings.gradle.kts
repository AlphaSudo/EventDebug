rootProject.name = "eventlens"

include(
    "eventlens-spi",
    "eventlens-core",
    "eventlens-source-postgres",
    "eventlens-source-mysql",
    "eventlens-stream-kafka",
    "eventlens-api",
    "eventlens-cli",
    "eventlens-ui",
    "eventlens-app"
)
