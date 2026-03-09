package io.eventlens.core.engine;

import io.eventlens.core.EventLensConfig.AnomalyConfig;
import io.eventlens.core.model.*;
import io.eventlens.core.spi.EventStoreReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;

/**
 * Rule-based anomaly detector. Scans an aggregate's full state history
 * and reports any state that violates registered rules.
 */
public class AnomalyDetector {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetector.class);

    private final EventStoreReader reader;
    private final ReplayEngine replayEngine;
    private final List<AnomalyRule> rules = new ArrayList<>();
    // Fix 11: max aggregates per scanRecent() to prevent O(n²) blowup on busy
    // stores
    private final int maxAggregatesPerScan;

    public AnomalyDetector(EventStoreReader reader, ReplayEngine replayEngine) {
        this(reader, replayEngine, 20);
    }

    public AnomalyDetector(EventStoreReader reader, ReplayEngine replayEngine, AnomalyConfig config) {
        this(reader, replayEngine, config != null ? config.getMaxAggregatesPerScan() : 20);
    }

    public AnomalyDetector(EventStoreReader reader, ReplayEngine replayEngine, int maxAggregatesPerScan) {
        this.reader = reader;
        this.replayEngine = replayEngine;
        this.maxAggregatesPerScan = maxAggregatesPerScan;
        registerDefaultRules();
    }

    private void registerDefaultRules() {
        // Rule: negative balance
        rules.add(new AnomalyRule(
                "NEGATIVE_BALANCE",
                "Balance went negative",
                AnomalyReport.Severity.HIGH,
                (event, state) -> {
                    Object balance = state.get("balance");
                    return balance instanceof Number n && n.doubleValue() < 0;
                }));

        // Rule: aggregate may need a snapshot
        rules.add(new AnomalyRule(
                "SNAPSHOT_NEEDED",
                "Aggregate has many events without snapshot (performance risk)",
                AnomalyReport.Severity.MEDIUM,
                (event, state) -> {
                    Object version = state.get("_version");
                    return version instanceof Number n && n.longValue() > 50 && n.longValue() % 50 == 0;
                }));
    }

    /** Register a custom anomaly rule. */
    public void addRule(AnomalyRule rule) {
        rules.add(rule);
        log.info("Added anomaly rule: '{}'", rule.code());
    }

    /** Scan one aggregate for anomalies across its full history. */
    public List<AnomalyReport> scan(String aggregateId) {
        log.debug("Scanning aggregate '{}' for anomalies ({} rules)", aggregateId, rules.size());
        List<StateTransition> transitions = replayEngine.replayFull(aggregateId);
        List<AnomalyReport> anomalies = new ArrayList<>();

        for (StateTransition t : transitions) {
            for (AnomalyRule rule : rules) {
                if (rule.test().test(t.event(), t.stateAfter())) {
                    anomalies.add(new AnomalyReport(
                            rule.code(), rule.description(), rule.severity(),
                            aggregateId, t.event().sequenceNumber(),
                            t.event().eventType(), t.event().timestamp(), t.stateAfter()));
                }
            }
        }

        if (!anomalies.isEmpty()) {
            log.warn("Found {} anomaly(-ies) in aggregate '{}'", anomalies.size(), aggregateId);
        }
        return anomalies;
    }

    /**
     * Scan recent events (grouped by aggregate) for anomalies.
     *
     * <p>
     * Fix 11: caps the number of aggregates scanned to {@code maxAggregatesPerScan}
     * (default: 20, configurable via {@code anomaly.max-aggregates-per-scan}).
     * Without this cap, a busy event store with 100 recent events across 50
     * aggregates
     * could trigger 50 full replays in a single HTTP request — O(n²) in event
     * count.
     */
    public List<AnomalyReport> scanRecent(int limit) {
        List<StoredEvent> recent = reader.getRecentEvents(limit);
        // Preserve insertion order, deduplicate aggregate IDs
        Map<String, String> seen = new LinkedHashMap<>();
        for (StoredEvent e : recent) {
            seen.put(e.aggregateId(), e.aggregateType());
            if (seen.size() >= maxAggregatesPerScan)
                break; // Fix 11: cap here
        }

        if (seen.size() >= maxAggregatesPerScan) {
            log.debug("scanRecent: capped at {} aggregates (max-aggregates-per-scan={}). "
                    + "Increase anomaly.max-aggregates-per-scan to scan more.",
                    seen.size(), maxAggregatesPerScan);
        }

        List<AnomalyReport> all = new ArrayList<>();
        for (String aggId : seen.keySet()) {
            all.addAll(scan(aggId));
        }
        return all;
    }

    public record AnomalyRule(
            String code,
            String description,
            AnomalyReport.Severity severity,
            BiPredicate<StoredEvent, Map<String, Object>> test) {
    }
}
