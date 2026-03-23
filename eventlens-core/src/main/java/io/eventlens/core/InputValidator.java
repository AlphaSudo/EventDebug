package io.eventlens.core;

import io.eventlens.core.exception.ConfigurationException;
import io.eventlens.core.exception.EventLensException;

import java.util.Map;
import java.util.regex.Pattern;

public final class InputValidator {

    // Aggregate ID: UUID or alphanumeric with dashes/underscores
    private static final Pattern AGGREGATE_ID_PATTERN =
            Pattern.compile("^[a-zA-Z0-9\\-_]{1,255}$");

    // Event type: uppercase alphanumeric with underscores
    private static final Pattern EVENT_TYPE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,127}$");

    // Aggregate type: uppercase alphanumeric with underscores
    private static final Pattern AGGREGATE_TYPE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    // Column names from config: only safe SQL identifiers
    private static final Pattern COLUMN_NAME_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private InputValidator() {
    }

    public static String validateAggregateId(String input) {
        if (input == null || !AGGREGATE_ID_PATTERN.matcher(input).matches()) {
            throw new ValidationException("aggregate_id",
                    "Must be alphanumeric with dashes/underscores, 1-255 chars");
        }
        return input;
    }

    public static String validateAggregateType(String input) {
        if (input == null || !AGGREGATE_TYPE_PATTERN.matcher(input).matches()) {
            throw new ValidationException("aggregate_type",
                    "Must be UPPER_SNAKE_CASE, 1-64 chars");
        }
        return input;
    }

    public static String validateEventType(String input) {
        if (input == null || !EVENT_TYPE_PATTERN.matcher(input).matches()) {
            throw new ValidationException("event_type",
                    "Must be UPPER_SNAKE_CASE, 1-128 chars");
        }
        return input;
    }

    public static int validateLimit(String input, int defaultLimit, int maxLimit) {
        if (input == null) return defaultLimit;
        try {
            int limit = Integer.parseInt(input);
            if (limit < 1) return 1;
            if (limit > maxLimit) return maxLimit;
            return limit;
        } catch (NumberFormatException e) {
            throw new ValidationException("limit", "Must be a positive integer");
        }
    }

    public static int validateOffset(String input) {
        if (input == null) return 0;
        try {
            int offset = Integer.parseInt(input);
            if (offset < 0) return 0;
            return offset;
        } catch (NumberFormatException e) {
            throw new ValidationException("offset", "Must be a non-negative integer");
        }
    }

    public static void validateColumnMapping(Map<String, String> columns) {
        if (columns == null) return;
        for (var entry : columns.entrySet()) {
            String logical = entry.getKey();
            String physical = entry.getValue();
            if (physical == null) continue;
            if (!COLUMN_NAME_PATTERN.matcher(physical).matches()) {
                throw new ConfigurationException(
                        ("Column mapping '%s' → '%s' contains unsafe characters. " +
                                "Only lowercase letters, digits, and underscores are allowed.")
                                .formatted(logical, physical));
            }
        }
    }

    public static final class ValidationException extends EventLensException {
        private final String field;

        public ValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String getField() {
            return field;
        }
    }
}

