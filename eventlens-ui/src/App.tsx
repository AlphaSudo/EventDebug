import { useState } from 'react';
import SearchBar from './components/SearchBar';
import Timeline from './components/Timeline';
import StateViewer from './components/StateViewer';
import LiveStream from './components/LiveStream';
import AnomalyPanel from './components/AnomalyPanel';
import { useQuery } from '@tanstack/react-query';
import { getHealth } from './api/client';

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
        setSelectedSequence(null); // Reset on new selection
    };

    return (
        <div className="app">
            {/* Header */}
            <header className="app-header">
                <div className="brand">
                    <span className="brand-icon">🔍</span>
                    <div>
                        <div className="brand-name">EventLens</div>
                        <div className="brand-sub">Event Store Visual Debugger</div>
                    </div>
                </div>
                <div className="header-status">
                    <span className={`dot ${isUp ? 'dot-green' : 'dot-red'}`} />
                    <span>{isUp ? 'Connected' : health?.status ?? 'Connecting…'}</span>
                </div>
            </header>

            {/* Main content */}
            <main className="app-main">
                {/* Search */}
                <div className="card">
                    <div className="card-title">⚡ Search Aggregates</div>
                    <SearchBar onSelect={handleSelectAggregate} />
                    {selectedAggregate && (
                        <div style={{ marginTop: 10, fontSize: 12, color: 'var(--text-muted)' }}>
                            Viewing: <span style={{ color: 'var(--accent-blue)', fontFamily: 'var(--font-mono)' }}>{selectedAggregate}</span>
                            <button
                                onClick={() => setSelectedAggregate(null)}
                                style={{ marginLeft: 12, background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}
                            >✕ clear</button>
                        </div>
                    )}
                </div>

                {/* Timeline */}
                {selectedAggregate && (
                    <Timeline
                        aggregateId={selectedAggregate}
                        selectedSequence={selectedSequence}
                        onSelectEvent={setSelectedSequence}
                    />
                )}

                {/* State Viewer */}
                {selectedAggregate && selectedSequence !== null && (
                    <StateViewer
                        aggregateId={selectedAggregate}
                        sequence={selectedSequence}
                    />
                )}

                {/* Bottom row */}
                <div className="bottom-grid">
                    <LiveStream />
                    <AnomalyPanel />
                </div>
            </main>
        </div>
    );
}
