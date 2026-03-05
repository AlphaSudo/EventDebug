package io.eventlens.core.engine;

import io.eventlens.core.model.*;
import io.eventlens.core.spi.EventStoreReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Compares the state of two aggregates(or two points in time for the same
 * aggregate)
 * and returns a field-level diff.
 */
public class DiffEngine {

    private static final Logger log = LoggerFactory.getLogger(DiffEngine.class);

    private final ReplayEngine replayEngine;

    public DiffEngine(ReplayEngine replayEngine) {
        this.replayEngine = replayEngine;
    }

    /**
     * Compare the final states of two aggregates.
     */
    public Map<String, StateTransition.FieldChange> diff(String aggregateIdA, String aggregateIdB) {
        log.debug("Diffing final states of '{}' vs '{}'", aggregateIdA, aggregateIdB);
        var resultA = replayEngine.replayFull(aggregateIdA);
        var resultB = replayEngine.replayFull(aggregateIdB);

        Map<String, Object> stateA = resultA.isEmpty() ? Map.of() : resultA.getLast().stateAfter();
        Map<String, Object> stateB = resultB.isEmpty() ? Map.of() : resultB.getLast().stateAfter();

        return replayEngine.computeDiff(stateA, stateB);
    }

    /**
     * Compare the state of an aggregate at two different sequence numbers.
     */
    public Map<String, StateTransition.FieldChange> diffAtSequences(
            String aggregateId, long seqA, long seqB) {
        log.debug("Diffing '{}' at seq #{} vs seq #{}", aggregateId, seqA, seqB);
        var resultA = replayEngine.replayTo(aggregateId, seqA);
        var resultB = replayEngine.replayTo(aggregateId, seqB);
        return replayEngine.computeDiff(resultA.state(), resultB.state());
    }
}
