import { useState, useEffect, useRef } from 'react';
import { StoredEvent } from '../api/client';
import { useWebSocket } from '../hooks/useWebSocket';
import { useToast } from './ToastProvider';
import { parseEventTimestamp } from '../utils/time';

// Generic event type styling — works for any domain (orders, tickets, accounts, etc.)
function typeClass(t: string): string {
    const l = t.toLowerCase();
    if (l.includes('deleted') || l.includes('closed') || l.includes('cancelled') || l.includes('rejected')) return 'type-deleted';
    if (l.includes('withdrawn') || l.includes('debit')) return 'type-withdrawn';
    if (l.includes('deposited') || l.includes('credit')) return 'type-deposited';
    if (l.includes('created') || l.includes('opened') || l.includes('placed') || l.includes('submitted')) return 'type-created';
    if (l.includes('completed') || l.includes('resolved') || l.includes('accepted') || l.includes('approved') || l.includes('assigned')) return 'type-completed';
    if (l.includes('failed') || l.includes('error')) return 'type-failed';
    if (l.includes('transfer')) return 'type-transfer';
    return 'type-default';
}

function rowClass(t: string): string {
    return typeClass(t);
}

function eventIcon(t: string): string {
    const l = t.toLowerCase();
    if (l.includes('deleted') || l.includes('closed') || l.includes('cancelled') || l.includes('rejected')) return '\u2716';
    if (l.includes('withdrawn') || l.includes('debit')) return '\u21A9';
    if (l.includes('deposited') || l.includes('credit')) return '\u21AA';
    if (l.includes('created') || l.includes('opened') || l.includes('placed') || l.includes('submitted')) return '\u2726';
    if (l.includes('completed') || l.includes('resolved') || l.includes('accepted') || l.includes('approved')) return '\u2714';
    if (l.includes('failed') || l.includes('error')) return '\u26A0';
    if (l.includes('transfer')) return '\u21C4';
    return '\u25C6';
}

export default function LiveStream() {
    const [events, setEvents] = useState<StoredEvent[]>([]);
    const [paused, setPaused] = useState(false);
    const scrollRef = useRef<HTMLDivElement>(null);
    const pausedRef = useRef(paused);
    pausedRef.current = paused;
    const { notify } = useToast();

    const wsStatus = useWebSocket<StoredEvent>('/ws/live', event => {
        if (pausedRef.current) return;
        setEvents(prev => [...prev.slice(-99), event]);
    });

    const notifyRef = useRef(notify);
    notifyRef.current = notify;
    const disconnectCountRef = useRef(0);

    useEffect(() => {
        if (wsStatus === 'disconnected') {
            disconnectCountRef.current++;
            if (disconnectCountRef.current <= 1) {
                notifyRef.current('Live stream disconnected. Retrying\u2026');
            }
        } else if (wsStatus === 'connected') {
            disconnectCountRef.current = 0;
        }
    }, [wsStatus]);

    useEffect(() => {
        if (!paused && scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [events, paused]);

    return (
        <div className="card">
            <div className="live-header">
                <div className="card-title" style={{ marginBottom: 0 }}>
                    &#x1F4E1; Live Event Stream
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <div className="live-indicator">
                        <span className={`dot ${wsStatus === 'connected' ? 'dot-green' : wsStatus === 'connecting' ? 'dot-yellow' : 'dot-red'}`} />
                        <span style={{ color: wsStatus === 'connected' ? 'var(--neon-green)' : 'var(--text-muted)', fontSize: 11 }}>
                            {wsStatus === 'connected' ? (paused ? 'Paused' : 'Live') : wsStatus}
                        </span>
                    </div>
                    <button className="pause-btn" onClick={() => setPaused(!paused)}>
                        {paused ? '\u25B6 Resume' : '\u23F8 Pause'}
                    </button>
                </div>
            </div>

            <div className="event-stream" ref={scrollRef}>
                {events.length === 0 && (
                    <div style={{ color: 'var(--text-muted)', padding: '20px 0', fontSize: 12, fontFamily: 'var(--font-mono)' }}>
                        Waiting for events&hellip;
                    </div>
                )}
                {events.map((e, i) => (
                    <div key={i} className={`event-row ${rowClass(e.eventType)}`}>
                        <span className="event-icon">{eventIcon(e.eventType)}</span>
                        <span className="event-time">{parseEventTimestamp(e.timestamp).toLocaleTimeString()}</span>
                        <span className={`event-type ${typeClass(e.eventType)}`}>{e.eventType}</span>
                        <span className="event-agg">{e.aggregateId}</span>
                    </div>
                ))}
            </div>
        </div>
    );
}
