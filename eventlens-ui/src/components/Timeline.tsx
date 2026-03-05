import { useQuery } from '@tanstack/react-query';
import { getTransitions, StateTransition } from '../api/client';

interface Props {
    aggregateId: string;
    selectedSequence: number | null;
    onSelectEvent: (seq: number) => void;
}

function dotClass(eventType: string): string {
    const t = eventType.toLowerCase();
    if (t.includes('created') || t.includes('opened')) return 'created';
    if (t.includes('deleted') || t.includes('closed')) return 'deleted';
    if (t.includes('transfer')) return 'transfer';
    return 'default';
}

export default function Timeline({ aggregateId, selectedSequence, onSelectEvent }: Props) {
    const { data: transitions, isLoading } = useQuery({
        queryKey: ['transitions', aggregateId],
        queryFn: () => getTransitions(aggregateId),
    });

    if (isLoading) {
        return (
            <div className="card">
                <div className="card-title">⏱ Timeline</div>
                <div className="skeleton" style={{ height: 64 }} />
            </div>
        );
    }

    if (!transitions?.length) {
        return (
            <div className="card">
                <div className="card-title">⏱ Timeline</div>
                <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>No events found for this aggregate.</p>
            </div>
        );
    }

    const minSeq = transitions[0].event.sequenceNumber;
    const maxSeq = transitions[transitions.length - 1].event.sequenceNumber;

    return (
        <div className="card">
            <div className="card-title">
                ⏱ Timeline
                <span style={{ color: 'var(--accent-blue)', fontFamily: 'var(--font-mono)' }}>
                    {transitions.length} events
                </span>
            </div>

            {/* Dot rail */}
            <div className="timeline-dots">
                {transitions.map((t, i) => (
                    <div key={t.event.sequenceNumber} style={{ display: 'flex', alignItems: 'center' }}>
                        {i > 0 && <div className="timeline-connector" />}
                        <div
                            className={`timeline-dot ${dotClass(t.event.eventType)} ${selectedSequence === t.event.sequenceNumber ? 'active' : ''}`}
                            onClick={() => onSelectEvent(t.event.sequenceNumber)}
                            title={`#${t.event.sequenceNumber}: ${t.event.eventType}\n${new Date(t.event.timestamp).toLocaleString()}`}
                            role="button"
                            tabIndex={0}
                            onKeyDown={e => e.key === 'Enter' && onSelectEvent(t.event.sequenceNumber)}
                            aria-label={`Event ${t.event.sequenceNumber}: ${t.event.eventType}`}
                        />
                    </div>
                ))}
            </div>

            {/* Slider for time travel */}
            <input
                type="range"
                className="timeline-slider"
                min={minSeq}
                max={maxSeq}
                value={selectedSequence ?? maxSeq}
                onChange={e => onSelectEvent(Number(e.target.value))}
                aria-label="Timeline scrubber"
            />

            <div className="timeline-info">
                <span>Event #{minSeq}</span>
                <span>
                    {selectedSequence != null
                        ? `#${selectedSequence}: ${transitions.find(t => t.event.sequenceNumber === selectedSequence)?.event.eventType ?? ''}`
                        : 'Drag slider or click a dot to inspect state'}
                </span>
                <span>Event #{maxSeq}</span>
            </div>
        </div>
    );
}
