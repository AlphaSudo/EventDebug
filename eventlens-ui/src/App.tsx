import { useEffect, useRef, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import SearchBar from './components/SearchBar';
import Timeline from './components/Timeline';
import StateViewer, { type TabId } from './components/StateViewer';
import LiveStream from './components/LiveStream';
import AnomalyPanel from './components/AnomalyPanel';
import KeyboardHints from './components/KeyboardHints';
import {
    getDatasourceHealth,
    getDatasources,
    getHealth,
    getPlugins,
    getRecentEvents,
    getTransitions,
    type DatasourceHealth,
    type DatasourceSummary,
    type PluginSummary,
} from './api/client';
import { DEMO_AGGREGATE_ID } from './demo/demoData';
import { isDemoMode } from './demo/demoMode';
import { parseEventTimestamp } from './utils/time';

function GeometricLogo() {
    return (
        <svg viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg">
            <defs>
                <linearGradient id="lens-grad" x1="0" y1="0" x2="40" y2="40">
                    <stop offset="0%" stopColor="#00f0ff" />
                    <stop offset="100%" stopColor="#ff00e5" />
                </linearGradient>
            </defs>
            <circle cx="18" cy="18" r="11" stroke="url(#lens-grad)" strokeWidth="2.5" fill="none" />
            <circle cx="18" cy="18" r="6" stroke="#00f0ff" strokeWidth="1" fill="none" opacity="0.5" />
            <line x1="26" y1="26" x2="36" y2="36" stroke="url(#lens-grad)" strokeWidth="3" strokeLinecap="round" />
            <polygon points="18,12 21,18 18,24 15,18" fill="#00f0ff" opacity="0.3" />
        </svg>
    );
}

function MiniWaveform() {
    const bars = [6, 12, 8, 16, 10, 14, 7, 11, 15, 9];
    return (
        <div className="mini-wave">
            {bars.map((h, i) => (
                <div
                    key={i}
                    className="mini-wave-bar"
                    style={{ height: h, animationDelay: `${i * 0.1}s` }}
                />
            ))}
        </div>
    );
}

function statusTone(status: string) {
    const normalized = status.toLowerCase();
    if (normalized === 'ready' || normalized === 'up') return '#00ff88';
    if (normalized === 'degraded' || normalized === 'initializing') return '#ffd166';
    return '#ff6b6b';
}

function isSelectableDatasource(status: string) {
    const normalized = status.toLowerCase();
    return normalized === 'ready' || normalized === 'degraded';
}

function ConnectionStats({ isUp, source }: { isUp: boolean; source?: string | null }) {
    const [uptime, setUptime] = useState(0);
    const [eventCount, setEventCount] = useState<number | null>(null);
    const intervalRef = useRef<ReturnType<typeof setInterval>>(undefined);

    useEffect(() => {
        const start = Date.now();
        intervalRef.current = setInterval(() => {
            setUptime(Math.floor((Date.now() - start) / 1000));
        }, 1000);
        return () => clearInterval(intervalRef.current);
    }, []);

    useEffect(() => {
        const fetchCount = () => {
            getRecentEvents(500, source)
                .then((data) => setEventCount(data.length))
                .catch(() => {});
        };
        fetchCount();
        const id = setInterval(fetchCount, 15000);
        return () => clearInterval(id);
    }, [source]);

    const fmtUptime = (s: number) => {
        const h = Math.floor(s / 3600);
        const m = Math.floor((s % 3600) / 60);
        const sec = s % 60;
        return h > 0 ? `${h}h ${m}m` : m > 0 ? `${m}m ${sec}s` : `${sec}s`;
    };

    return (
        <div className="conn-stats">
            <MiniWaveform />
            <div className="conn-stat">
                <span className="conn-stat-label">API</span>
                <span className={`conn-stat-value ${isUp ? 'green' : ''}`}>{isUp ? 'Healthy' : 'Down'}</span>
            </div>
            <div className="conn-stat">
                <span className="conn-stat-label">Events</span>
                <span className="conn-stat-value">{eventCount ?? '...'}</span>
            </div>
            <div className="conn-stat">
                <span className="conn-stat-label">Uptime</span>
                <span className="conn-stat-value green">{fmtUptime(uptime)}</span>
            </div>
        </div>
    );
}

function EventSummaryBar({
    aggregateId,
    sequence,
    totalEvents,
    source,
}: {
    aggregateId: string;
    sequence: number;
    totalEvents: number;
    source?: string | null;
}) {
    const { data: transitions } = useQuery({
        queryKey: ['transitions', aggregateId, source ?? 'default'],
        queryFn: () => getTransitions(aggregateId, source),
        staleTime: 30_000,
    });

    const transition = transitions?.find(t => t.event.sequenceNumber === sequence);
    if (!transition) return null;

    const { event, diff } = transition;
    const changeCount = Object.keys(diff).length;
    const stepIndex = transitions ? transitions.findIndex(t => t.event.sequenceNumber === sequence) + 1 : null;

    return (
        <div className="event-summary-bar">
            <div className="event-summary-left">
                <span className="event-summary-type">{event.eventType}</span>
                <span className="event-summary-meta">
                    seq #{sequence}
                    {stepIndex !== null && ` step ${stepIndex} of ${totalEvents}`}
                    {' '}
                    {parseEventTimestamp(event.timestamp).toLocaleTimeString()}
                    {source ? ` source ${source}` : ''}
                </span>
            </div>
            {changeCount > 0 && (
                <span className="event-summary-changes">
                    {changeCount} {changeCount === 1 ? 'field' : 'fields'} changed
                </span>
            )}
        </div>
    );
}

function SourceSelector({
    datasources,
    selectedSource,
    onChange,
}: {
    datasources: DatasourceSummary[];
    selectedSource: string;
    onChange: (value: string) => void;
}) {
    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <label style={{ fontSize: 12, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em' }}>
                Datasource
            </label>
            <select
                value={selectedSource}
                onChange={e => onChange(e.target.value)}
                style={{
                    background: 'rgba(13, 17, 35, 0.85)',
                    color: 'var(--text-primary)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    borderRadius: 10,
                    padding: '10px 12px',
                    fontFamily: 'var(--font-mono)',
                }}
            >
                <option value="">Auto (primary datasource)</option>
                {datasources.map(source => (
                    <option
                        key={source.id}
                        value={source.id}
                        disabled={!isSelectableDatasource(source.status)}
                    >
                        {source.id} [{source.status}]
                    </option>
                ))}
            </select>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {datasources.map(source => (
                    <span
                        key={source.id}
                        style={{
                            border: `1px solid ${statusTone(source.status)}55`,
                            color: statusTone(source.status),
                            padding: '4px 8px',
                            borderRadius: 999,
                            fontSize: 11,
                            fontFamily: 'var(--font-mono)',
                        }}
                    >
                        {source.id}: {source.status}
                    </span>
                ))}
            </div>
        </div>
    );
}

function PluginHealthPage({
    datasources,
    datasourceHealth,
    plugins,
}: {
    datasources: DatasourceSummary[];
    datasourceHealth: Array<DatasourceHealth | undefined>;
    plugins: PluginSummary[];
}) {
    return (
        <div style={{ display: 'grid', gap: 20 }}>
            <div className="card">
                <div className="card-title">Plugin Health</div>
                <p style={{ color: 'var(--text-muted)', fontSize: 13, marginTop: 0 }}>
                    Source and stream readiness is surfaced from the plugin manager so we can spot failed connectors before switching the UI over.
                </p>
            </div>

            <div className="card">
                <div className="card-title">Datasources</div>
                <div style={{ display: 'grid', gap: 12 }}>
                    {datasources.map((source, index) => {
                        const health = datasourceHealth[index];
                        const tone = statusTone(source.status);
                        return (
                            <div
                                key={source.id}
                                style={{
                                    border: '1px solid rgba(255,255,255,0.08)',
                                    borderLeft: `4px solid ${tone}`,
                                    borderRadius: 12,
                                    padding: 14,
                                    background: 'rgba(255,255,255,0.02)',
                                }}
                            >
                                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
                                    <strong>{source.displayName}</strong>
                                    <span style={{ color: tone, fontFamily: 'var(--font-mono)', fontSize: 12 }}>{source.status}</span>
                                </div>
                                <div style={{ color: 'var(--text-muted)', fontSize: 12, marginTop: 8 }}>{source.id}</div>
                                {health && (
                                    <div style={{ color: 'var(--text-muted)', fontSize: 12, marginTop: 8 }}>
                                        {health.health.message}
                                        {health.failureReason ? ` | ${health.failureReason}` : ''}
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            </div>

            <div className="card">
                <div className="card-title">All Plugins</div>
                <div style={{ display: 'grid', gap: 12 }}>
                    {plugins.map(plugin => (
                        <div
                            key={plugin.instanceId}
                            style={{
                                border: '1px solid rgba(255,255,255,0.08)',
                                borderRadius: 12,
                                padding: 14,
                                background: 'rgba(255,255,255,0.02)',
                            }}
                        >
                            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                                <strong>{plugin.displayName}</strong>
                                <span style={{ color: statusTone(plugin.lifecycle), fontFamily: 'var(--font-mono)', fontSize: 12 }}>
                                    {plugin.lifecycle}
                                </span>
                            </div>
                            <div style={{ color: 'var(--text-muted)', fontSize: 12, marginTop: 8 }}>
                                {plugin.instanceId} | {plugin.pluginType} | {plugin.typeId}
                            </div>
                            <div style={{ color: 'var(--text-muted)', fontSize: 12, marginTop: 8 }}>
                                {plugin.health.message}
                                {plugin.failureReason ? ` | ${plugin.failureReason}` : ''}
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}

export default function App() {
    const [selectedAggregate, setSelectedAggregate] = useState<string | null>(null);
    const [selectedSequence, setSelectedSequence] = useState<number | null>(null);
    const [activeTab, setActiveTab] = useState<TabId>('changes');
    const [selectedSource, setSelectedSource] = useState('');
    const [currentHash, setCurrentHash] = useState(window.location.hash || '');

    useEffect(() => {
        const handler = (e: Event) => {
            const tab = (e as CustomEvent<string>).detail as TabId;
            if (tab) setActiveTab(tab);
        };
        window.addEventListener('eventlens:switchtab', handler);
        return () => window.removeEventListener('eventlens:switchtab', handler);
    }, []);

    useEffect(() => {
        const syncHash = () => setCurrentHash(window.location.hash || '');
        window.addEventListener('hashchange', syncHash);
        return () => window.removeEventListener('hashchange', syncHash);
    }, []);

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        const aggregateId = params.get('aggregateId');
        const seq = params.get('seq');
        const tab = params.get('tab') as TabId | null;
        const source = params.get('source');
        if (aggregateId) setSelectedAggregate(aggregateId);
        if (seq !== null) {
            const n = Number(seq);
            if (!Number.isNaN(n)) setSelectedSequence(n);
        }
        if (tab && ['changes', 'before-after', 'raw'].includes(tab)) {
            setActiveTab(tab);
        }
        if (source) {
            setSelectedSource(source);
        }
    }, []);

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        if (selectedAggregate) {
            params.set('aggregateId', selectedAggregate);
        } else {
            params.delete('aggregateId');
        }
        if (selectedSequence != null) {
            params.set('seq', String(selectedSequence));
        } else {
            params.delete('seq');
        }
        params.set('tab', activeTab);
        if (selectedSource) {
            params.set('source', selectedSource);
        } else {
            params.delete('source');
        }
        const qs = params.toString();
        const newUrl = `${window.location.pathname}${qs ? `?${qs}` : ''}${window.location.hash}`;
        window.history.replaceState(null, '', newUrl);
    }, [selectedAggregate, selectedSequence, activeTab, selectedSource]);

    const { data: health } = useQuery({
        queryKey: ['health'],
        queryFn: getHealth,
        refetchInterval: 30_000,
    });

    const { data: datasources = [] } = useQuery({
        queryKey: ['datasources'],
        queryFn: getDatasources,
        staleTime: 10_000,
    });

    const { data: plugins = [] } = useQuery({
        queryKey: ['plugins'],
        queryFn: getPlugins,
        staleTime: 10_000,
    });

    const datasourceHealthQueries = useQueries({
        queries: datasources.map(source => ({
            queryKey: ['datasource-health', source.id],
            queryFn: () => getDatasourceHealth(source.id),
            staleTime: 10_000,
        })),
    });
    const datasourceHealth = datasourceHealthQueries.map(query => query.data);

    const isUp = health?.status === 'UP';

    const handleSelectAggregate = (id: string) => {
        setSelectedAggregate(id);
        setSelectedSequence(null);
    };

    const { data: transitions } = useQuery({
        queryKey: ['transitions', selectedAggregate, selectedSource || 'default'],
        queryFn: () => getTransitions(selectedAggregate!, selectedSource || null),
        enabled: !!selectedAggregate,
        staleTime: 30_000,
    });
    const totalEvents = transitions?.length ?? 0;

    const pluginView = currentHash === '#/plugins';

    return (
        <div className="app">
            <header className="app-header">
                <div className="brand">
                    <div className="brand-logo">
                        <GeometricLogo />
                    </div>
                    <div>
                        <div className="brand-name">EventLens</div>
                        <div className="brand-sub">Event Store Visual Debugger</div>
                    </div>
                </div>

                <div className="header-title">EventLens</div>

                <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
                    <ConnectionStats isUp={isUp} source={selectedSource || null} />
                    <div className="header-status">
                        <span className={`dot ${isUp ? 'dot-green' : 'dot-red'}`} />
                        <span className={`status-text ${isUp ? '' : 'offline'}`}>
                            {isUp ? 'Connected' : health?.status ?? 'Connecting'}
                        </span>
                    </div>
                </div>
            </header>

            <main className="app-main">
                {isDemoMode() && (
                    <div className="demo-banner" role="status">
                        Demo mode (frontend only): API calls are stubbed with sample data. Search{' '}
                        <code>{DEMO_AGGREGATE_ID}</code> or <code>demo</code> to load the sample aggregate.
                    </div>
                )}

                <div className="card" style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'center', flexWrap: 'wrap' }}>
                    <div>
                        <div className="card-title" style={{ marginBottom: 6 }}>Workspace</div>
                        <div style={{ color: 'var(--text-muted)', fontSize: 13 }}>
                            Switch datasource context without breaking the default single-source flow.
                        </div>
                    </div>
                    <div style={{ display: 'flex', gap: 10 }}>
                        <a href="#" style={{ color: pluginView ? 'var(--text-muted)' : 'var(--neon-cyan)' }}>Explorer</a>
                        <a href="#/plugins" style={{ color: pluginView ? 'var(--neon-cyan)' : 'var(--text-muted)' }}>Plugins</a>
                    </div>
                </div>

                {pluginView ? (
                    <PluginHealthPage
                        datasources={datasources}
                        datasourceHealth={datasourceHealth}
                        plugins={plugins}
                    />
                ) : (
                    <>
                        <div className="card card--dropdown-host">
                            <div className="card-title">Search Aggregates</div>
                            <SourceSelector
                                datasources={datasources}
                                selectedSource={selectedSource}
                                onChange={value => {
                                    setSelectedSource(value);
                                    setSelectedSequence(null);
                                }}
                            />
                            <div style={{ height: 12 }} />
                            <SearchBar onSelect={handleSelectAggregate} source={selectedSource || null} />
                            {selectedAggregate && (
                                <div style={{ marginTop: 10, fontSize: 12, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                                    Viewing: <span style={{ color: 'var(--neon-cyan)', textShadow: '0 0 6px rgba(0,240,255,0.3)' }}>{selectedAggregate}</span>
                                    {selectedSource ? <span> on {selectedSource}</span> : <span> on primary datasource</span>}
                                    <button
                                        onClick={() => setSelectedAggregate(null)}
                                        style={{ marginLeft: 12, background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer', fontFamily: 'var(--font-mono)' }}
                                    >&times; clear</button>
                                </div>
                            )}
                        </div>

                        {selectedAggregate && (
                            <Timeline
                                aggregateId={selectedAggregate}
                                selectedSequence={selectedSequence}
                                onSelectEvent={setSelectedSequence}
                                source={selectedSource || null}
                            />
                        )}

                        {selectedAggregate && selectedSequence !== null && (
                            <EventSummaryBar
                                aggregateId={selectedAggregate}
                                sequence={selectedSequence}
                                totalEvents={totalEvents}
                                source={selectedSource || null}
                            />
                        )}

                        {selectedAggregate && selectedSequence !== null && (
                            <StateViewer
                                aggregateId={selectedAggregate}
                                sequence={selectedSequence}
                                activeTab={activeTab}
                                onTabChange={setActiveTab}
                                source={selectedSource || null}
                            />
                        )}

                        <div className="bottom-grid">
                            <LiveStream />
                            <AnomalyPanel />
                        </div>
                    </>
                )}
            </main>

            <KeyboardHints />
        </div>
    );
}
