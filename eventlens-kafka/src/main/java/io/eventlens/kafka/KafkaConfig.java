package io.eventlens.kafka;

/**
 * Kafka connection configuration.
 *
 * @param bootstrapServers Kafka bootstrap servers (e.g. "localhost:9092")
 * @param topic            topic containing domain events
 */
public record KafkaConfig(
        String bootstrapServers,
        String topic) {
}
