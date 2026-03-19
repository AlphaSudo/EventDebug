import { useReplay } from '../hooks/useReplay';
import { parseEventTimestamp } from '../utils/time';
import StateDiff from './StateDiff';
import { useState } from 'react';

interface Props {
    aggregateId: string;
    sequence: number;
}

export default function StateViewer({ aggregateId, sequence }: Props) {
    const { data: transitions, isLoading } = useReplay(aggregateId);

    if (isLoading) {
        return (
            <div className="card">
                <div className="card-title">🔬 State at Event</div>
                <div className="skeleton" style={{ height: 120 }} />
            </div>
        );
    }

    const transition = transitions?.find(t => t.event.sequenceNumber === sequence);
    if (!transition) return null;

    const { event, stateBefore, stateAfter, diff } = transition;
    let metadata: Record<string, string> = {};
    try { metadata = JSON.parse(event.metadata || '{}'); } catch { /* empty */ }

    const hasDiff = Object.keys(diff).length > 0;

    const JsonBlock = ({ label, value }: { label: string; value: unknown }) => {
        const [expanded, setExpanded] = useState(false);
        const text = JSON.stringify(value, null, 2);
        const lines = text.split('\n');
        const MAX_LINES = 40;
        const display = expanded || lines.length <= MAX_LINES
            ? text
            : [...lines.slice(0, MAX_LINES), '… (collapsed)'].join('\n');
        return (
            <div className="state-panel">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <h4>{label}</h4>
                    {lines.length > MAX_LINES && (
                        <button
                            className="link-button"
                            type="button"
                            onClick={() => setExpanded(e => !e)}
                        >
                            {expanded ? 'Collapse' : 'Expand'}
                        </button>
                    )}
                </div>
                <pre className="json-block">{display}</pre>
            </div>
        );
    };

    return (
        <div className="card">
            <div className="card-title">
                🔬 State at Event #{event.sequenceNumber}
                <span style={{ color: 'var(--accent-blue)', background: 'var(--accent-blue-dim)', padding: '2px 8px', borderRadius: 4 }}>
                    {event.eventType}
                </span>
            </div>

            {/* Before / After */}
            <div className="state-grid">
                <JsonBlock label="BEFORE" value={stateBefore} />
                <JsonBlock label="AFTER" value={stateAfter} />
            </div>

            {/* Field-level diff */}
            {hasDiff && <StateDiff diff={diff} />}

            {/* Metadata */}
            <div className="event-meta">
                <span>🕐 {parseEventTimestamp(event.timestamp).toLocaleString()}</span>
                <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>ID: {event.eventId.slice(0, 8)}…</span>
                {metadata.correlationId && <span>🔗 {metadata.correlationId}</span>}
                {metadata.userId && <span>👤 {metadata.userId}</span>}
            </div>

            {/* Actions */}
            <button
                className="copy-btn"
                onClick={() => navigator.clipboard.writeText(JSON.stringify(event, null, 2))}
            >
                📋 Copy Event JSON
            </button>
        </div>
    );
}
