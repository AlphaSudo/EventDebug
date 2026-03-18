package io.eventlens.core.exception;

/**
 * Thrown when the database cancels a query due to a configured timeout.
 */
public class QueryTimeoutException extends EventLensException {

    private final int timeoutSeconds;

    public QueryTimeoutException(int timeoutSeconds, String message, Throwable cause) {
        super(message, cause);
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}

