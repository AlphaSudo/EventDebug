import { useState, useEffect, useRef } from 'react';
import { LiveStreamUnavailableMessage, StoredEvent } from '../api/client';
import { demoLiveStreamSeed } from '../demo/demoData';
import { isDemoMode } from '../demo/demoMode';
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

/** Keep in sync with server WebSocket backfill size so the client buffer is not trimmed below backfill. */
const BACKFILL_CAP = 100;

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

export default function LiveStream({
    source,
    onSelectAggregate,
}: {
    source?: string | null;
    onSelectAggregate?: (aggregateId: string) => void;
}) {
    return <SourceAwareLiveStream source={source} onSelectAggregate={onSelectAggregate} />;
}

type LiveStreamMessage = StoredEvent | LiveStreamUnavailableMessage;

function isUnavailableMessage(message: LiveStreamMessage): message is LiveStreamUnavailableMessage {
    return 'type' in message && message.type === 'NO_LIVE_STREAM';
}

function buildSocketPath(source?: string | null) {
    if (!source) {
        return '/ws/live';
    }
    return `/ws/live?source=${encodeURIComponent(source)}`;
}

function SourceAwareLiveStream({
    source,
    onSelectAggregate,
}: {
    source?: string | null;
    onSelectAggregate?: (aggregateId: string) => void;
}) {
    const demo = isDemoMode();
    const [events, setEvents] = useState<StoredEvent[]>(() => (demo ? demoLiveStreamSeed() : []));
    const [paused, setPaused] = useState(false);
    const [unavailableSource, setUnavailableSource] = useState<string | null>(null);
    const scrollRef = useRef<HTMLDivElement>(null);
    const pausedRef = useRef(paused);
    pausedRef.current = paused;
    const { notify } = useToast();

    useEffect(() => {
        setUnavailableSource(null);
        setEvents(demo ? demoLiveStreamSeed() : []);
    }, [source, demo]);

    const wsStatus = useWebSocket<LiveStreamMessage>(
        buildSocketPath(source),
        message => {
            if (isUnavailableMessage(message)) {
                setUnavailableSource(message.source);
                setEvents([]);
                return;
            }
            setUnavailableSource(null);
            if (pausedRef.current) return;
            setEvents(prev => [...prev.slice(-(BACKFILL_CAP - 1)), message]);
        },
        { enabled: !demo }
    );

    const notifyRef = useRef(notify);
    notifyRef.current = notify;
    const disconnectCountRef = useRef(0);

    useEffect(() => {
        if (demo) return;
        if (wsStatus === 'disconnected') {
            disconnectCountRef.current++;
            if (disconnectCountRef.current <= 1) {
                notifyRef.current('Live stream disconnected. Retrying\u2026');
            }
        } else if (wsStatus === 'connected') {
            disconnectCountRef.current = 0;
        }
    }, [wsStatus, demo]);

    useEffect(() => {
        if (!paused && scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [events, paused]);

    // Space key shortcut wired from Timeline via custom event
    useEffect(() => {
        const handler = () => setPaused(p => !p);
        window.addEventListener('eventlens:togglestream', handler);
        return () => window.removeEventListener('eventlens:togglestream', handler);
    }, []);

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
                {unavailableSource && (
                    <div style={{ color: 'var(--text-muted)', padding: '20px 0', fontSize: 12, fontFamily: 'var(--font-mono)' }}>
                        Live stream not available for this source
                        {source ? ` (${source})` : unavailableSource ? ` (${unavailableSource})` : ''}.
                    </div>
                )}
                {events.length === 0 && (
                    <div style={{ color: 'var(--text-muted)', padding: '20px 0', fontSize: 12, fontFamily: 'var(--font-mono)' }}>
                        {unavailableSource
                            ? null
                            : demo
                                ? 'Demo stream (static sample events)'
                                : 'Waiting for events\u2026'}
                    </div>
                )}
                {events.map((e) => (
                    <div key={e.eventId} className={`event-row ${rowClass(e.eventType)}`}>
                        <span className="event-icon">{eventIcon(e.eventType)}</span>
                        <span className="event-time">{parseEventTimestamp(e.timestamp).toLocaleTimeString()}</span>
                        <span className={`event-type ${typeClass(e.eventType)}`}>{e.eventType}</span>
                        {onSelectAggregate ? (
                            <button
                                type="button"
                                className="event-agg event-agg-button"
                                onClick={() => onSelectAggregate(e.aggregateId)}
                            >
                                {e.aggregateId}
                            </button>
                        ) : (
                            <span className="event-agg">{e.aggregateId}</span>
                        )}
                    </div>
                ))}
            </div>
        </div>
    );
}
