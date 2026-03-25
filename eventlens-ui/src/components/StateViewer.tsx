import { useMemo, useState } from 'react';
import { useReplay } from '../hooks/useReplay';
import { useJsonDiffWorker } from '../hooks/useJsonDiffWorker';
import StateDiff from './StateDiff';
import JsonTreeView from './JsonTreeView';

interface Props {
    aggregateId: string;
    sequence: number;
    compareSequence?: number | null;
    activeTab?: TabId;
    onTabChange?: (tab: TabId) => void;
    source?: string | null;
}

export type TabId = 'changes' | 'before-after' | 'raw';

const TABS: { id: TabId; label: string }[] = [
    { id: 'changes', label: 'Changes' },
    { id: 'before-after', label: 'Before / After' },
    { id: 'raw', label: 'Raw JSON' },
];

export default function StateViewer({ aggregateId, sequence, compareSequence, activeTab: externalTab, onTabChange, source }: Props) {
    const { data: transitions = [], isLoading } = useReplay(aggregateId, source);
    const [localTab, setLocalTab] = useState<TabId>('changes');

    const activeTab = externalTab ?? localTab;
    const handleTab = (tab: TabId) => {
        setLocalTab(tab);
        onTabChange?.(tab);
    };

    const primary = transitions.find(t => t.event.sequenceNumber === sequence) ?? null;
    const compare = compareSequence != null
        ? transitions.find(t => t.event.sequenceNumber === compareSequence) ?? null
        : null;
    const compareMode = primary != null && compare != null && primary.event.sequenceNumber !== compare.event.sequenceNumber;
    const leftState = compareMode ? compare.stateAfter : primary?.stateBefore;
    const rightState = primary?.stateAfter;
    const { patches, loading: diffLoading, durationMs } = useJsonDiffWorker(leftState, rightState, compareMode && !!leftState && !!rightState);

    const changedKeys = useMemo(() => {
        if (compareMode) {
            return new Set(patches.map(patch => patch.path.replace(/^\$\./, '').split('.')[0]));
        }
        return new Set(Object.keys(primary?.diff ?? {}));
    }, [compareMode, patches, primary?.diff]);

    if (isLoading) {
        return <div className="card"><div className="card-title">State</div><div className="skeleton" style={{ height: 160 }} /></div>;
    }
    if (!primary) {
        return null;
    }

    return (
        <div className="card">
            <div className="card-title">
                State at Event #{primary.event.sequenceNumber}
                <span className="diff-count-badge">{primary.event.eventType}</span>
                {compareMode && <span className="diff-count-badge">Compared with #{compare?.event.sequenceNumber}</span>}
                {compareMode && !diffLoading && <span className="diff-count-badge">Worker {durationMs.toFixed(1)}ms</span>}
            </div>
            <div className="state-tabs" role="tablist">
                {TABS.map(tab => (
                    <button
                        key={tab.id}
                        type="button"
                        role="tab"
                        aria-selected={activeTab === tab.id}
                        className={`state-tab ${activeTab === tab.id ? 'active' : ''}`}
                        onClick={() => handleTab(tab.id)}
                    >
                        {tab.label}
                    </button>
                ))}
            </div>
            <div className="state-tab-content" role="tabpanel">
                {activeTab === 'changes' && (
                    <div>
                        {compareMode ? (
                            diffLoading ? <div className="skeleton" style={{ height: 120 }} /> : <StateDiff patches={patches} title="Structural diff" />
                        ) : (
                            primary.diff && Object.keys(primary.diff).length > 0
                                ? <StateDiff diff={primary.diff} />
                                : <p style={{ color: 'var(--text-muted)', marginTop: 12, fontSize: 13 }}>No field changes at this event.</p>
                        )}
                    </div>
                )}
                {activeTab === 'before-after' && (
                    <div className="state-grid" style={{ marginTop: 12 }}>
                        <div className="state-panel state-panel-before">
                            <h4>{compareMode ? `Event #${compare?.event.sequenceNumber}` : 'Before'}</h4>
                            <JsonTreeView value={leftState ?? {}} changedKeys={changedKeys} />
                        </div>
                        <div className="state-panel state-panel-after">
                            <h4>{compareMode ? `Event #${primary.event.sequenceNumber}` : 'After'}</h4>
                            <JsonTreeView value={rightState ?? {}} changedKeys={changedKeys} />
                        </div>
                    </div>
                )}
                {activeTab === 'raw' && (
                    <div style={{ marginTop: 12 }}>
                        <div className="json-block" style={{ maxHeight: 340 }}>
                            {JSON.stringify(compareMode ? {
                                leftEvent: compare?.event,
                                rightEvent: primary.event,
                                leftState,
                                rightState,
                            } : primary.event, null, 2)}
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}
