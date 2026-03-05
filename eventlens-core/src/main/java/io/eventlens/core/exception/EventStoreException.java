package io.eventlens.core.exception;

/**
 * Thrown when EventLens cannot connect to or read from the event store.
 */
public class EventStoreException extends EventLensException {

    public EventStoreException(String message) {
        super(message);
    }

    public EventStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
