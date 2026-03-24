import axios from 'axios';
import type {
    AnomalyReport,
    BisectResult,
    DatasourceHealth,
    DatasourceSummary,
    PluginSummary,
    ReplayResult,
    StateTransition,
    StoredEvent,
} from './types';
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

function withOptionalSource(path: string, source?: string | null) {
    if (!source) {
        return path;
    }
    const separator = path.includes('?') ? '&' : '?';
    return `${path}${separator}source=${encodeURIComponent(source)}`;
}

export type {
    AnomalyReport,
    BisectResult,
    DatasourceHealth,
    DatasourceSummary,
    FieldChange,
    PluginSummary,
    ReplayResult,
    StateTransition,
    StoredEvent,
} from './types';

export const searchAggregates = async (q: string, limit = 20, source?: string | null) => {
    const path = withOptionalSource(`/aggregates/search?q=${encodeURIComponent(q)}&limit=${limit}`, source);
    if (isDemoMode()) {
        await delay(40);
        const demo = demoSearchAggregates(q);
        try {
            const r = await api.get<string[]>(path);
            return [...new Set([...demo, ...r.data])].slice(0, limit);
        } catch {
            return demo;
        }
    }
    return api.get<string[]>(path).then(r => r.data);
};

export const getAggregateTypes = async (source?: string | null) => {
    const path = withOptionalSource('/meta/types', source);
    if (isDemoMode()) {
        await delay(30);
        try {
            const r = await api.get<string[]>(path);
            return [...new Set([...demoAggregateTypes(), ...r.data])];
        } catch {
            return demoAggregateTypes();
        }
    }
    return api.get<string[]>(path).then(r => r.data);
};

export const getTimeline = async (id: string, limit = 500, offset = 0, source?: string | null, fields: 'full' | 'metadata' = 'full') => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(50);
        return demoTimeline(id, limit, offset);
    }
    const path = withOptionalSource(
        `/aggregates/${id}/timeline?limit=${limit}&offset=${offset}&fields=${fields}`,
        source
    );
    return api
        .get<{ events: StoredEvent[]; totalEvents: number }>(path)
        .then(r => r.data);
};

export const getTransitions = async (id: string, source?: string | null) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(50);
        return demoTransitions(id);
    }
    return api
        .get<StateTransition[]>(withOptionalSource(`/aggregates/${id}/transitions`, source))
        .then(r => r.data);
};

export const replayTo = async (id: string, seq: number, source?: string | null) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(40);
        return demoReplayTo(id, seq);
    }
    return api
        .get<ReplayResult>(withOptionalSource(`/aggregates/${id}/replay/${seq}`, source))
        .then(r => r.data);
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

export const getRecentEvents = async (limit = 50, source?: string | null) => {
    if (isDemoMode()) {
        await delay(35);
        return demoRecentEvents(limit);
    }
    return api.get<StoredEvent[]>(withOptionalSource(`/events/recent?limit=${limit}`, source)).then(r => r.data);
};

export const getHealth = async () => {
    if (isDemoMode()) {
        await delay(20);
        return demoHealth();
    }
    return api.get('/health').then(r => r.data);
};

export const getDatasources = async () => api.get<DatasourceSummary[]>('/v1/datasources').then(r => r.data);

export const getDatasourceHealth = async (id: string) =>
    api.get<DatasourceHealth>(`/v1/datasources/${encodeURIComponent(id)}/health`).then(r => r.data);

export const getPlugins = async () => api.get<PluginSummary[]>('/v1/plugins').then(r => r.data);

export { DEMO_AGGREGATE_ID } from '../demo/demoData';
