package io.eventlens.core.exception;

/**
 * Thrown when EventLens cannot detect or parse the event store table schema.
 */
public class SchemaDetectionException extends EventLensException {

    public SchemaDetectionException(String message) {
        super(message);
    }

    public SchemaDetectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
