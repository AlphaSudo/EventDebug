package io.eventlens.spi;

import java.util.Map;

public record HealthStatus(
        State state,
        String message,
        Map<String, Object> details
) {
    public HealthStatus {
        details = Map.copyOf(details == null ? Map.of() : details);
    }

    public enum State {
        UP,
        DOWN
    }

    public static HealthStatus up() {
        return new HealthStatus(State.UP, null, Map.of());
    }

    public static HealthStatus down(String message) {
        return new HealthStatus(State.DOWN, message, Map.of());
    }
}
