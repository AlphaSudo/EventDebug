package io.eventlens.kafka;

import io.eventlens.core.model.StoredEvent;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class KafkaLiveTailTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    private static KafkaProducer<String, String> producer;

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

    @Test
    void liveTailConsumesEventsFromKafkaTopic() throws Exception {
        String topic = "domain-events";
        KafkaConfig config = new KafkaConfig(kafka.getBootstrapServers(), topic);

        List<StoredEvent> received = new CopyOnWriteArrayList<>();
        try (KafkaLiveTail tail = new KafkaLiveTail(config)) {
            tail.addListener(received::add);
            tail.start();

            // The LiveTail consumer uses auto.offset.reset="latest". 
            // We must wait until it has fully joined the group before our produced messages
            // will be caught. To avoid fragile sleeps, produce repeatedly until we get them.
            long start = System.currentTimeMillis();
            while (received.size() < 2 && System.currentTimeMillis() - start < 30_000) {
                producer.send(new ProducerRecord<>(topic, "ACC-001",
                        "{\"eventType\":\"AccountCreated\",\"payload\":{\"balance\":0}}")).get();
                producer.send(new ProducerRecord<>(topic, "ACC-001",
                        "{\"eventType\":\"MoneyDeposited\",\"payload\":{\"amount\":50}}")).get();
                Thread.sleep(1000);
            }
        }

        assertThat(received).hasSizeGreaterThanOrEqualTo(2);
        assertThat(received.get(0).aggregateId()).isEqualTo("ACC-001");
    }
}


