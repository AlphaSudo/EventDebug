package io.eventlens.spi;

import java.util.Optional;

public final class SpiVersions {
    public static final int CURRENT = 1;
    public static final int MINIMUM_SUPPORTED = 1;

    private SpiVersions() {
    }

    public static Optional<String> checkCompatibility(String pluginId, int pluginSpiVersion) {
        if (pluginSpiVersion < MINIMUM_SUPPORTED) {
            return Optional.of(
                    "Plugin '%s' uses SPI version %d, minimum supported is %d. Please upgrade the plugin."
                            .formatted(pluginId, pluginSpiVersion, MINIMUM_SUPPORTED)
            );
        }
        if (pluginSpiVersion > CURRENT) {
            return Optional.of(
                    "Plugin '%s' uses SPI version %d, but this EventLens supports up to %d. Please upgrade EventLens."
                            .formatted(pluginId, pluginSpiVersion, CURRENT)
            );
        }
        return Optional.empty();
    }
}
