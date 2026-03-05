package io.eventlens.core.exception;

/**
 * Base exception for all EventLens runtime errors.
 * All exceptions are unchecked to avoid cluttering the SPI and engine APIs.
 */
public class EventLensException extends RuntimeException {

    public EventLensException(String message) {
        super(message);
    }

    public EventLensException(String message, Throwable cause) {
        super(message, cause);
    }
}
