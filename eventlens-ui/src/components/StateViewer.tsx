import { useState } from 'react';
import { useReplay } from '../hooks/useReplay';
import { parseEventTimestamp } from '../utils/time';
import StateDiff from './StateDiff';
import JsonTreeView from './JsonTreeView';

interface Props {
    aggregateId: string;
    sequence: number;
    activeTab?: TabId;
    onTabChange?: (tab: TabId) => void;
}

export type TabId = 'summary' | 'changes' | 'before-after' | 'raw';

const TABS: { id: TabId; label: string; emoji: string }[] = [
    { id: 'summary', label: 'Summary', emoji: '📋' },
    { id: 'changes', label: 'Changes', emoji: '±' },
    { id: 'before-after', label: 'Before / After', emoji: '⇄' },
    { id: 'raw', label: 'Raw JSON', emoji: '{ }' },
];

export default function StateViewer({ aggregateId, sequence, activeTab: externalTab, onTabChange }: Props) {
    const { data: transitions, isLoading } = useReplay(aggregateId);
    const [localTab, setLocalTab] = useState<TabId>('summary');

    const activeTab = externalTab ?? localTab;
    const handleTab = (t: TabId) => {
        setLocalTab(t);
        onTabChange?.(t);
    };

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
    try {
        metadata = JSON.parse(event.metadata || '{}');
    } catch {
        /* empty */
    }

    const diffEntries = Object.entries(diff);
    const hasDiff = diffEntries.length > 0;
    const changedKeys = new Set(diffEntries.map(([k]) => k));

    return (
        <div className="card">
            <div className="card-title">
                🔬 State at Event #{event.sequenceNumber}
                <span
                    style={{
                        color: 'var(--accent-blue)',
                        background: 'var(--accent-blue-dim)',
                        padding: '2px 8px',
                        borderRadius: 4,
                        fontSize: 12,
                    }}
                >
                    {event.eventType}
                </span>
                {hasDiff && (
                    <span className="diff-count-badge">
                        {diffEntries.length} {diffEntries.length === 1 ? 'change' : 'changes'}
                    </span>
                )}
            </div>

            {/* Tab strip */}
            <div className="state-tabs" role="tablist">
                {TABS.map(t => (
                    <button
                        key={t.id}
                        type="button"
                        role="tab"
                        aria-selected={activeTab === t.id}
                        className={`state-tab ${activeTab === t.id ? 'active' : ''}`}
                        onClick={() => handleTab(t.id)}
                    >
                        <span className="state-tab-emoji" aria-hidden>{t.emoji}</span>
                        {t.label}
                    </button>
                ))}
            </div>

            {/* Tab content */}
            <div className="state-tab-content" role="tabpanel">

                {/* Summary tab — human-readable "what changed" */}
                {activeTab === 'summary' && (
                    <div className="summary-tab">
                        {hasDiff ? (
                            <div className="summary-changes">
                                <div className="summary-changes-header">What changed at this event</div>
                                {diffEntries.map(([field, change]) => (
                                    <div key={field} className="summary-change-row">
                                        <span className="summary-change-field">{field}</span>
                                        <span className="summary-change-old">{JSON.stringify(change.oldValue)}</span>
                                        <span className="summary-change-arrow">→</span>
                                        <span className="summary-change-new">{JSON.stringify(change.newValue)}</span>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <div className="summary-no-changes">
                                <span style={{ fontSize: 20 }}>✓</span>
                                No state changes — this event carried no diff
                            </div>
                        )}

                        {/* Event meta inline in summary */}
                        <div className="event-meta event-meta-bar" style={{ marginTop: 16 }}>
                            <span className="event-meta-time">🕐 {parseEventTimestamp(event.timestamp).toLocaleString()}</span>
                            <span className="event-meta-id" title={event.eventId}>ID: {event.eventId}</span>
                            {metadata.correlationId && <span className="event-meta-extra">🔗 {metadata.correlationId}</span>}
                            {metadata.userId && <span className="event-meta-extra">👤 {metadata.userId}</span>}
                        </div>
                    </div>
                )}

                {/* Changes tab — existing StateDiff */}
                {activeTab === 'changes' && (
                    <div>
                        {hasDiff ? (
                            <StateDiff diff={diff} />
                        ) : (
                            <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>No field changes at this event.</p>
                        )}
                    </div>
                )}

                {/* Before / After tab — JSON tree views */}
                {activeTab === 'before-after' && (
                    <div className="state-grid" style={{ marginTop: 12 }}>
                        <div className="state-panel state-panel-before">
                            <h4>Before</h4>
                            <JsonTreeView value={stateBefore} changedKeys={changedKeys} />
                        </div>
                        <div className="state-panel state-panel-after">
                            <h4>After</h4>
                            <JsonTreeView value={stateAfter} changedKeys={changedKeys} />
                        </div>
                    </div>
                )}

                {/* Raw JSON tab */}
                {activeTab === 'raw' && (
                    <div style={{ marginTop: 12 }}>
                        <div className="json-block" style={{ maxHeight: 340 }}>
                            {JSON.stringify(event, null, 2)}
                        </div>
                        <button
                            className="copy-btn"
                            type="button"
                            onClick={() => navigator.clipboard.writeText(JSON.stringify(event, null, 2))}
                        >
                            📋 Copy Event JSON
                        </button>
                    </div>
                )}
            </div>
        </div>
    );
}
