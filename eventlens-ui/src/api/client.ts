import axios from 'axios';
import type {
    AnomalyReport,
    BisectResult,
    DatasourceHealth,
    DatasourceSummary,
    EventStatistics,
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
    demoStatistics,
    demoReplayTo,
    demoSearchAggregates,
    demoTimeline,
    demoRecentEvents,
    demoTransitions,
    DEMO_AGGREGATE_ID,
} from '../demo/demoData';
import { isDemoMode } from '../demo/demoMode';

const api = axios.create({
    baseURL: '/api',
    withCredentials: true,
});

let csrfToken: string | null = null;

api.interceptors.request.use(config => {
    const method = (config.method ?? 'get').toUpperCase();
    if (csrfToken && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
        config.headers = config.headers ?? {};
        config.headers['X-CSRF-Token'] = csrfToken;
    }
    return config;
});

export interface AuthPrincipal {
    userId: string;
    displayName: string;
    authMethod: string;
    roles: string[];
}

export interface AuthSessionResponse {
    authenticated: boolean;
    principal?: AuthPrincipal;
    returnHash?: string;
    provider?: string;
    basicLoginEnabled?: boolean;
    csrfToken?: string;
}

export interface AuditEntry {
    auditId: number;
    action: string;
    resourceType: string;
    resourceId: string | null;
    userId: string;
    authMethod: string;
    clientIp: string | null;
    requestId: string | null;
    userAgent: string | null;
    details: Record<string, unknown>;
    createdAt: string;
}

export interface AuditEntriesResponse {
    entries: AuditEntry[];
    limit: number;
    action?: string | null;
    userId?: string | null;
}

export interface ManagedApiKey {
    apiKeyId: string;
    keyPrefix: string;
    description?: string | null;
    principalUserId: string;
    roles: string[];
    createdAt: string;
    expiresAt?: string | null;
    revokedAt?: string | null;
    lastUsedAt?: string | null;
}

export interface ManagedApiKeysResponse {
    entries: ManagedApiKey[];
}

export interface CreateApiKeyRequest {
    principalUserId: string;
    roles: string[];
    description?: string;
    expiresAt?: string;
}

export interface CreateApiKeyResponse extends ManagedApiKey {
    apiKey: string;
}

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

function encodeBasicCredentials(username: string, password: string) {
    const credentials = `${username}:${password}`;
    const bytes = new TextEncoder().encode(credentials);
    let binary = '';
    bytes.forEach(byte => {
        binary += String.fromCharCode(byte);
    });
    return btoa(binary);
}

export type {
    AnomalyReport,
    BisectResult,
    DatasourceHealth,
    DatasourceSummary,
    DiffPatch,
    EventStatistics,
    FieldChange,
    LiveStreamUnavailableMessage,
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
    return api.get<{ events: StoredEvent[]; totalEvents: number }>(path).then(r => r.data);
};

export const getTransitions = async (id: string, source?: string | null) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(50);
        return demoTransitions(id);
    }
    return api.get<StateTransition[]>(withOptionalSource(`/aggregates/${id}/transitions`, source)).then(r => r.data);
};

export const replayTo = async (id: string, seq: number, source?: string | null) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(40);
        return demoReplayTo(id, seq);
    }
    return api.get<ReplayResult>(withOptionalSource(`/aggregates/${id}/replay/${seq}`, source)).then(r => r.data);
};

export const bisect = async (id: string, expression: string) => {
    if (isDemoMode() && id === DEMO_AGGREGATE_ID) {
        await delay(60);
        return demoBisect(expression);
    }
    return api.post<BisectResult>(`/aggregates/${id}/bisect`, expression, {
        headers: { 'Content-Type': 'text/plain' },
    }).then(r => r.data);
};

export const getAnomalies = async (limit = 100, source?: string | null) => {
    if (isDemoMode()) {
        await delay(45);
        return demoAnomalies(limit);
    }
    return api.get<AnomalyReport[]>(withOptionalSource(`/anomalies/recent?limit=${limit}`, source)).then(r => r.data);
};

export const getRecentEvents = async (limit = 50, source?: string | null) => {
    if (isDemoMode()) {
        await delay(35);
        return demoRecentEvents(limit);
    }
    return api.get<StoredEvent[]>(withOptionalSource(`/events/recent?limit=${limit}`, source)).then(r => r.data);
};

export const getStatistics = async (source?: string | null, bucketHours = 1, maxBuckets = 24) => {
    if (isDemoMode()) {
        await delay(30);
        return demoStatistics(bucketHours, maxBuckets);
    }
    const path = withOptionalSource(`/v1/statistics?bucketHours=${bucketHours}&maxBuckets=${maxBuckets}`, source);
    return api.get<EventStatistics>(path).then(r => r.data);
};

export const getHealth = async () => {
    if (isDemoMode()) {
        await delay(20);
        return demoHealth();
    }
    return api.get('/health').then(r => r.data);
};

export const getAuthSession = async () => {
    if (isDemoMode()) {
        return {
            authenticated: true,
            principal: {
                userId: 'demo-user',
                displayName: 'Demo User',
                authMethod: 'demo',
                roles: ['demo'],
            },
        } satisfies AuthSessionResponse;
    }
    return api.get<AuthSessionResponse>('/v1/auth/session').then(r => r.data);
};

export const getAuditEntries = async (limit = 25) => {
    return api.get<AuditEntriesResponse>(`/v1/audit?limit=${limit}`).then(r => r.data);
};

export const getManagedApiKeys = async () => {
    return api.get<ManagedApiKeysResponse>('/v1/admin/api-keys').then(r => r.data);
};

export const createManagedApiKey = async (payload: CreateApiKeyRequest) => {
    return api.post<CreateApiKeyResponse>('/v1/admin/api-keys', payload).then(r => r.data);
};

export const revokeManagedApiKey = async (apiKeyId: string) => {
    return api.post<{ apiKeyId: string; revoked: boolean }>(`/v1/admin/api-keys/${encodeURIComponent(apiKeyId)}/revoke`).then(r => r.data);
};

export const loginWithBasicSession = async (username: string, password: string, returnHash: string) => {
    return api.post<AuthSessionResponse>(
        '/v1/auth/login/basic',
        { returnHash },
        {
            headers: {
                Authorization: `Basic ${encodeBasicCredentials(username, password)}`,
            },
        }
    ).then(r => r.data);
};

export const logoutSession = async () => {
    if (isDemoMode()) {
        return { authenticated: false } satisfies AuthSessionResponse;
    }
    return api.post<AuthSessionResponse>('/v1/auth/logout').then(r => r.data);
};

export const buildOidcLoginUrl = (returnHash: string) =>
    `/api/v1/auth/login/oidc?returnHash=${encodeURIComponent(returnHash)}`;

export const setCsrfToken = (value: string | null) => {
    csrfToken = value;
};

export const getDatasources = async () => {
    if (isDemoMode()) {
        await delay(20);
        return [{
            id: 'demo-primary',
            displayName: 'Demo Primary',
            status: 'ready',
            healthMessage: 'Frontend demo datasource',
            capabilities: ['timeline', 'replay', 'statistics'],
        }];
    }
    return api.get<DatasourceSummary[]>('/v1/datasources').then(r => r.data);
};

export const getDatasourceHealth = async (id: string) => {
    if (isDemoMode()) {
        await delay(20);
        return {
            id,
            displayName: 'Demo Primary',
            status: 'ready',
            health: {
                state: 'up',
                message: 'Frontend demo datasource',
            },
            lastHealthCheck: new Date().toISOString(),
            failureReason: '',
        };
    }
    return api.get<DatasourceHealth>(`/v1/datasources/${encodeURIComponent(id)}/health`).then(r => r.data);
};

export const getPlugins = async () => {
    if (isDemoMode()) {
        await delay(20);
        return [{
            instanceId: 'demo-source',
            typeId: 'demo',
            displayName: 'Demo Source Plugin',
            pluginType: 'EVENT_SOURCE',
            lifecycle: 'ready',
            health: {
                state: 'up',
                message: 'Frontend demo plugin',
            },
            lastHealthCheck: new Date().toISOString(),
            failureReason: null,
        }];
    }
    return api.get<PluginSummary[]>('/v1/plugins').then(r => r.data);
};

export { DEMO_AGGREGATE_ID } from '../demo/demoData';




