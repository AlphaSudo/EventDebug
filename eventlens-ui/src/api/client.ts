import axios from 'axios';
import type { AnomalyReport, BisectResult, ReplayResult, StateTransition, StoredEvent } from './types';
import {
    demoAggregateTypes,
    demoAnomalies,
    demoBisect,
    demoHealth,
    demoReplayTo,
    demoSearchAggregates,
    demoTimeline,
    demoRecentEvents,
    demoTransitions,
    DEMO_AGGREGATE_ID,
} from '../demo/demoData';
import { isDemoMode } from '../demo/demoMode';

const api = axios.create({ baseURL: '/api' });

function delay(ms: number) {
    return new Promise<void>(resolve => {
        setTimeout(resolve, ms);
    });
}

// ── Types (re-exported for existing imports from `api/client`) ─────────────
export type {
    AnomalyReport,
    BisectResult,
    FieldChange,
    ReplayResult,
    StateTransition,
    StoredEvent,
} from './types';

// ── API calls ──────────────────────────────────────────────────────────────
export const searchAggregates = async (q: string, limit = 20) => {
    if (isDemoMode()) {
        await delay(40);
        const demo = demoSearchAggregates(q);
        try {
            const r = await api.get<string[]>(
                `/aggregates/search?q=${encodeURIComponent(q)}&limit=${limit}`
            );
            return [...new Set([...demo, ...r.data])].slice(0, limit);
        } catch {
            return demo;
        }
    }
    return api
        .get<string[]>(`/aggregates/search?q=${encodeURIComponent(q)}&limit=${limit}`)
        .then(r => r.data);
};

export const getAggregateTypes = async () => {
    if (isDemoMode()) {
        await delay(30);
        try {
            const r = await api.get<string[]>('/meta/types');
            return [...new Set([...demoAggregateTypes(), ...r.data])];
        } catch {
            return demoAggregateTypes();
        }
    }
    return api.get<string[]>('/meta/types').then(r => r.data);
};

export const getTimeline = async (id: string, limit = 500, offset = 0) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(50);
        return demoTimeline(id, limit, offset);
    }
    return api
        .get<{ events: StoredEvent[]; totalEvents: number }>(
            `/aggregates/${id}/timeline?limit=${limit}&offset=${offset}`
        )
        .then(r => r.data);
};

export const getTransitions = async (id: string) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(50);
        return demoTransitions(id);
    }
    return api.get<StateTransition[]>(`/aggregates/${id}/transitions`).then(r => r.data);
};

export const replayTo = async (id: string, seq: number) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(40);
        return demoReplayTo(id, seq);
    }
    return api.get<ReplayResult>(`/aggregates/${id}/replay/${seq}`).then(r => r.data);
};

export const bisect = async (id: string, expression: string) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(60);
        return demoBisect(expression);
    }
    return api
        .post<BisectResult>(`/aggregates/${id}/bisect`, expression, {
            headers: { 'Content-Type': 'text/plain' },
        })
        .then(r => r.data);
};

export const getAnomalies = async (limit = 100) => {
    if (isDemoMode()) {
        await delay(45);
        return demoAnomalies(limit);
    }
    return api.get<AnomalyReport[]>(`/anomalies/recent?limit=${limit}`).then(r => r.data);
};

export const getRecentEvents = async (limit = 50) => {
    if (isDemoMode()) {
        await delay(35);
        return demoRecentEvents(limit);
    }
    return api.get<StoredEvent[]>(`/events/recent?limit=${limit}`).then(r => r.data);
};

export const getHealth = async () => {
    if (isDemoMode()) {
        await delay(20);
        return demoHealth();
    }
    return api.get('/health').then(r => r.data);
};

export { DEMO_AGGREGATE_ID } from '../demo/demoData';
