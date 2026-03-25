package io.eventlens.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventlens.core.model.StoredEvent;
import io.eventlens.spi.Event;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class KafkaEventMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    public static StoredEvent fromRecord(ConsumerRecord<String, String> record) {
        try {
            Map<String, Object> value = MAPPER.readValue(record.value(), new TypeReference<>() {});

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

            String eventType = (String) value.getOrDefault("eventType", value.getOrDefault("type", "UnknownEvent"));
            String aggregateId = record.key() != null ? record.key() : "unknown";

            return new StoredEvent(
                    UUID.randomUUID().toString(),
                    aggregateId,
                    "unknown",
                    record.offset(),
                    eventType,
                    record.value(),
                    "{}",
                    Instant.ofEpochMilli(record.timestamp()),
                    record.offset());
        } catch (Exception e) {
            throw new RuntimeException("Cannot parse Kafka record at offset " + record.offset(), e);
        }
    }

    public static Event toSpiEvent(StoredEvent event) {
        try {
            return new Event(
                    event.eventId(),
                    event.aggregateId(),
                    event.aggregateType(),
                    event.sequenceNumber(),
                    event.eventType(),
                    MAPPER.readTree(event.payload()),
                    MAPPER.readTree(event.metadata()),
                    event.timestamp(),
                    event.globalPosition());
        } catch (Exception e) {
            throw new RuntimeException("Cannot convert Kafka event " + event.eventId(), e);
        }
    }

    private static long toLong(Object val) {
        if (val instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(val.toString());
        } catch (Exception e) {
            return 0L;
        }
    }
}
