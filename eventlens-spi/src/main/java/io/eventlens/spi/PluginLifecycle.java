package io.eventlens.spi;

public enum PluginLifecycle {
    DISCOVERED,
    INITIALIZING,
    READY,
    DEGRADED,
    FAILED,
    STOPPED
}
