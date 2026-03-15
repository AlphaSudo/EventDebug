import { useState, useEffect, useRef } from 'react';
import SearchBar from './components/SearchBar';
import Timeline from './components/Timeline';
import StateViewer from './components/StateViewer';
import LiveStream from './components/LiveStream';
import AnomalyPanel from './components/AnomalyPanel';
import { useQuery } from '@tanstack/react-query';
import { getHealth, getRecentEvents } from './api/client';

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

function ConnectionStats({ isUp }: { isUp: boolean }) {
    const [uptime, setUptime] = useState(0);
    const [eventCount, setEventCount] = useState<number | null>(null);
    const intervalRef = useRef<ReturnType<typeof setInterval>>();

    useEffect(() => {
        const start = Date.now();
        intervalRef.current = setInterval(() => {
            setUptime(Math.floor((Date.now() - start) / 1000));
        }, 1000);
        return () => clearInterval(intervalRef.current);
    }, []);

    useEffect(() => {
        const fetchCount = () => {
            getRecentEvents(500)
                .then((data) => setEventCount(data.length))
                .catch(() => {});
        };
        fetchCount();
        const id = setInterval(fetchCount, 15000);
        return () => clearInterval(id);
    }, []);

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

export default function App() {
    const [selectedAggregate, setSelectedAggregate] = useState<string | null>(null);
    const [selectedSequence, setSelectedSequence] = useState<number | null>(null);

    const { data: health } = useQuery({
        queryKey: ['health'],
        queryFn: getHealth,
        refetchInterval: 30_000,
    });

    const isUp = health?.status === 'UP';

    const handleSelectAggregate = (id: string) => {
        setSelectedAggregate(id);
        setSelectedSequence(null);
    };

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
                    <ConnectionStats isUp={isUp} />
                    <div className="header-status">
                        <span className={`dot ${isUp ? 'dot-green' : 'dot-red'}`} />
                        <span className={`status-text ${isUp ? '' : 'offline'}`}>
                            {isUp ? 'Connected' : health?.status ?? 'Connecting'}
                        </span>
                    </div>
                </div>
            </header>

            <main className="app-main">
                <div className="card">
                    <div className="card-title">&#x26A1; Search Aggregates</div>
                    <SearchBar onSelect={handleSelectAggregate} />
                    {selectedAggregate && (
                        <div style={{ marginTop: 10, fontSize: 12, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                            Viewing: <span style={{ color: 'var(--neon-cyan)', textShadow: '0 0 6px rgba(0,240,255,0.3)' }}>{selectedAggregate}</span>
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
                    />
                )}

                {selectedAggregate && selectedSequence !== null && (
                    <StateViewer
                        aggregateId={selectedAggregate}
                        sequence={selectedSequence}
                    />
                )}

                <div className="bottom-grid">
                    <LiveStream />
                    <AnomalyPanel />
                </div>
            </main>
        </div>
    );
}
