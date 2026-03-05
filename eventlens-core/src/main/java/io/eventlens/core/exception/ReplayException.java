package io.eventlens.core.exception;

/**
 * Thrown when a replay operation fails — e.g., reducer throws or data is
 * corrupt.
 */
public class ReplayException extends EventLensException {

    public ReplayException(String message) {
        super(message);
    }

    public ReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}
