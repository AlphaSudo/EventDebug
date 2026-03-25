package io.eventlens.kafka;

import io.eventlens.spi.StreamAdapterPlugin;
import io.eventlens.test.StreamAdapterPluginTestKit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.Map;

@Testcontainers(disabledWithoutDocker = true)
class KafkaStreamAdapterPluginContractTest extends StreamAdapterPluginTestKit {

    @Container
    @SuppressWarnings("resource")
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    private static KafkaProducer<String, String> producer;
    private static final String TOPIC = "contract-events";

    @BeforeAll
    static void setUpProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producer = new KafkaProducer<>(props);
    }

    @AfterAll
    static void tearDownProducer() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
        }
    }

    @Override
    protected StreamAdapterPlugin createPlugin() {
        var plugin = new KafkaStreamAdapterPlugin();
        plugin.initialize("contract-kafka", Map.of(
                "bootstrapServers", kafka.getBootstrapServers(),
                "topic", TOPIC
        ));
        return plugin;
    }

    @Override
    protected void emitCanonicalEvents() throws Exception {
        String first = """
                {
                  "eventId":"evt-1",
                  "aggregateId":"ACC-001",
                  "aggregateType":"BankAccount",
                  "sequenceNumber":1,
                  "eventType":"AccountCreated",
                  "payload":{"balance":0},
                  "metadata":{"source":"contract"},
                  "timestamp":"%s"
                }
                """.formatted(Instant.now().toString());
        String second = """
                {
                  "eventId":"evt-2",
                  "aggregateId":"ACC-001",
                  "aggregateType":"BankAccount",
                  "sequenceNumber":2,
                  "eventType":"MoneyDeposited",
                  "payload":{"amount":100},
                  "metadata":{"source":"contract"},
                  "timestamp":"%s"
                }
                """.formatted(Instant.now().plusSeconds(1).toString());

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 10_000) {
            producer.send(new ProducerRecord<>(TOPIC, "ACC-001", first)).get();
            producer.send(new ProducerRecord<>(TOPIC, "ACC-001", second)).get();
            producer.flush();
            Thread.sleep(750);
        }
    }
}
