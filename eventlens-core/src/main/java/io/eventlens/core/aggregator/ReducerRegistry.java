package io.eventlens.core.aggregator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that maps aggregate types to their {@link AggregateReducer}
 * implementations.
 * Falls back to {@link GenericJsonReducer} for unregistered types.
 */
public class ReducerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReducerRegistry.class);

    private final Map<String, AggregateReducer> reducers = new ConcurrentHashMap<>();
    private final AggregateReducer defaultReducer = new GenericJsonReducer();

    /**
     * Register a reducer for an aggregate type.
     *
     * @param aggregateType e.g. "BankAccount"
     * @param reducer       the reducer to use
     */
    public void register(String aggregateType, AggregateReducer reducer) {
        reducers.put(aggregateType, reducer);
        log.info("Registered reducer '{}' for aggregate type '{}'",
                reducer.getClass().getSimpleName(), aggregateType);
    }

    /**
     * Get the reducer for an aggregate type, falling back to the generic reducer.
     */
    public AggregateReducer getReducer(String aggregateType) {
        return reducers.getOrDefault(aggregateType, defaultReducer);
    }

    /**
     * Returns true if a custom reducer (non-generic) is registered for this type.
     */
    public boolean hasCustomReducer(String aggregateType) {
        return reducers.containsKey(aggregateType);
    }

    public Map<String, AggregateReducer> allReducers() {
        return Map.copyOf(reducers);
    }
}
