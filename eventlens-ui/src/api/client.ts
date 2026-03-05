import axios from 'axios';

const api = axios.create({ baseURL: '/api' });

// ── Types ──────────────────────────────────────────────────────────────────
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

// ── API calls ──────────────────────────────────────────────────────────────
export const searchAggregates = (q: string, limit = 20) =>
    api.get<string[]>(`/aggregates/search?q=${encodeURIComponent(q)}&limit=${limit}`).then(r => r.data);

export const getAggregateTypes = () =>
    api.get<string[]>('/meta/types').then(r => r.data);

export const getTimeline = (id: string) =>
    api.get<{ events: StoredEvent[]; totalEvents: number }>(`/aggregates/${id}/timeline`).then(r => r.data);

export const getTransitions = (id: string) =>
    api.get<StateTransition[]>(`/aggregates/${id}/transitions`).then(r => r.data);

export const replayTo = (id: string, seq: number) =>
    api.get<ReplayResult>(`/aggregates/${id}/replay/${seq}`).then(r => r.data);

export const bisect = (id: string, expression: string) =>
    api.post<BisectResult>(`/aggregates/${id}/bisect`, expression, {
        headers: { 'Content-Type': 'text/plain' },
    }).then(r => r.data);

export const getAnomalies = (limit = 100) =>
    api.get<AnomalyReport[]>(`/anomalies/recent?limit=${limit}`).then(r => r.data);

export const getRecentEvents = (limit = 50) =>
    api.get<StoredEvent[]>(`/events/recent?limit=${limit}`).then(r => r.data);

export const getHealth = () =>
    api.get('/health').then(r => r.data);
