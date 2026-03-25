package io.eventlens.test;

import io.eventlens.spi.Event;
import io.eventlens.spi.StreamAdapterPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class StreamAdapterPluginTestKit {

    protected StreamAdapterPlugin plugin;

    @BeforeEach
    void initializePluginUnderTest() throws Exception {
        plugin = createPlugin();
    }

    @AfterEach
    void cleanupPluginUnderTest() throws Exception {
        if (plugin != null) {
            plugin.close();
        }
        cleanupStream();
    }

    protected abstract StreamAdapterPlugin createPlugin() throws Exception;

    protected abstract void emitCanonicalEvents() throws Exception;

    protected void cleanupStream() throws Exception {
    }

    protected int expectedEventCount() {
        return 2;
    }

    protected Duration awaitTimeout() {
        return Duration.ofSeconds(30);
    }

    @Test
    void healthCheckReportsUpForInitializedPlugin() {
        assertThat(plugin.healthCheck().state().name()).isEqualTo("UP");
    }

    @Test
    void subscribeReceivesCanonicalEvents() throws Exception {
        CountDownLatch latch = new CountDownLatch(expectedEventCount());
        List<Event> received = new CopyOnWriteArrayList<>();

        plugin.subscribe(event -> {
            received.add(event);
            if (latch.getCount() > 0) {
                latch.countDown();
            }
        });

        emitCanonicalEvents();

        assertThat(latch.await(awaitTimeout().toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSizeGreaterThanOrEqualTo(expectedEventCount());
        assertThat(received).extracting(Event::aggregateId).contains(CanonicalEventSet.PRIMARY_AGGREGATE_ID);
        plugin.unsubscribe();
    }
}
