import { useState, useEffect, useRef } from 'react';
import { StoredEvent } from '../api/client';
import { useWebSocket } from '../hooks/useWebSocket';
import { useToast } from './ToastProvider';

function typeClass(t: string): string {
    const l = t.toLowerCase();
    if (l.includes('created')) return 'type-created';
    if (l.includes('deleted') || l.includes('closed')) return 'type-deleted';
    if (l.includes('transfer')) return 'type-transfer';
    return 'type-default';
}

export default function LiveStream() {
    const [events, setEvents] = useState<StoredEvent[]>([]);
    const [paused, setPaused] = useState(false);
    const [wsStatus, setWsStatus] = useState<'connecting' | 'connected' | 'disconnected'>('connecting');
    const scrollRef = useRef<HTMLDivElement>(null);
    const pausedRef = useRef(paused);
    pausedRef.current = paused;
    const { notify } = useToast();

    useWebSocket<StoredEvent>('/ws/live', event => {
        if (pausedRef.current) return;
        setEvents(prev => [...prev.slice(-99), event]);
    });

    useEffect(() => {
        if (wsStatus === 'disconnected') {
            notify('Live stream disconnected. Retrying…');
        }
    }, [wsStatus, notify]);

    // Auto-scroll to bottom
    useEffect(() => {
        if (!paused && scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [events, paused]);

    return (
        <div className="card">
            <div className="live-header">
                <div className="card-title" style={{ marginBottom: 0 }}>
                    📡 Live Event Stream
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <div className="live-indicator">
                        <span className={`dot ${wsStatus === 'connected' ? 'dot-green' : wsStatus === 'connecting' ? 'dot-yellow' : 'dot-red'}`} />
                        <span style={{ color: 'var(--text-muted)', fontSize: 11 }}>
                            {wsStatus === 'connected' ? (paused ? 'Paused' : 'Live') : wsStatus}
                        </span>
                    </div>
                    <button className="pause-btn" onClick={() => setPaused(!paused)}>
                        {paused ? '▶ Resume' : '⏸ Pause'}
                    </button>
                </div>
            </div>

            <div className="event-stream" ref={scrollRef}>
                {events.length === 0 && (
                    <div style={{ color: 'var(--text-muted)', padding: '20px 0', fontSize: 12 }}>
                        Waiting for events…
                    </div>
                )}
                {events.map((e, i) => (
                    <div key={i} className="event-row">
                        <span className="event-time">{new Date(e.timestamp).toLocaleTimeString()}</span>
                        <span className={`event-type ${typeClass(e.eventType)}`}>{e.eventType}</span>
                        <span className="event-agg">{e.aggregateId}</span>
                    </div>
                ))}
            </div>
        </div>
    );
}
