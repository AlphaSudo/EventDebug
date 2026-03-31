import { FormEvent, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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
import AdminConsole from './components/AdminConsole';
import SetupWizard from './components/SetupWizard';
import {
    buildOidcLoginUrl,
    getAuthSession,
    getDatasources,
    getHealth,
    getPlugins,
    getRecentEvents,
    getSetupStatus,
    getTimeline,
    loginWithBasicSession,
    logoutSession,
    setCsrfToken,
    type SetupApplyResponse,
} from './api/client';
import { isDemoMode } from './demo/demoMode';
import { useReplay } from './hooks/useReplay';

function isHealthyStatus(status: string) {
    const normalized = status.toLowerCase();
    return normalized === 'ready' || normalized === 'up';
}

function isSelectableDatasource(status: string) {
    return status.toLowerCase() === 'ready';
}

function MiniWaveform() {
    const bars = [6, 12, 8, 16, 10, 14, 7, 11, 15, 9];
    return (
        <div className="mini-wave" aria-hidden>
            {bars.map((height, index) => (
                <div
                    key={index}
                    className="mini-wave-bar"
                    style={{ height, animationDelay: `${index * 0.1}s` }}
                />
            ))}
        </div>
    );
}

function ConnectionStats({
    isUp,
    selectedSource,
    fallbackCount,
}: {
    isUp: boolean;
    selectedSource: string;
    fallbackCount: number | null;
}) {
    const [uptime, setUptime] = useState(0);
    const [eventCount, setEventCount] = useState<number | null>(fallbackCount);
    const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

    useEffect(() => {
        const start = Date.now();
        intervalRef.current = setInterval(() => {
            setUptime(Math.floor((Date.now() - start) / 1000));
        }, 1000);
        return () => {
            if (intervalRef.current) {
                clearInterval(intervalRef.current);
            }
        };
    }, []);

    useEffect(() => {
        setEventCount(fallbackCount);
    }, [fallbackCount]);

    useEffect(() => {
        let cancelled = false;

        const fetchCount = () => {
            getRecentEvents(500, selectedSource || null)
                .then(data => {
                    if (!cancelled) {
                        setEventCount(data.length);
                    }
                })
                .catch(() => {
                    if (!cancelled && fallbackCount != null) {
                        setEventCount(fallbackCount);
                    }
                });
        };

        fetchCount();
        const id = setInterval(fetchCount, 15_000);
        return () => {
            cancelled = true;
            clearInterval(id);
        };
    }, [fallbackCount, selectedSource]);

    const formatUptime = (seconds: number) => {
        const hours = Math.floor(seconds / 3600);
        const minutes = Math.floor((seconds % 3600) / 60);
        const secs = seconds % 60;
        return hours > 0 ? `${hours}h ${minutes}m` : minutes > 0 ? `${minutes}m ${secs}s` : `${secs}s`;
    };

    return (
        <div className="conn-stats" aria-label="Connection metrics">
            <MiniWaveform />
            <div className="conn-stat">
                <span className="conn-stat-label">API</span>
                <span className={`conn-stat-value ${isUp ? 'green' : 'amber'}`}>{isUp ? 'Healthy' : 'Down'}</span>
            </div>
            <div className="conn-stat conn-stat--metric">
                <span className="conn-stat-label">Events</span>
                <span className="conn-stat-value">{eventCount ?? '...'}</span>
            </div>
            <div className="conn-stat">
                <span className="conn-stat-label">Uptime</span>
                <span className="conn-stat-value green conn-stat-value--uptime">{formatUptime(uptime)}</span>
            </div>
        </div>
    );
}

export default function App() {
    const queryClient = useQueryClient();
    const [activePanel, setActivePanel] = useState<'state' | 'replay'>('state');
    const [selectedAggregate, setSelectedAggregate] = useState<string | null>(null);
    const [selectedSequence, setSelectedSequence] = useState<number | null>(null);
    const [compareSequence, setCompareSequence] = useState<number | null>(null);
    const [activeTab, setActiveTab] = useState<TabId>('changes');
    const [selectedSource, setSelectedSource] = useState('');
    const [currentHash, setCurrentHash] = useState(window.location.hash || '');
    const [workspaceDockOpen, setWorkspaceDockOpen] = useState(false);
    const [paletteOpen, setPaletteOpen] = useState(false);
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [authError, setAuthError] = useState<string | null>(null);
    const [isSubmittingLogin, setIsSubmittingLogin] = useState(false);
    const [isSubmittingLogout, setIsSubmittingLogout] = useState(false);
    const [oidcError, setOidcError] = useState<string | null>(null);
    const [setupApplied, setSetupApplied] = useState<SetupApplyResponse | null>(null);

    useEffect(() => {
        const syncHash = () => setCurrentHash(window.location.hash || '');
        window.addEventListener('hashchange', syncHash);
        return () => window.removeEventListener('hashchange', syncHash);
    }, []);

    useEffect(() => {
        if (currentHash === '#/plugins') {
            window.location.hash = '#/timeline';
        }
    }, [currentHash]);

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
        const authErrorParam = params.get('authError');
        const aggregateId = params.get('aggregateId');
        const seq = params.get('seq');
        const compare = params.get('compare');
        const tab = params.get('tab') as TabId | null;
        const panel = params.get('panel');
        const source = params.get('source');
        if (authErrorParam) {
            setOidcError(authErrorParam.replace(/_/g, ' '));
            params.delete('authError');
            const qs = params.toString();
            const newUrl = `${window.location.pathname}${qs ? `?${qs}` : ''}${window.location.hash || '#/timeline'}`;
            window.history.replaceState(null, '', newUrl);
        }
        if (aggregateId) setSelectedAggregate(aggregateId);
        if (seq) setSelectedSequence(Number(seq));
        if (compare) setCompareSequence(Number(compare));
        if (tab && ['changes', 'before-after', 'raw'].includes(tab)) setActiveTab(tab);
        if (panel === 'replay' || panel === 'state') setActivePanel(panel);
        if (source) setSelectedSource(source);
    }, []);

    useEffect(() => {
        const params = new URLSearchParams(window.location.search);
        if (selectedAggregate) params.set('aggregateId', selectedAggregate); else params.delete('aggregateId');
        if (selectedSequence != null) params.set('seq', String(selectedSequence)); else params.delete('seq');
        if (compareSequence != null) params.set('compare', String(compareSequence)); else params.delete('compare');
        params.set('tab', activeTab);
        params.set('panel', activePanel);
        if (selectedSource) params.set('source', selectedSource); else params.delete('source');
        const qs = params.toString();
        const newUrl = `${window.location.pathname}${qs ? `?${qs}` : ''}${window.location.hash}`;
        window.history.replaceState(null, '', newUrl);
    }, [activePanel, activeTab, compareSequence, selectedAggregate, selectedSequence, selectedSource]);

    const setupQuery = useQuery({
        queryKey: ['setup-status'],
        queryFn: getSetupStatus,
        retry: false,
        refetchOnWindowFocus: false,
    });
    const authQuery = useQuery({
        queryKey: ['auth-session'],
        queryFn: getAuthSession,
        retry: false,
        refetchOnWindowFocus: false,
        enabled: isDemoMode() || (setupQuery.isSuccess && !setupQuery.data?.setupRequired && !setupQuery.data?.restartRequired),
    });
    const isAuthenticated = isDemoMode() || authQuery.data?.authenticated === true;
    const setupReady = isDemoMode() || setupQuery.isSuccess || setupQuery.isError;
    const authReady = isDemoMode()
        || authQuery.isSuccess
        || authQuery.isError
        || !!setupQuery.data?.setupRequired
        || !!setupQuery.data?.restartRequired;

    useEffect(() => {
        if (isDemoMode()) {
            setCsrfToken(null);
            return;
        }
        setCsrfToken(authQuery.data?.authenticated ? (authQuery.data.csrfToken ?? null) : null);
    }, [authQuery.data]);

    const { data: health } = useQuery({
        queryKey: ['health'],
        queryFn: getHealth,
        refetchInterval: 30_000,
        enabled: isAuthenticated,
    });
    const { data: datasources = [] } = useQuery({
        queryKey: ['datasources'],
        queryFn: getDatasources,
        staleTime: 10_000,
        enabled: isAuthenticated,
    });
    const { data: plugins = [] } = useQuery({
        queryKey: ['plugins'],
        queryFn: getPlugins,
        staleTime: 10_000,
        enabled: isAuthenticated,
    });
    const { data: transitions = [] } = useReplay(selectedAggregate ?? '', selectedSource || null, isAuthenticated);

    const { data: timelineSummary } = useQuery({
        queryKey: ['timeline-summary', selectedAggregate, selectedSource || 'default'],
        queryFn: () => getTimeline(selectedAggregate!, 500, 0, selectedSource || null, 'metadata'),
        enabled: isAuthenticated && !!selectedAggregate,
        staleTime: 30_000,
    });

    const isUp = health?.status === 'UP';
    const statsView = currentHash === '#/stats';
    const adminView = currentHash === '#/admin';
    const healthySources = datasources.filter(source => isHealthyStatus(source.status)).length;
    const healthyPlugins = plugins.filter(plugin => isHealthyStatus(plugin.lifecycle)).length;
    const issueCount = (datasources.length - healthySources) + (plugins.length - healthyPlugins);

    const openAggregate = (id: string) => {
        setSelectedAggregate(id);
        setSelectedSequence(null);
        setCompareSequence(null);
        setActivePanel('state');
        window.location.hash = '#/timeline';
    };

    const openMainPage = () => {
        window.location.hash = '#/timeline';
    };

    const openAdminPage = () => {
        window.location.hash = '#/admin';
    };

    const handleLogin = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setAuthError(null);
        setOidcError(null);
        setIsSubmittingLogin(true);
        try {
            const session = await loginWithBasicSession(username, password, currentHash || '#/timeline');
            setPassword('');
            queryClient.setQueryData(['auth-session'], session);
            await queryClient.invalidateQueries();
            window.location.hash = session.returnHash || currentHash || '#/timeline';
        } catch {
            setAuthError('Login failed. Check your credentials and try again.');
        } finally {
            setIsSubmittingLogin(false);
        }
    };

    const handleLogout = async () => {
        setIsSubmittingLogout(true);
        setAuthError(null);
        setOidcError(null);
        try {
            await logoutSession();
            queryClient.setQueryData(['auth-session'], { authenticated: false });
            await queryClient.invalidateQueries();
            setPassword('');
            window.location.hash = '#/timeline';
        } finally {
            setIsSubmittingLogout(false);
        }
    };

    if (!setupReady) {
        return (
            <div className="app auth-shell">
                <main className="auth-screen" role="main" aria-label="Setup status">
                    <section className="auth-card">
                        <div className="auth-eyebrow">EventLens Setup</div>
                        <h1 className="auth-title">Checking instance state</h1>
                        <p className="auth-copy">
                            We are checking whether this EventLens instance still needs first-run security setup.
                        </p>
                    </section>
                </main>
            </div>
        );
    }

    if (setupQuery.data?.setupRequired && !setupApplied) {
        return <SetupWizard status={setupQuery.data} onApplied={setSetupApplied} />;
    }

    if (setupApplied || setupQuery.data?.restartRequired) {
        const setupResult = setupApplied ?? {
            saved: true,
            restartRequired: true,
            mode: 'saved',
            configPath: setupQuery.data?.configPath ?? 'eventlens.yaml',
        };
        return (
            <div className="app auth-shell">
                <main className="auth-screen" role="main" aria-label="Restart required">
                    <section className="auth-card">
                        <div className="auth-eyebrow">EventLens Setup</div>
                        <h1 className="auth-title">Restart required</h1>
                        <p className="auth-copy">
                            We saved the new security mode to <span className="setup-path">{setupResult.configPath}</span>.
                            Restart EventLens once so the new auth pipeline comes up cleanly.
                        </p>
                        <div className="setup-warning">
                            Saved mode: <strong>{setupResult.mode}</strong>. After restart, reload this page and continue with the normal sign-in flow.
                        </div>
                    </section>
                </main>
            </div>
        );
    }

    if (!authReady) {
        return (
            <div className="app auth-shell">
                <main className="auth-screen" role="main" aria-label="Session status">
                    <section className="auth-card">
                        <div className="auth-eyebrow">Security Session</div>
                        <h1 className="auth-title">Checking active session</h1>
                        <p className="auth-copy">
                            We are verifying whether this browser already has an active EventLens session.
                        </p>
                    </section>
                </main>
            </div>
        );
    }

    if (!isAuthenticated) {
        const provider = authQuery.data?.provider ?? 'basic';
        const showBasicLogin = authQuery.data?.basicLoginEnabled ?? provider !== 'oidc';
        return (
            <div className="app auth-shell">
                <main className="auth-screen" role="main" aria-label="Login">
                    <section className="auth-card">
                        <div className="auth-eyebrow">EventLens Security</div>
                        <h1 className="auth-title">{authQuery.isError ? 'Server unavailable' : 'Sign in to continue'}</h1>
                        <p className="auth-copy">
                            {authQuery.isError
                                ? 'We could not reach the EventLens API to check session state. Start the server or restore connectivity, then refresh this page.'
                                : provider === 'oidc'
                                    ? 'This deployment expects OpenID Connect sign-in and then creates a server-side browser session for EventLens.'
                                    : 'This v5 transition uses a server-side browser session. After login, we return you to the current workspace route.'}
                        </p>
                        {oidcError && <div className="auth-error" role="alert">OIDC sign-in failed: {oidcError}</div>}
                        {!authQuery.isError && provider === 'oidc' && (
                            <button
                                className="auth-submit auth-submit--secondary"
                                type="button"
                                onClick={() => window.location.assign(buildOidcLoginUrl(currentHash || '#/timeline'))}
                            >
                                Sign in with OpenID Connect
                            </button>
                        )}
                        {!authQuery.isError && showBasicLogin && (
                            <form className="auth-form" onSubmit={handleLogin}>
                                <label className="auth-field">
                                    <span className="auth-label">Username</span>
                                    <input
                                        className="auth-input"
                                        type="text"
                                        autoComplete="username"
                                        value={username}
                                        onChange={event => setUsername(event.target.value)}
                                        disabled={isSubmittingLogin}
                                        required
                                    />
                                </label>
                                <label className="auth-field">
                                    <span className="auth-label">Password</span>
                                    <input
                                        className="auth-input"
                                        type="password"
                                        autoComplete="current-password"
                                        value={password}
                                        onChange={event => setPassword(event.target.value)}
                                        disabled={isSubmittingLogin}
                                        required
                                    />
                                </label>
                                <div className="auth-meta">
                                    <span className="auth-route">Return route: {currentHash || '#/timeline'}</span>
                                </div>
                                {authError && <div className="auth-error" role="alert">{authError}</div>}
                                <button className="auth-submit" type="submit" disabled={isSubmittingLogin}>
                                    {isSubmittingLogin ? 'Signing in...' : 'Sign in'}
                                </button>
                            </form>
                        )}
                        {authQuery.isError && (
                            <div className="auth-error" role="alert">
                                Session check failed. Refresh after the API is available again.
                            </div>
                        )}
                    </section>
                </main>
            </div>
        );
    }

    return (
        <div className="app">
            <KeyboardManager paletteOpen={paletteOpen} onOpenPalette={() => setPaletteOpen(true)} onClosePalette={() => setPaletteOpen(false)} />
            <CommandPalette
                open={paletteOpen}
                selectedSource={selectedSource || null}
                onClose={() => setPaletteOpen(false)}
                onSelectAggregate={openAggregate}
                onOpenHome={openMainPage}
                onOpenStats={() => { window.location.hash = '#/stats'; }}
                onOpenAdmin={openAdminPage}
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
                    {authQuery.data?.principal && (
                        <div className="header-user" aria-label="Authenticated user">
                            <div className="header-user-meta">
                                <span className="header-user-label">Signed in</span>
                                <strong className="header-user-name">{authQuery.data.principal.displayName}</strong>
                            </div>
                            <button
                                className={`header-user-action ${adminView ? 'header-user-action--active' : ''}`}
                                type="button"
                                onClick={openAdminPage}
                            >
                                Admin
                            </button>
                            <button className="header-user-action" type="button" onClick={handleLogout} disabled={isSubmittingLogout}>
                                {isSubmittingLogout ? 'Signing out...' : 'Sign out'}
                            </button>
                        </div>
                    )}
                    <ConnectionStats isUp={isUp} selectedSource={selectedSource} fallbackCount={timelineSummary?.totalEvents ?? null} />
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
                <button
                    type="button"
                    className="workspace-dock-handle"
                    onClick={() => setWorkspaceDockOpen(o => !o)}
                    aria-expanded={workspaceDockOpen}
                    aria-controls="workspace-dock-panel"
                    aria-label={workspaceDockOpen ? 'Collapse workspace panel' : 'Expand workspace panel'}
                >
                    <span className="workspace-dock-chevron" aria-hidden>{workspaceDockOpen ? '>' : '<'}</span>
                </button>
            </aside>

            <main className="app-main" role="main" aria-label="EventLens workspace">
                <div className="workspace-content">
                    {!statsView && !adminView && (
                        <div className="card search-panel card--dropdown-host">
                            <label className="control-field-label" htmlFor="aggregate-search">Search Aggregates</label>
                            <SearchBar onSelect={openAggregate} source={selectedSource || null} selectedValue={selectedAggregate} />
                            {selectedAggregate && (
                                <div className="selection-summary">
                                    Viewing: <span style={{ color: 'var(--neon-cyan)' }}>{selectedAggregate}</span>
                                    {selectedSource ? <span> on {selectedSource}</span> : <span> on primary datasource</span>}
                                    {compareSequence != null && <span> comparing with seq #{compareSequence}</span>}
                                    <span className="sr-only" aria-live="polite">Current panel {activePanel}</span>
                                    <button className="selection-clear-btn" onClick={() => { setSelectedAggregate(null); setSelectedSequence(null); setCompareSequence(null); }}>&times; clear</button>
                                </div>
                            )}
                        </div>
                    )}

                    {statsView ? (
                        <StatisticsPanel source={selectedSource || null} onBack={openMainPage} />
                    ) : adminView ? (
                        <AdminConsole />
                    ) : (
                        <>
                            {selectedAggregate && (
                                <Timeline
                                    aggregateId={selectedAggregate}
                                    selectedSequence={selectedSequence}
                                    compareSequence={compareSequence}
                                    onSelectEvent={seq => {
                                        setSelectedSequence(seq);
                                        setActivePanel('state');
                                    }}
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
                                    onTabChange={tab => {
                                        setActiveTab(tab);
                                        setActivePanel('state');
                                    }}
                                    active={activePanel === 'state'}
                                    onActivate={() => setActivePanel('state')}
                                    source={selectedSource || null}
                                />
                            )}
                            {selectedAggregate && transitions.length > 0 && (
                                <ReplayDebugger
                                    transitions={transitions}
                                    selectedSequence={selectedSequence}
                                    compareSequence={compareSequence}
                                    onSelectSequence={seq => {
                                        setSelectedSequence(seq);
                                        setActivePanel('replay');
                                    }}
                                    onClearCompare={() => setCompareSequence(null)}
                                    active={activePanel === 'replay'}
                                    onActivate={() => setActivePanel('replay')}
                                />
                            )}
                            <div className="bottom-grid">
                                <LiveStream source={selectedSource || null} onSelectAggregate={openAggregate} />
                                <AnomalyPanel source={selectedSource || null} onSelectAggregate={openAggregate} />
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
