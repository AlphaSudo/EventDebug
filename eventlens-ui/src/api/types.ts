export interface StoredEvent {
    eventId: string;
    aggregateId: string;
    aggregateType: string;
    sequenceNumber: number;
    eventType: string;
    payload: string;
    metadata: string;
    timestamp: string;
    globalPosition: number;
}

export interface FieldChange {
    oldValue: unknown;
    newValue: unknown;
}

export interface StateTransition {
    event: StoredEvent;
    stateBefore: Record<string, unknown>;
    stateAfter: Record<string, unknown>;
    diff: Record<string, FieldChange>;
}

export interface ReplayResult {
    aggregateId: string;
    atSequence: number;
    state: Record<string, unknown>;
    transitions: StateTransition[];
}

export interface BisectResult {
    aggregateId: string;
    culpritEvent: StoredEvent | null;
    transition: StateTransition | null;
    replaysPerformed: number;
    summary: string;
}

export interface AnomalyReport {
    code: string;
    description: string;
    severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
    aggregateId: string;
    atSequence: number;
    triggeringEventType: string;
    timestamp: string;
    stateAtAnomaly: Record<string, unknown>;
}

export interface DatasourceSummary {
    id: string;
    displayName: string;
    status: string;
    healthMessage: string;
    capabilities: string[];
}

export interface DatasourceHealth {
    id: string;
    displayName: string;
    status: string;
    health: {
        state: string;
        message: string;
    };
    lastHealthCheck: string;
    failureReason: string;
}

export interface PluginSummary {
    instanceId: string;
    typeId: string;
    displayName: string;
    pluginType: string;
    lifecycle: string;
    health: {
        state: string;
        message: string;
    };
    lastHealthCheck: string;
    failureReason: string | null;
}
