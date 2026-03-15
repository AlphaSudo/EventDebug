package io.eventlens.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventlens.core.model.StoredEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps a Kafka {@link ConsumerRecord} to a {@link StoredEvent}.
 *
 * <p>
 * Supports two payload formats:
 * <ul>
 * <li><b>Full format</b>: JSON containing all StoredEvent fields
 * (eventId, aggregateId, eventType, payload, etc.)</li>
 * <li><b>Minimal format</b>: any JSON — treated as the payload, with
 * aggregateId taken from the record key and eventType from a
 * "type" or "eventType" field in the value.</li>
 * </ul>
 */
public class KafkaEventMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public static StoredEvent fromRecord(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> value = MAPPER.readValue(
                    record.value(), new TypeReference<>() {
                    });

            // Full format: all fields present
            if (value.containsKey("eventId") && value.containsKey("aggregateId")) {
                return new StoredEvent(
                        Objects.toString(value.get("eventId"), ""),
                        (String) value.get("aggregateId"),
                        (String) value.getOrDefault("aggregateType", "unknown"),
                        toLong(value.getOrDefault("sequenceNumber", 0L)),
                        (String) value.getOrDefault("eventType", "UnknownEvent"),
                        MAPPER.writeValueAsString(value.getOrDefault("payload", "{}")),
                        MAPPER.writeValueAsString(value.getOrDefault("metadata", "{}")),
                        Instant.parse((String) value.getOrDefault("timestamp", Instant.now().toString())),
                        record.offset());
            }

            // Minimal format: extract what we can
            String eventType = (String) value.getOrDefault("eventType",
                    value.getOrDefault("type", "UnknownEvent"));
            String aggregateId = record.key() != null ? record.key() : "unknown";

            return new StoredEvent(
                    UUID.randomUUID().toString(),
                    aggregateId,
                    "unknown",
                    record.offset(),
                    eventType,
                    record.value(), // raw JSON as payload
                    "{}",
                    Instant.ofEpochMilli(record.timestamp()),
                    record.offset());
        } catch (Exception e) {
            throw new RuntimeException("Cannot parse Kafka record at offset " + record.offset(), e);
        }
    }

    private static long toLong(Object val) {
        if (val instanceof Number n)
            return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (Exception e) {
            return 0L;
        }
    }
}
