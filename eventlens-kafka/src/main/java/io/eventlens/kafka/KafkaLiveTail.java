package io.eventlens.kafka;

import io.eventlens.core.model.StoredEvent;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Consumes events from a Kafka topic on a virtual thread,
 * notifying registered listeners for each event received.
 *
 * <p>
 * Uses a random group ID so it never interferes with your application's
 * consumers.
 * Fails gracefully — if Kafka is unreachable, a warning is logged and the live
 * tail
 * falls back to PostgreSQL polling.
 */
public class KafkaLiveTail implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaLiveTail.class);

    private final KafkaConsumer<String, String> consumer;
    private final String topic;
    private final List<Consumer<StoredEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running = false;
    private Thread pollingThread;

    public KafkaLiveTail(KafkaConfig config) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "eventlens-livetail-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");

        this.consumer = new KafkaConsumer<>(props);
        this.topic = config.topic();
        log.info("KafkaLiveTail initialized for topic '{}' on {}", topic, config.bootstrapServers());
    }

    public void addListener(Consumer<StoredEvent> listener) {
        listeners.add(listener);
    }

    public void start() {
        running = true;
        consumer.subscribe(List.of(topic));
        pollingThread = Thread.ofVirtual().name("eventlens-kafka-tail").start(this::pollLoop);
        log.info("Kafka live tail started on topic '{}'", topic);
    }

    @Override
    public void close() {
        running = false;
        consumer.wakeup();
        log.info("Kafka live tail stopped");
    }

    private void pollLoop() {
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        StoredEvent event = KafkaEventMapper.fromRecord(record);
                        listeners.forEach(l -> l.accept(event));
                    } catch (Exception e) {
                        log.warn("Skipping malformed Kafka event at offset {}: {}",
                                record.offset(), e.getMessage());
                    }
                }
            }
        } catch (org.apache.kafka.common.errors.WakeupException e) {
            // Expected on close()
        } catch (Exception e) {
            log.error("Kafka poll loop failed", e);
        } finally {
            consumer.close();
        }
    }
}
