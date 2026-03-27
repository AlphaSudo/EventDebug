package io.eventlens.core.pii;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.eventlens.core.engine.ReplayEngine;
import io.eventlens.core.model.AggregateTimeline;
import io.eventlens.core.model.AnomalyReport;
import io.eventlens.core.model.StateTransition;
import io.eventlens.core.model.StoredEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SensitiveDataProtector {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PiiMasker piiMasker;
    private final ObjectMapper mapper;

    public SensitiveDataProtector(PiiMasker piiMasker) {
        this.piiMasker = piiMasker;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    public StoredEvent maskEvent(StoredEvent event) {
        if (event == null || event.payload() == null) {
            return event;
        }
        String maskedPayload = maskJsonString(event.payload());
        if (maskedPayload.equals(event.payload())) {
            return event;
        }
        return new StoredEvent(
                event.eventId(),
                event.aggregateId(),
                event.aggregateType(),
                event.sequenceNumber(),
                event.eventType(),
                maskedPayload,
                event.metadata(),
                event.timestamp(),
                event.globalPosition()
        );
    }

    public AggregateTimeline maskTimeline(AggregateTimeline timeline) {
        if (timeline == null || timeline.events() == null) {
            return timeline;
        }
        return new AggregateTimeline(
                timeline.aggregateId(),
                timeline.aggregateType(),
                timeline.events().stream().map(this::maskEvent).toList(),
                timeline.totalEvents()
        );
    }

    public ReplayEngine.ReplayResult maskReplayResult(ReplayEngine.ReplayResult replay) {
        if (replay == null) {
            return null;
        }
        return new ReplayEngine.ReplayResult(
                replay.aggregateId(),
                replay.atSequence(),
                maskMap(replay.state()),
                replay.transitions().stream().map(this::maskTransition).toList()
        );
    }

    public List<StateTransition> maskTransitions(List<StateTransition> transitions) {
        if (transitions == null) {
            return List.of();
        }
        return transitions.stream().map(this::maskTransition).toList();
    }

    public AnomalyReport maskAnomaly(AnomalyReport report) {
        if (report == null) {
            return null;
        }
        return new AnomalyReport(
                report.code(),
                report.description(),
                report.severity(),
                report.aggregateId(),
                report.atSequence(),
                report.triggeringEventType(),
                report.timestamp(),
                maskMap(report.stateAtAnomaly())
        );
    }

    public List<AnomalyReport> maskAnomalies(List<AnomalyReport> reports) {
        if (reports == null) {
            return List.of();
        }
        return reports.stream().map(this::maskAnomaly).toList();
    }

    public String maskJsonString(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return rawJson;
        }
        try {
            JsonNode original = mapper.readTree(rawJson);
            JsonNode masked = piiMasker.mask(original, rawJson);
            if (masked == original) {
                return rawJson;
            }
            return mapper.writeValueAsString(masked);
        } catch (Exception e) {
            return rawJson;
        }
    }

    private StateTransition maskTransition(StateTransition transition) {
        if (transition == null) {
            return null;
        }
        return new StateTransition(
                maskEvent(transition.event()),
                maskMap(transition.stateBefore()),
                maskMap(transition.stateAfter()),
                maskDiff(transition.diff())
        );
    }

    private Map<String, StateTransition.FieldChange> maskDiff(Map<String, StateTransition.FieldChange> diff) {
        if (diff == null || diff.isEmpty()) {
            return Map.of();
        }
        Map<String, StateTransition.FieldChange> masked = new LinkedHashMap<>();
        for (var entry : diff.entrySet()) {
            var change = entry.getValue();
            masked.put(entry.getKey(), new StateTransition.FieldChange(
                    maskValue(change.oldValue()),
                    maskValue(change.newValue())
            ));
        }
        return Map.copyOf(masked);
    }

    private Map<String, Object> maskMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return value == null ? Map.of() : value;
        }
        try {
            String rawJson = mapper.writeValueAsString(value);
            JsonNode masked = piiMasker.mask(mapper.valueToTree(value), rawJson);
            return mapper.convertValue(masked, MAP_TYPE);
        } catch (Exception e) {
            return value;
        }
    }

    private Object maskValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String rawJson = mapper.writeValueAsString(value);
            JsonNode masked = piiMasker.mask(mapper.valueToTree(value), rawJson);
            return mapper.convertValue(masked, Object.class);
        } catch (Exception e) {
            return value;
        }
    }
}
