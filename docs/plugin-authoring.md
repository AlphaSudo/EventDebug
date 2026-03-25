# Plugin Authoring Guide

EventLens v3 exposes a small SPI so external plugins can add new event sources, stream adapters, and reducers without modifying the core app.

## Plugin Types

### Event source plugins
Use [`EventSourcePlugin`](C:/Java%20Developer/EventDebug/eventlens-spi/src/main/java/io/eventlens/spi/EventSourcePlugin.java) when your plugin reads events from a database or event store.

Responsibilities:
- Initialize once from config.
- Stay thread-safe after initialization.
- Never mutate the underlying store.
- Return health independently from other plugins.

### Stream adapter plugins
Use [`StreamAdapterPlugin`](C:/Java%20Developer/EventDebug/eventlens-spi/src/main/java/io/eventlens/spi/StreamAdapterPlugin.java) when your plugin subscribes to live events from Kafka or another broker.

Responsibilities:
- Start non-blocking background consumption in `subscribe(...)`.
- Stop cleanly in `unsubscribe()` and `close()`.
- Keep health checks lightweight.

### Reducer plugins
Use [`ReducerPlugin`](C:/Java%20Developer/EventDebug/eventlens-spi/src/main/java/io/eventlens/spi/ReducerPlugin.java) when you want custom replay/state reconstruction behavior for a domain aggregate type.

## Module Rules

- Depend on `eventlens-spi`, not runtime modules like `eventlens-core` or existing source plugins.
- Register plugins with `META-INF/services/<interface-name>` so `ServiceLoader` can discover them.
- Prefer parent-first dependency loading assumptions: shade only when truly necessary.
- Keep constructors no-arg so built-in discovery works.
- Treat `spiVersion()` compatibility as part of your release contract.

## Minimal Event Source Example

```java
package com.acme.eventlens;

import io.eventlens.spi.*;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.Map;

public final class AcmeEventSourcePlugin implements EventSourcePlugin {

    private AcmeReader reader;

    @Override
    public String typeId() {
        return "acme-db";
    }

    @Override
    public String displayName() {
        return "Acme Event Store";
    }

    @Override
    public void initialize(String instanceId, Map<String, Object> config) {
        reader = new AcmeReader(config);
    }

    @Override
    public EventQueryResult query(EventQuery query) {
        return reader.query(query);
    }

    @Override
    public HealthStatus healthCheck() {
        return reader != null ? HealthStatus.up() : HealthStatus.down("not initialized");
    }

    @Override
    public com.fasterxml.jackson.databind.JsonNode configSchema() {
        return NullNode.getInstance();
    }

    @Override
    public void close() {
        if (reader != null) {
            reader.close();
        }
    }
}
```

Service registration file:

```text
META-INF/services/io.eventlens.spi.EventSourcePlugin
```

Contents:

```text
com.acme.eventlens.AcmeEventSourcePlugin
```

## Config Shape

Datasource and stream instances are configured from `eventlens.yaml`.

Example datasource entry:

```yaml
datasources:
  - id: reporting-mysql
    type: mysql
    url: jdbc:mysql://localhost:3306/eventlens_reporting
    username: root
    password: secret
    table: event_store
```

EventLens converts each configured block into the `Map<String, Object>` passed to `initialize(...)`.
Built-in JDBC sources currently receive keys such as:
- `jdbcUrl`
- `username`
- `password`
- `tableName`
- `columnOverrides`
- `pool`
- `queryTimeoutSeconds`

If your plugin needs a schema, return it from `configSchema()` so future tooling and validation can surface it.

## Classloading and Dependency Guidance

- Compile against the SPI only.
- Avoid depending on app modules or implementation packages from built-in plugins.
- Keep transitive dependencies small and predictable.
- Prefer widely used drivers or clients that your plugin alone owns.
- Assume plugins may be loaded from `/plugins` with parent-first classloading.
- Do not rely on mutable global state or static caches shared across plugin instances.

## Versioning and Compatibility

Compatibility is checked through [`SpiVersions`](C:/Java%20Developer/EventDebug/eventlens-spi/src/main/java/io/eventlens/spi/SpiVersions.java).

Guidelines:
- Adding a `default` method is backward-compatible.
- Adding a required interface method is a breaking change.
- Changing a method signature is a breaking change.
- Keep `typeId()` stable once released.

## Using the Plugin Test Kit

The shared contract harness lives in [`eventlens-plugin-test`](C:/Java%20Developer/EventDebug/eventlens-plugin-test).

### Event source contract example

```java
@Testcontainers(disabledWithoutDocker = true)
class MyPluginContractTest extends EventSourcePluginTestKit {

    @Override
    protected EventSourcePlugin createPlugin() {
        var plugin = new AcmeEventSourcePlugin();
        plugin.initialize("contract", Map.of(
            "jdbcUrl", container.getJdbcUrl(),
            "username", container.getUsername(),
            "password", container.getPassword()
        ));
        return plugin;
    }

    @Override
    protected void seedCanonicalEvents() throws Exception {
        // Insert CanonicalEventSet.defaultEvents() into your backing store.
    }

    @Override
    protected void cleanupStore() throws Exception {
        // Truncate backing tables.
    }
}
```

The contract kit verifies:
- health checks
- timeline ordering
- cursor pagination
- metadata-only payload behavior
- search results
- empty-state handling

### Stream adapter contract example

```java
@Testcontainers(disabledWithoutDocker = true)
class MyStreamContractTest extends StreamAdapterPluginTestKit {

    @Override
    protected StreamAdapterPlugin createPlugin() {
        var plugin = new MyStreamPlugin();
        plugin.initialize("contract", Map.of("topic", "events"));
        return plugin;
    }

    @Override
    protected void emitCanonicalEvents() throws Exception {
        // Publish at least two events for aggregate ACC-001.
    }
}
```

## Packaging and Publishing

- Build your plugin as a normal JAR.
- Include the ServiceLoader registration file.
- Target Java 21 to match the current EventLens runtime.
- Publish your plugin artifact independently; EventLens loads it from the classpath or `/plugins` directory.
- The SPI module is configured for Maven publishing in [`eventlens-spi/build.gradle.kts`](C:/Java%20Developer/EventDebug/eventlens-spi/build.gradle.kts).

## Practical Checklist

- Implement the correct SPI interface.
- Keep initialization deterministic and fail fast on invalid config.
- Return `HealthStatus.down(...)` with an actionable message.
- Add a contract test using `eventlens-plugin-test`.
- Register the plugin in `META-INF/services`.
- Document required config keys and expected dependencies.
