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
    getTimeline,
    getTransitions,
    type DatasourceHealth,
    type DatasourceSummary,
    type PluginSummary,
} from './api/client';
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

function isHealthyStatus(status: string) {
    const normalized = status.toLowerCase();
    return normalized === 'ready' || normalized === 'up';
}

function isSelectableDatasource(status: string) {
    const normalized = status.toLowerCase();
    return normalized === 'ready';
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
        if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
        if (m > 0) return `${String(m).padStart(2, '0')}m ${String(sec).padStart(2, '0')}s`;
        return `${String(sec).padStart(2, '0')}s`;
    };

    return (
        <div className="conn-stats">
            <MiniWaveform />
            <div className="conn-stat">
                <span className="conn-stat-label">API</span>
                <span className={`conn-stat-value ${isUp ? 'green' : ''}`}>{isUp ? 'Healthy' : 'Down'}</span>
            </div>
            <div className="conn-stat conn-stat--metric">
                <span className="conn-stat-label">Events</span>
                <span className="conn-stat-value">{eventCount ?? '...'}</span>
            </div>
            <div className="conn-stat conn-stat--uptime">
                <span className="conn-stat-label">Uptime</span>
                <span className="conn-stat-value green conn-stat-value--uptime">{fmtUptime(uptime)}</span>
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
        <div className="plugin-dashboard">
            <div className="card">
                <div className="card-title">Datasources</div>
                <div className="plugin-cards-grid">
                    {datasources.map((source, index) => {
                        const health = datasourceHealth[index];
                        const tone = statusTone(source.status);
                        return (
                            <article key={source.id} className="plugin-card plugin-card--interactive" style={{ borderLeft: `3px solid ${tone}` }}>
                                <div className="plugin-card-head">
                                    <strong>{source.displayName}</strong>
                                    <span className="plugin-pill" style={{ color: tone, borderColor: `${tone}55` }}>
                                        {source.status}
                                    </span>
                                </div>
                                <div className="plugin-card-meta">{source.id}</div>
                                {health && (
                                    <div className="plugin-card-detail">
                                        {health.health.message}
                                        {health.failureReason ? ` | ${health.failureReason}` : ''}
                                    </div>
                                )}
                            </article>
                        );
                    })}
                </div>
            </div>

            <div className="card">
                <div className="card-title">All Plugins</div>
                <div className="plugin-cards-grid plugin-cards-grid--dense">
                    {plugins.map(plugin => (
                        <article key={plugin.instanceId} className="plugin-card plugin-card--interactive">
                            <div className="plugin-card-head">
                                <strong>{plugin.displayName}</strong>
                                <span className="plugin-pill" style={{ color: statusTone(plugin.lifecycle), borderColor: `${statusTone(plugin.lifecycle)}55` }}>
                                    {plugin.lifecycle}
                                </span>
                            </div>
                            <div className="plugin-card-meta">
                                {plugin.pluginType} | {plugin.typeId}
                            </div>
                            <div className="plugin-card-meta">{plugin.instanceId}</div>
                            <div className="plugin-card-detail">
                                {plugin.health.message}
                                {plugin.failureReason ? ` | ${plugin.failureReason}` : ''}
                            </div>
                        </article>
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
    const [workspaceDockOpen, setWorkspaceDockOpen] = useState(false);

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

    const { data: timelineSummary } = useQuery({
        queryKey: ['timeline-summary', selectedAggregate, selectedSource || 'default'],
        queryFn: () => getTimeline(selectedAggregate!, 500, 0, selectedSource || null, 'metadata'),
        enabled: !!selectedAggregate,
        staleTime: 30_000,
    });
    const totalEvents = timelineSummary?.totalEvents ?? 0;

    const pluginView = currentHash === '#/plugins';
    const healthySources = datasources.filter(source => isHealthyStatus(source.status)).length;
    const healthyPlugins = plugins.filter(plugin => isHealthyStatus(plugin.lifecycle)).length;
    const issueCount = (datasources.length - healthySources) + (plugins.length - healthyPlugins);

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

                <div className="header-center">
                    {isDemoMode() && (
                        <div className="header-demo-pill" role="status">
                            Demo mode
                        </div>
                    )}
                    <div className="header-title">EventLens</div>
                </div>

                <div className="header-actions">
                    <ConnectionStats isUp={isUp} source={selectedSource || null} />
                    <div className="header-status">
                        <span className={`dot ${isUp ? 'dot-green' : 'dot-red'}`} />
                        <span className={`status-text ${isUp ? '' : 'offline'}`}>
                            {isUp ? 'Connected' : health?.status ?? 'Connecting'}
                        </span>
                    </div>
                </div>
            </header>

            <aside
                className={`workspace-dock${workspaceDockOpen ? ' workspace-dock--open' : ''}`}
                aria-label="Workspace"
            >
                <div className="workspace-dock-panel" id="workspace-dock-panel" hidden={!workspaceDockOpen}>
                    <div className="workspace-dock-title">Workspace</div>
                    <label className="workspace-datasource">
                        <span className="workspace-datasource-label">Datasource</span>
                        <select
                            id="workspace-datasource-select"
                            className="workspace-datasource-select"
                            value={selectedSource}
                            onChange={e => {
                                setSelectedSource(e.target.value);
                                setSelectedSequence(null);
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
                    </label>
                    <div className="workspace-sidebar-kpis">
                        <div className="workspace-kpi-row">
                            <span>Datasources Healthy</span>
                            <strong>{healthySources}/{datasources.length || 0}</strong>
                        </div>
                        <div className="workspace-kpi-row">
                            <span>Plugins Healthy</span>
                            <strong>{healthyPlugins}/{plugins.length || 0}</strong>
                        </div>
                        <div className="workspace-kpi-row">
                            <span>Issues</span>
                            <strong>{issueCount}</strong>
                        </div>
                    </div>
                    <div className="workspace-sidebar-links">
                        <span>Datasources</span>
                        <span>All Plugins</span>
                    </div>
                </div>
                <button
                    type="button"
                    className="workspace-dock-handle"
                    onClick={() => setWorkspaceDockOpen(o => !o)}
                    aria-expanded={workspaceDockOpen}
                    aria-controls="workspace-dock-panel"
                    aria-label={workspaceDockOpen ? 'Collapse workspace' : 'Expand workspace'}
                    title={workspaceDockOpen ? 'Collapse workspace' : 'Expand workspace'}
                >
                    <span className="workspace-dock-chevron" aria-hidden>
                        {workspaceDockOpen ? '›' : '‹'}
                    </span>
                </button>
            </aside>

            {workspaceDockOpen && (
                <button
                    type="button"
                    className="workspace-dock-scrim"
                    aria-label="Close workspace panel"
                    onClick={() => setWorkspaceDockOpen(false)}
                />
            )}

            <main className="app-main">
                <div className="workspace-content">
                    {!pluginView && (
                        <div className="card search-panel card--dropdown-host">
                            <label className="control-field-label" htmlFor="aggregate-search">Search Aggregates</label>
                            <SearchBar onSelect={handleSelectAggregate} source={selectedSource || null} />
                            {selectedAggregate && (
                                <div className="selection-summary">
                                    Viewing: <span style={{ color: 'var(--neon-cyan)', textShadow: '0 0 6px rgba(0,240,255,0.3)' }}>{selectedAggregate}</span>
                                    {selectedSource ? <span> on {selectedSource}</span> : <span> on primary datasource</span>}
                                    <button className="selection-clear-btn" onClick={() => setSelectedAggregate(null)}>
                                        &times; clear
                                    </button>
                                </div>
                            )}
                        </div>
                    )}

                    {pluginView ? (
                            <PluginHealthPage
                                datasources={datasources}
                                datasourceHealth={datasourceHealth}
                                plugins={plugins}
                            />
                        ) : (
                            <>
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
                            <LiveStream source={selectedSource || null} />
                            <AnomalyPanel source={selectedSource || null} />
                        </div>
                    </>
                )}
                </div>
            </main>

            <KeyboardHints />
        </div>
    );
}
