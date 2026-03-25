import { useEffect, useState } from 'react';
import JsonTreeView from './JsonTreeView';
import type { StateTransition } from '../api/client';

type PendingReplayAction =
    | { type: 'select'; sequence: number }
    | { type: 'autoplay'; sequence: number };

const AUTO_PLAY_OPTIONS = [2, 3, 5, 10] as const;

interface Props {
    transitions: StateTransition[];
    selectedSequence: number | null;
    compareSequence?: number | null;
    onSelectSequence: (seq: number) => void;
    onClearCompare?: () => void;
    active?: boolean;
    onActivate?: () => void;
}

export default function ReplayDebugger({
    transitions,
    selectedSequence,
    compareSequence,
    onSelectSequence,
    onClearCompare,
    active = false,
    onActivate,
}: Props) {
    const [pendingAction, setPendingAction] = useState<PendingReplayAction | null>(null);
    const [autoPlaySeconds, setAutoPlaySeconds] = useState<(typeof AUTO_PLAY_OPTIONS)[number]>(3);
    const [isAutoPlaying, setIsAutoPlaying] = useState(false);

    useEffect(() => {
        if (compareSequence == null && pendingAction != null) {
            if (pendingAction.type === 'autoplay') {
                setIsAutoPlaying(true);
            }
            onSelectSequence(pendingAction.sequence);
            setPendingAction(null);
        }
    }, [compareSequence, onSelectSequence, pendingAction]);

    if (!transitions.length) return null;
    const current = transitions.find(transition => transition.event.sequenceNumber === selectedSequence) ?? transitions[0];
    const currentIndex = transitions.findIndex(transition => transition.event.sequenceNumber === current.event.sequenceNumber);
    const compareActive = compareSequence != null && compareSequence !== current.event.sequenceNumber;
    const atEnd = currentIndex >= transitions.length - 1;
    const atStart = currentIndex <= 0;

    useEffect(() => {
        if (compareActive && isAutoPlaying) {
            setIsAutoPlaying(false);
        }
    }, [compareActive, isAutoPlaying]);

    useEffect(() => {
        if (!isAutoPlaying || atEnd) {
            if (atEnd && isAutoPlaying) {
                setIsAutoPlaying(false);
            }
            return;
        }

        const id = window.setInterval(() => {
            const nextTransition = transitions[Math.min(currentIndex + 1, transitions.length - 1)];
            if (!nextTransition || nextTransition.event.sequenceNumber === current.event.sequenceNumber) {
                setIsAutoPlaying(false);
                return;
            }
            onSelectSequence(nextTransition.event.sequenceNumber);
        }, autoPlaySeconds * 1000);

        return () => window.clearInterval(id);
    }, [atEnd, autoPlaySeconds, current.event.sequenceNumber, currentIndex, isAutoPlaying, onSelectSequence, transitions]);

    const requestSelectSequence = (sequence: number) => {
        if (sequence === current.event.sequenceNumber) {
            return;
        }
        if (compareActive && onClearCompare) {
            setPendingAction({ type: 'select', sequence });
            return;
        }
        setIsAutoPlaying(false);
        onSelectSequence(sequence);
    };

    const handleAutoPlayToggle = () => {
        if (isAutoPlaying) {
            setIsAutoPlaying(false);
            return;
        }

        if (atEnd) {
            return;
        }

        const nextSequence = transitions[Math.min(currentIndex + 1, transitions.length - 1)].event.sequenceNumber;
        if (compareActive && onClearCompare) {
            setPendingAction({ type: 'autoplay', sequence: nextSequence });
            return;
        }

        setIsAutoPlaying(true);
    };

    return (
        <section className="card replay-debugger" tabIndex={0} role="region" aria-label="Replay debugger" aria-current={active ? 'page' : undefined} onFocus={onActivate}>
            <div className="card-title">Replay Debugger</div>
            <div className="replay-toolbar">
                <button
                    type="button"
                    onClick={() => requestSelectSequence(transitions[Math.max(currentIndex - 1, 0)].event.sequenceNumber)}
                    title="Step backward"
                    aria-label="Replay previous event"
                    disabled={atStart}
                >
                    Previous
                </button>
                <div className="replay-controls-center">
                    <div className="replay-position">Event {currentIndex + 1} of {transitions.length}</div>
                    <div className="replay-auto-controls">
                        <button
                            type="button"
                            className={`replay-auto-toggle ${isAutoPlaying ? 'active' : ''}`}
                            onClick={handleAutoPlayToggle}
                            disabled={atEnd}
                            aria-pressed={isAutoPlaying}
                        >
                            {isAutoPlaying ? 'Pause Auto Replay' : 'Start Auto Replay'}
                        </button>
                        <label className="replay-interval-picker">
                            <span>Speed</span>
                            <select
                                value={autoPlaySeconds}
                                onChange={e => setAutoPlaySeconds(Number(e.target.value) as (typeof AUTO_PLAY_OPTIONS)[number])}
                                disabled={isAutoPlaying}
                                aria-label="Auto replay interval"
                            >
                                {AUTO_PLAY_OPTIONS.map(seconds => (
                                    <option key={seconds} value={seconds}>{seconds}s / event</option>
                                ))}
                            </select>
                        </label>
                    </div>
                </div>
                <button
                    type="button"
                    onClick={() => requestSelectSequence(transitions[Math.min(currentIndex + 1, transitions.length - 1)].event.sequenceNumber)}
                    title="Step forward"
                    aria-label="Replay next event"
                    disabled={atEnd}
                >
                    Next
                </button>
            </div>
            {compareActive && (
                <div className="replay-compare-warning" role="alert" aria-live="polite">
                    <div>
                        <strong>Compare mode is active.</strong> Replay stepping or auto replay will replace the comparison and continue from the selected event.
                    </div>
                    {pendingAction != null ? (
                        <div className="replay-compare-actions">
                            <button
                                type="button"
                                className="replay-warning-btn replay-warning-btn--primary"
                                onClick={() => onClearCompare?.()}
                            >
                                {pendingAction.type === 'autoplay' ? 'Start replay and replace compare' : 'Continue and replace compare'}
                            </button>
                            <button
                                type="button"
                                className="replay-warning-btn"
                                onClick={() => setPendingAction(null)}
                            >
                                Keep compare
                            </button>
                        </div>
                    ) : (
                        <div className="replay-warning-hint">Use Previous, Next, the slider, or auto replay. We’ll ask before clearing compare mode.</div>
                    )}
                </div>
            )}
            <input
                type="range"
                min={0}
                max={Math.max(transitions.length - 1, 0)}
                value={currentIndex}
                onChange={e => requestSelectSequence(transitions[Number(e.target.value)].event.sequenceNumber)}
                aria-label="Replay position"
            />
            <div className="state-panel replay-state">
                <h4>Current State</h4>
                <JsonTreeView value={current.stateAfter} changedKeys={new Set(Object.keys(current.diff))} />
            </div>
        </section>
    );
}
