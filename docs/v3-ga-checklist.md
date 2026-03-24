# v3 GA Checklist

This checklist translates the v3 release criteria from [`versions/v3.md`](C:/Java%20Developer/EventDebug/versions/v3.md) into repo-local release evidence.

## MUST

- [x] `eventlens-spi` exists as a dedicated module with stable SPI types.
- [x] PostgreSQL plugin contract coverage added through [`PostgresEventSourcePluginContractTest.java`](C:/Java%20Developer/EventDebug/eventlens-source-postgres/src/test/java/io/eventlens/pg/PostgresEventSourcePluginContractTest.java).
- [x] MySQL plugin contract coverage added through [`MySqlEventSourcePluginContractTest.java`](C:/Java%20Developer/EventDebug/eventlens-source-mysql/src/test/java/io/eventlens/mysql/MySqlEventSourcePluginContractTest.java).
- [x] Kafka stream contract coverage added through [`KafkaStreamAdapterPluginContractTest.java`](C:/Java%20Developer/EventDebug/eventlens-stream-kafka/src/test/java/io/eventlens/kafka/KafkaStreamAdapterPluginContractTest.java).
- [x] v2 single-source config remains supported through migrator and validator tests.
- [x] v3 multi-source config is implemented and exercised through config tests and runtime wiring.
- [x] Existing API routes remain in place while source-aware v3 routes were added.
- [x] Optional `source` support is implemented for search and timeline flows.
- [x] UI datasource selector and plugin health page are implemented.
- [x] Plugin authoring guide is published in [`docs/plugin-authoring.md`](C:/Java%20Developer/EventDebug/docs/plugin-authoring.md).
- [~] SPI publishing is prepared via Maven publication config in [`eventlens-spi/build.gradle.kts`](C:/Java%20Developer/EventDebug/eventlens-spi/build.gradle.kts).

## SHOULD

- [ ] External plugin JAR loading should be verified with a dummy plugin artifact.
- [ ] Cache hit ratio should be measured under repeated-query load tests.
- [ ] Metadata-only mode should be benchmarked for response-size reduction.

## MUST NOT

- [x] No plugin sandboxing code was introduced.
- [x] No Vault or AWS Secrets Manager integration was introduced.
- [x] No gRPC dependency was added.
- [x] No MongoDB dependency was added.
- [x] No RabbitMQ, NATS, or Pulsar dependency was added.
- [x] No scripting runtime was added.
- [x] No metadata database or Flyway layer was added.
- [x] No event store write path was introduced.

## Verification Run

Recommended gate before a release candidate:

```bash
./gradlew.bat test
./gradlew.bat check
```

These commands validate the shared plugin contracts, built-in plugin modules, API tests, and UI build.
