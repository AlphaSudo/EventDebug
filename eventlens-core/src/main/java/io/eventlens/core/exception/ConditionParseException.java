package io.eventlens.core.exception;

/**
 * Thrown when a bisect condition expression cannot be parsed.
 */
public class ConditionParseException extends EventLensException {

    public ConditionParseException(String expression, String reason) {
        super(String.format("Cannot parse condition '%s': %s", expression, reason));
    }
}
