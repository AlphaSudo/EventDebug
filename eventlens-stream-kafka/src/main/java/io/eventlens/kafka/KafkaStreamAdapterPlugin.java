package io.eventlens.kafka;

import io.eventlens.spi.Event;
import io.eventlens.spi.HealthStatus;
import io.eventlens.spi.StreamAdapterPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class KafkaStreamAdapterPlugin implements StreamAdapterPlugin {

    private volatile KafkaLiveTail liveTail;
    private volatile KafkaConfig config;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);

    @Override
    public String typeId() {
        return "kafka";
    }

    @Override
    public String displayName() {
        return "Kafka Stream Adapter";
    }

    @Override
    public void initialize(String instanceId, Map<String, Object> config) {
        this.config = new KafkaConfig(requireString(config, "bootstrapServers"), requireString(config, "topic"));
        this.liveTail = new KafkaLiveTail(this.config);
    }

    @Override
    public void subscribe(Consumer<Event> listener) {
        KafkaLiveTail activeTail = requireLiveTail();
        activeTail.addListener(event -> listener.accept(KafkaEventMapper.toSpiEvent(event)));
        if (subscribed.compareAndSet(false, true)) {
            activeTail.start();
        }
    }

    @Override
    public void unsubscribe() {
        subscribed.set(false);
        KafkaLiveTail activeTail = liveTail;
        if (activeTail != null) {
            activeTail.close();
            liveTail = config != null ? new KafkaLiveTail(config) : null;
        }
    }

    @Override
    public HealthStatus healthCheck() {
        return config == null ? HealthStatus.down("Kafka plugin not initialized") : HealthStatus.up();
    }

    @Override
    public void close() {
        KafkaLiveTail activeTail = liveTail;
        liveTail = null;
        if (activeTail != null) {
            activeTail.close();
        }
    }

    private KafkaLiveTail requireLiveTail() {
        KafkaLiveTail activeTail = liveTail;
        if (activeTail == null) {
            throw new IllegalStateException("Kafka plugin is not initialized");
        }
        return activeTail;
    }

    private static String requireString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null || Objects.toString(value, "").isBlank()) {
            throw new IllegalArgumentException("Missing required kafka config: " + key);
        }
        return value.toString();
    }
}
