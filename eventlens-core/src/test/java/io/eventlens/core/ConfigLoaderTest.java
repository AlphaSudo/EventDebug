package io.eventlens.core;

import io.eventlens.core.exception.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @Test
    void loadsLegacyConfigIntoNormalizedLists() throws Exception {
        Path file = Files.createTempFile("eventlens-legacy", ".yaml");
        Files.writeString(file, """
                datasource:
                  url: jdbc:postgresql://localhost:5432/eventlens
                  username: postgres
                  password: secret
                kafka:
                  bootstrap-servers: localhost:9092
                  topic: domain-events
                """);

        EventLensConfig config = ConfigLoader.load(file.toString());

        assertThat(config.getDatasourcesOrLegacy()).hasSize(1);
        assertThat(config.getDatasourcesOrLegacy().get(0).getType()).isEqualTo("postgres");
        assertThat(config.getStreamsOrLegacy()).hasSize(1);
        assertThat(config.getStreamsOrLegacy().get(0).getType()).isEqualTo("kafka");
    }

    @Test
    void loadsPluralConfigWithoutLegacyKeys() throws Exception {
        Path file = Files.createTempFile("eventlens-v3", ".yaml");
        Files.writeString(file, """
                datasources:
                  - id: orders-mysql
                    type: mysql
                    url: jdbc:mysql://localhost:3306/orders
                    username: root
                    password: secret
                streams:
                  - id: events-kafka
                    type: kafka
                    bootstrap-servers: localhost:9092
                    topic: domain-events
                """);

        EventLensConfig config = ConfigLoader.load(file.toString());

        assertThat(config.getDatasources()).hasSize(1);
        assertThat(config.getDatasources().get(0).getType()).isEqualTo("mysql");
        assertThat(config.getDatasource().getUrl()).isEqualTo("jdbc:mysql://localhost:3306/orders");
        assertThat(config.getStreams()).hasSize(1);
        assertThat(config.getKafka().getBootstrapServers()).isEqualTo("localhost:9092");
    }

    @Test
    void rejectsMixedLegacyAndPluralConfig() throws Exception {
        Path file = Files.createTempFile("eventlens-mixed", ".yaml");
        Files.writeString(file, """
                datasource:
                  url: jdbc:postgresql://localhost:5432/eventlens
                  username: postgres
                datasources:
                  - id: pg
                    type: postgres
                    url: jdbc:postgresql://localhost:5432/eventlens
                    username: postgres
                """);

        assertThatThrownBy(() -> ConfigLoader.load(file.toString()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("both 'datasource' and 'datasources'");
    }
}
