import { useQuery } from '@tanstack/react-query';
import { getTransitions } from '../api/client';

interface Props {
    aggregateId: string;
    sequence: number;
}

export default function StateViewer({ aggregateId, sequence }: Props) {
    const { data: transitions } = useQuery({
        queryKey: ['transitions', aggregateId],
        queryFn: () => getTransitions(aggregateId),
    });

    const transition = transitions?.find(t => t.event.sequenceNumber === sequence);
    if (!transition) return null;

    const { event, stateBefore, stateAfter, diff } = transition;
    let metadata: Record<string, string> = {};
    try { metadata = JSON.parse(event.metadata || '{}'); } catch { /* empty */ }

    const hasDiff = Object.keys(diff).length > 0;

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
                <div className="state-panel before">
                    <h4>BEFORE</h4>
                    <pre className="json-block">{JSON.stringify(stateBefore, null, 2)}</pre>
                </div>
                <div className="state-panel after">
                    <h4>AFTER</h4>
                    <pre className="json-block">{JSON.stringify(stateAfter, null, 2)}</pre>
                </div>
            </div>

            {/* Field-level diff */}
            {hasDiff && (
                <div className="diff-list">
                    <div className="card-title" style={{ marginTop: 12, marginBottom: 8 }}>Changes</div>
                    {Object.entries(diff).map(([field, change]) => (
                        <div key={field} className="diff-row">
                            <span className="diff-field">{field}:</span>
                            <span className="diff-old">{JSON.stringify(change.oldValue) ?? 'undefined'}</span>
                            <span className="diff-arrow">→</span>
                            <span className="diff-new">{JSON.stringify(change.newValue) ?? 'undefined'}</span>
                        </div>
                    ))}
                </div>
            )}

            {/* Metadata */}
            <div className="event-meta">
                <span>🕐 {new Date(event.timestamp).toLocaleString()}</span>
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
