import JsonTreeView from './JsonTreeView';
import type { StateTransition } from '../api/client';

interface Props {
    transitions: StateTransition[];
    selectedSequence: number | null;
    onSelectSequence: (seq: number) => void;
    active?: boolean;
    onActivate?: () => void;
}

export default function ReplayDebugger({ transitions, selectedSequence, onSelectSequence, active = false, onActivate }: Props) {
    if (!transitions.length) return null;
    const current = transitions.find(transition => transition.event.sequenceNumber === selectedSequence) ?? transitions[0];
    const currentIndex = transitions.findIndex(transition => transition.event.sequenceNumber === current.event.sequenceNumber);

    return (
        <section className="card replay-debugger" tabIndex={0} role="region" aria-label="Replay debugger" aria-current={active ? 'page' : undefined} onFocus={onActivate}>
            <div className="card-title">Replay Debugger</div>
            <div className="replay-toolbar">
                <button type="button" onClick={() => onSelectSequence(transitions[Math.max(currentIndex - 1, 0)].event.sequenceNumber)} title="Step backward" aria-label="Replay previous event">Previous</button>
                <div className="replay-position">Event {currentIndex + 1} of {transitions.length}</div>
                <button type="button" onClick={() => onSelectSequence(transitions[Math.min(currentIndex + 1, transitions.length - 1)].event.sequenceNumber)} title="Step forward" aria-label="Replay next event">Next</button>
            </div>
            <input
                type="range"
                min={0}
                max={Math.max(transitions.length - 1, 0)}
                value={currentIndex}
                onChange={e => onSelectSequence(transitions[Number(e.target.value)].event.sequenceNumber)}
                aria-label="Replay position"
            />
            <div className="state-panel replay-state">
                <h4>Current State</h4>
                <JsonTreeView value={current.stateAfter} changedKeys={new Set(Object.keys(current.diff))} />
            </div>
        </section>
    );
}
