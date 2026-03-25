import { useEffect, useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import SearchBar from './components/SearchBar';
import Timeline from './components/Timeline';
import StateViewer, { type TabId } from './components/StateViewer';
import LiveStream from './components/LiveStream';
import AnomalyPanel from './components/AnomalyPanel';
import KeyboardHints from './components/KeyboardHints';
import ReplayDebugger from './components/ReplayDebugger';
import StatisticsPanel from './components/StatisticsPanel';
import CommandPalette from './components/CommandPalette';
import KeyboardManager from './components/KeyboardManager';
import {
    getDatasourceHealth,
    getDatasources,
    getHealth,
    getPlugins,
    getTimeline,
    type DatasourceHealth,
    type DatasourceSummary,
    type PluginSummary,
} from './api/client';
import { isDemoMode } from './demo/demoMode';
import { useReplay } from './hooks/useReplay';

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
    return status.toLowerCase() === 'ready';
}

function PluginHealthPage({ datasources, datasourceHealth, plugins }: {
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
                                    <span className="plugin-pill" style={{ color: tone, borderColor: `${tone}55` }}>{source.status}</span>
                                </div>
                                <div className="plugin-card-meta">{source.id}</div>
                                {health && <div className="plugin-card-detail">{health.health.message}{health.failureReason ? ` | ${health.failureReason}` : ''}</div>}
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
                                <span className="plugin-pill" style={{ color: statusTone(plugin.lifecycle), borderColor: `${statusTone(plugin.lifecycle)}55` }}>{plugin.lifecycle}</span>
                            </div>
                            <div className="plugin-card-meta">{plugin.pluginType} | {plugin.typeId}</div>
                            <div className="plugin-card-meta">{plugin.instanceId}</div>
                            <div className="plugin-card-detail">{plugin.health.message}{plugin.failureReason ? ` | ${plugin.failureReason}` : ''}</div>
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
    const [compareSequence, setCompareSequence] = useState<number | null>(null);
    const [activeTab, setActiveTab] = useState<TabId>('changes');
    const [selectedSource, setSelectedSource] = useState('');
    const [currentHash, setCurrentHash] = useState(window.location.hash || '');
    const [workspaceDockOpen, setWorkspaceDockOpen] = useState(false);
    const [paletteOpen, setPaletteOpen] = useState(false);

    useEffect(() => {
        const syncHash = () => setCurrentHash(window.location.hash || '');
        window.addEventListener('hashchange', syncHash);
        return () => window.removeEventListener('hashchange', syncHash);
    }, []);

    useEffect(() => {
        const handler = (e: Event) => {
            const tab = (e as CustomEvent<string>).detail as TabId;
            if (tab) setActiveTab(tab);
        };
        window.addEventListener('eventlens:switchtab', handler);
        return () => window.removeEventListener('eventlens:switchtab', handler);
    }, []);

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        const aggregateId = params.get('aggregateId');
        const seq = params.get('seq');
        const compare = params.get('compare');
        const tab = params.get('tab') as TabId | null;
        const source = params.get('source');
        if (aggregateId) setSelectedAggregate(aggregateId);
        if (seq) setSelectedSequence(Number(seq));
        if (compare) setCompareSequence(Number(compare));
        if (tab && ['changes', 'before-after', 'raw'].includes(tab)) setActiveTab(tab);
        if (source) setSelectedSource(source);
    }, []);

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        if (selectedAggregate) params.set('aggregateId', selectedAggregate); else params.delete('aggregateId');
        if (selectedSequence != null) params.set('seq', String(selectedSequence)); else params.delete('seq');
        if (compareSequence != null) params.set('compare', String(compareSequence)); else params.delete('compare');
        params.set('tab', activeTab);
        if (selectedSource) params.set('source', selectedSource); else params.delete('source');
        const qs = params.toString();
        const newUrl = `${window.location.pathname}${qs ? `?${qs}` : ''}${window.location.hash}`;
        window.history.replaceState(null, '', newUrl);
    }, [activeTab, compareSequence, selectedAggregate, selectedSequence, selectedSource]);

    const { data: health } = useQuery({ queryKey: ['health'], queryFn: getHealth, refetchInterval: 30_000 });
    const { data: datasources = [] } = useQuery({ queryKey: ['datasources'], queryFn: getDatasources, staleTime: 10_000 });
    const { data: plugins = [] } = useQuery({ queryKey: ['plugins'], queryFn: getPlugins, staleTime: 10_000 });
    const datasourceHealthQueries = useQueries({
        queries: datasources.map(source => ({
            queryKey: ['datasource-health', source.id],
            queryFn: () => getDatasourceHealth(source.id),
            staleTime: 10_000,
        })),
    });
    const datasourceHealth = datasourceHealthQueries.map(query => query.data);
    const { data: transitions = [] } = useReplay(selectedAggregate ?? '', selectedSource || null);

    const { data: timelineSummary } = useQuery({
        queryKey: ['timeline-summary', selectedAggregate, selectedSource || 'default'],
        queryFn: () => getTimeline(selectedAggregate!, 500, 0, selectedSource || null, 'metadata'),
        enabled: !!selectedAggregate,
        staleTime: 30_000,
    });

    const isUp = health?.status === 'UP';
    const pluginView = currentHash === '#/plugins';
    const statsView = currentHash === '#/stats';
    const healthySources = datasources.filter(source => isHealthyStatus(source.status)).length;
    const healthyPlugins = plugins.filter(plugin => isHealthyStatus(plugin.lifecycle)).length;
    const issueCount = (datasources.length - healthySources) + (plugins.length - healthyPlugins);

    const openAggregate = (id: string) => {
        setSelectedAggregate(id);
        setSelectedSequence(null);
        setCompareSequence(null);
        window.location.hash = '#/timeline';
    };

    return (
        <div className="app">
            <KeyboardManager paletteOpen={paletteOpen} onOpenPalette={() => setPaletteOpen(true)} onClosePalette={() => setPaletteOpen(false)} />
            <CommandPalette
                open={paletteOpen}
                selectedSource={selectedSource || null}
                onClose={() => setPaletteOpen(false)}
                onSelectAggregate={openAggregate}
                onOpenStats={() => { window.location.hash = '#/stats'; }}
                onOpenPlugins={() => { window.location.hash = '#/plugins'; }}
            />
            <header className="app-header">
                <div className="brand">
                    <div>
                        <div className="brand-name">EventLens</div>
                        <div className="brand-sub">Event Store Visual Debugger</div>
                    </div>
                </div>
                <div className="header-center">
                    {isDemoMode() && <div className="header-demo-pill" role="status">Demo mode</div>}
                    <div className="header-title">EventLens</div>
                </div>
                <div className="header-actions">
                    <div className="header-status">
                        <span className={`dot ${isUp ? 'dot-green' : 'dot-red'}`} />
                        <span className={`status-text ${isUp ? '' : 'offline'}`}>{isUp ? 'Connected' : health?.status ?? 'Connecting'}</span>
                    </div>
                </div>
            </header>

            <aside className={`workspace-dock${workspaceDockOpen ? ' workspace-dock--open' : ''}`} aria-label="Workspace">
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
                                setCompareSequence(null);
                            }}
                        >
                            <option value="">Auto (primary datasource)</option>
                            {datasources.map(source => (
                                <option key={source.id} value={source.id} disabled={!isSelectableDatasource(source.status)}>{source.id} [{source.status}]</option>
                            ))}
                        </select>
                    </label>
                    <div className="workspace-sidebar-kpis">
                        <div className="workspace-kpi-row"><span>Datasources Healthy</span><strong>{healthySources}/{datasources.length || 0}</strong></div>
                        <div className="workspace-kpi-row"><span>Plugins Healthy</span><strong>{healthyPlugins}/{plugins.length || 0}</strong></div>
                        <div className="workspace-kpi-row"><span>Issues</span><strong>{issueCount}</strong></div>
                    </div>
                </div>
                <button type="button" className="workspace-dock-handle" onClick={() => setWorkspaceDockOpen(o => !o)} aria-expanded={workspaceDockOpen} aria-controls="workspace-dock-panel">
                    <span className="workspace-dock-chevron" aria-hidden>{workspaceDockOpen ? '>' : '<'}</span>
                </button>
            </aside>

            <main className="app-main" role="main">
                <div className="workspace-content">
                    {!pluginView && !statsView && (
                        <div className="card search-panel card--dropdown-host">
                            <label className="control-field-label" htmlFor="aggregate-search">Search Aggregates</label>
                            <SearchBar onSelect={openAggregate} source={selectedSource || null} />
                            {selectedAggregate && (
                                <div className="selection-summary">
                                    Viewing: <span style={{ color: 'var(--neon-cyan)' }}>{selectedAggregate}</span>
                                    {selectedSource ? <span> on {selectedSource}</span> : <span> on primary datasource</span>}
                                    {compareSequence != null && <span> comparing with seq #{compareSequence}</span>}
                                    <button className="selection-clear-btn" onClick={() => { setSelectedAggregate(null); setSelectedSequence(null); setCompareSequence(null); }}>&times; clear</button>
                                </div>
                            )}
                        </div>
                    )}

                    {pluginView ? (
                        <PluginHealthPage datasources={datasources} datasourceHealth={datasourceHealth} plugins={plugins} />
                    ) : statsView ? (
                        <StatisticsPanel source={selectedSource || null} />
                    ) : (
                        <>
                            {selectedAggregate && (
                                <Timeline
                                    aggregateId={selectedAggregate}
                                    selectedSequence={selectedSequence}
                                    compareSequence={compareSequence}
                                    onSelectEvent={setSelectedSequence}
                                    onSelectCompare={setCompareSequence}
                                    source={selectedSource || null}
                                />
                            )}
                            {selectedAggregate && selectedSequence !== null && (
                                <StateViewer
                                    aggregateId={selectedAggregate}
                                    sequence={selectedSequence}
                                    compareSequence={compareSequence}
                                    activeTab={activeTab}
                                    onTabChange={setActiveTab}
                                    source={selectedSource || null}
                                />
                            )}
                            {selectedAggregate && transitions.length > 0 && (
                                <ReplayDebugger transitions={transitions} selectedSequence={selectedSequence} onSelectSequence={setSelectedSequence} />
                            )}
                            <div className="bottom-grid">
                                <LiveStream source={selectedSource || null} />
                                <AnomalyPanel source={selectedSource || null} />
                            </div>
                            {selectedAggregate && timelineSummary && (
                                <div className="card">
                                    <div className="card-title">Selection Summary</div>
                                    <p style={{ color: 'var(--text-secondary)' }}>
                                        {timelineSummary.totalEvents} events available for this aggregate.
                                        Use Shift+Click in the timeline to compare two points in time, or open the command palette with Ctrl/Cmd+K.
                                    </p>
                                </div>
                            )}
                        </>
                    )}
                </div>
            </main>
            <KeyboardHints />
        </div>
    );
}
