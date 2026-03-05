package io.eventlens.core.engine;

import io.eventlens.core.exception.ConditionParseException;
import io.eventlens.core.model.*;
import io.eventlens.core.spi.EventStoreReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;

/**
 * Binary search through an aggregate's event history to find the <em>first</em>
 * event that caused a given condition to become true.
 *
 * <p>
 * O(log n) replays instead of O(n) — like git-bisect for event sourcing.
 *
 * <p>
 * Example: {@code bisect("ACC-001", "balance < 0")} finds the withdrawal
 * that first made the balance go negative.
 */
public class BisectEngine {

    private static final Logger log = LoggerFactory.getLogger(BisectEngine.class);

    private final ReplayEngine replayEngine;
    private final EventStoreReader reader;

    public BisectEngine(ReplayEngine replayEngine, EventStoreReader reader) {
        this.replayEngine = replayEngine;
        this.reader = reader;
    }

    /**
     * Find the first event where the condition becomes true using binary search.
     *
     * @param aggregateId the aggregate to bisect
     * @param condition   a predicate over the aggregate state
     * @return bisect result with the culprit event (or null if not found)
     */
    public BisectResult bisect(String aggregateId, Predicate<Map<String, Object>> condition) {
        List<StoredEvent> allEvents = reader.getEvents(aggregateId);

        if (allEvents.isEmpty()) {
            return new BisectResult(aggregateId, null, null, 0, "No events found");
        }

        log.debug("Bisecting '{}' over {} events", aggregateId, allEvents.size());

        // First check: does the condition ever become true?
        var fullReplay = replayEngine.replayTo(aggregateId, allEvents.getLast().sequenceNumber());
        int replayCount = 1;
        if (!condition.test(fullReplay.state())) {
            return new BisectResult(aggregateId, null, null, replayCount,
                    "Condition never becomes true in entire history");
        }

        // Binary search for the first event where condition becomes true
        int low = 0;
        int high = allEvents.size() - 1;
        StoredEvent culprit = null;

        while (low <= high) {
            int mid = (low + high) / 2;
            long midSequence = allEvents.get(mid).sequenceNumber();

            var replay = replayEngine.replayTo(aggregateId, midSequence);
            replayCount++;

            if (condition.test(replay.state())) {
                culprit = allEvents.get(mid);
                high = mid - 1; // search earlier
            } else {
                low = mid + 1; // search later
            }
        }

        // Get the exact transition at the culprit
        StateTransition transition = null;
        if (culprit != null) {
            long culpritSeq = culprit.sequenceNumber();
            transition = replayEngine.replayFull(aggregateId).stream()
                    .filter(t -> t.event().sequenceNumber() == culpritSeq)
                    .findFirst().orElse(null);
        }

        String summary = culprit != null
                ? String.format("Event #%d (%s at %s) first caused the condition",
                        culprit.sequenceNumber(), culprit.eventType(), culprit.timestamp())
                : "Could not isolate culprit event";

        log.info("Bisect complete for '{}': {} replays performed. {}",
                aggregateId, replayCount, summary);

        return new BisectResult(aggregateId, culprit, transition, replayCount, summary);
    }

    /**
     * Parse a condition string like {@code "balance < 0"} into a state predicate.
     *
     * <p>
     * Supported operators: {@code < <= > >= == !=} (numeric),
     * {@code == != contains} (string).
     *
     * @throws ConditionParseException if the expression format is invalid
     */
    public static Predicate<Map<String, Object>> parseCondition(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new ConditionParseException("<empty>", "expression cannot be empty");
        }

        // Sanitize: only allow safe characters
        if (!expression.matches("[\\w.\\s<>=!]+")) {
            throw new ConditionParseException(expression,
                    "expression contains invalid characters. Allowed: letters, numbers, spaces, and operators (< > = !)");
        }

        String[] parts = expression.trim().split("\\s+", 3);
        if (parts.length != 3) {
            throw new ConditionParseException(expression,
                    "format must be 'field operator value', e.g. 'balance < 0'");
        }

        String field = parts[0];
        String operator = parts[1];
        String rawValue = parts[2];

        return state -> {
            Object fieldValue = state.get(field);
            if (fieldValue == null)
                return false;

            if (fieldValue instanceof Number num) {
                double actual = num.doubleValue();
                double expected;
                try {
                    expected = Double.parseDouble(rawValue);
                } catch (NumberFormatException e) {
                    return false;
                }
                return switch (operator) {
                    case "<" -> actual < expected;
                    case "<=" -> actual <= expected;
                    case ">" -> actual > expected;
                    case ">=" -> actual >= expected;
                    case "==" -> actual == expected;
                    case "!=" -> actual != expected;
                    default -> throw new ConditionParseException(expression,
                            "unknown numeric operator: " + operator);
                };
            }

            String actual = fieldValue.toString();
            return switch (operator) {
                case "==" -> actual.equals(rawValue);
                case "!=" -> !actual.equals(rawValue);
                case "contains" -> actual.contains(rawValue);
                default -> false;
            };
        };
    }

    public record BisectResult(
            String aggregateId,
            StoredEvent culpritEvent,
            StateTransition transition,
            int replaysPerformed,
            String summary) {
    }
}
