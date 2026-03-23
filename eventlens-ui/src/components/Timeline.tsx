import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { StateTransition } from '../api/client';
import { useTimeline } from '../hooks/useTimeline';
import { parseEventTimestamp } from '../utils/time';

interface Props {
    aggregateId: string;
    selectedSequence: number | null;
    onSelectEvent: (seq: number) => void;
}

const MIN_SAME_TYPE_RUN = 4;

function dotClass(eventType: string): string {
    const t = eventType.toLowerCase();
    if (t.includes('created') || t.includes('opened') || t.includes('placed') || t.includes('submitted')) return 'created';
    if (t.includes('deleted') || t.includes('closed') || t.includes('cancelled') || t.includes('rejected')) return 'deleted';
    if (t.includes('completed') || t.includes('resolved') || t.includes('accepted') || t.includes('approved') || t.includes('assigned')) return 'completed';
    if (t.includes('failed') || t.includes('error') || t.includes('blocked')) return 'failed';
    if (t.includes('transfer')) return 'transfer';
    if (t.includes('line_item') || (t.includes('item') && t.includes('add'))) return 'item';
    if (t.includes('payment') || t.includes('progress')) return 'progress';
    return 'default';
}

type Segment =
    | { kind: 'single'; transition: StateTransition; index: number }
    | { kind: 'group'; eventType: string; items: StateTransition[]; startIndex: number };

function buildSegments(transitions: StateTransition[]): Segment[] {
    const out: Segment[] = [];
    let i = 0;
    while (i < transitions.length) {
        const type = transitions[i].event.eventType;
        let j = i + 1;
        while (j < transitions.length && transitions[j].event.eventType === type) {
            j++;
        }
        const runLen = j - i;
        if (runLen >= MIN_SAME_TYPE_RUN) {
            out.push({
                kind: 'group',
                eventType: type,
                items: transitions.slice(i, j),
                startIndex: i,
            });
            i = j;
        } else {
            for (let k = i; k < j; k++) {
                out.push({ kind: 'single', transition: transitions[k], index: k });
            }
            i = j;
        }
    }
    return out;
}

function groupKey(startIndex: number, len: number): string {
    return `${startIndex}-${len}`;
}

interface StepButtonProps {
    transition: StateTransition;
    stepNumber: number;
    selectedSequence: number | null;
    onSelectEvent: (seq: number) => void;
    compact?: boolean;
    hasDiff?: boolean;
}

function StepButton({ transition, stepNumber, selectedSequence, onSelectEvent, compact, hasDiff }: StepButtonProps) {
    const e = transition.event;
    const dc = dotClass(e.eventType);
    const active = selectedSequence === e.sequenceNumber;
    return (
        <button
            type="button"
            data-timeline-seq={e.sequenceNumber}
            className={`timeline-step timeline-step-${dc} ${compact ? 'timeline-step-compact' : ''} ${active ? 'active' : ''}`}
            onClick={() => onSelectEvent(e.sequenceNumber)}
            title={`${e.eventType}\n${parseEventTimestamp(e.timestamp).toLocaleString()}`}
            aria-current={active ? 'step' : undefined}
            aria-label={`Event ${stepNumber}, sequence ${e.sequenceNumber}, ${e.eventType}`}
        >
            <span className="timeline-step-badge">Event {stepNumber}</span>
            <span className="timeline-step-seq">seq #{e.sequenceNumber}</span>
            <span className="timeline-step-type">{e.eventType}</span>
            {hasDiff && <span className="timeline-anomaly-marker" title="Has state changes">●</span>}
        </button>
    );
}

export default function Timeline({ aggregateId, selectedSequence, onSelectEvent }: Props) {
    const { data: transitions, isLoading } = useTimeline(aggregateId);
    const [expandedGroupKey, setExpandedGroupKey] = useState<string | null>(null);
    const [jumpInput, setJumpInput] = useState('');
    const [filterType, setFilterType] = useState<string>('');

    const segments = useMemo(() => (transitions?.length ? buildSegments(transitions) : []), [transitions]);

    // Derive unique event types for filter chips
    const eventTypes = useMemo(() => {
        if (!transitions?.length) return [];
        const types = [...new Set(transitions.map(t => t.event.eventType))];
        return types.sort();
    }, [transitions]);

    // Apply filter: when a type is active, only show matching steps
    const filteredTransitions = useMemo(() => {
        if (!filterType || !transitions?.length) return transitions;
        return transitions.filter(t => t.event.eventType === filterType);
    }, [transitions, filterType]);

    const filteredSegments = useMemo(
        () => (filteredTransitions?.length ? buildSegments(filteredTransitions) : []),
        [filteredTransitions]
    );

    const activeSegments = filterType ? filteredSegments : segments;
    const activeTransitions = filterType ? filteredTransitions : transitions;

    const selectedIndex =
        selectedSequence != null && activeTransitions?.length
            ? activeTransitions.findIndex(t => t.event.sequenceNumber === selectedSequence)
            : -1;
    const stepDisplay = selectedIndex >= 0 ? selectedIndex + 1 : null;

    const minSeq = activeTransitions?.[0]?.event.sequenceNumber ?? 0;
    const maxSeq = activeTransitions?.[activeTransitions.length - 1]?.event.sequenceNumber ?? 0;

    const expandedSeg = useMemo(() => {
        if (!expandedGroupKey) return null;
        for (const seg of activeSegments) {
            if (seg.kind !== 'group') continue;
            if (groupKey(seg.startIndex, seg.items.length) === expandedGroupKey) return seg;
        }
        return null;
    }, [expandedGroupKey, activeSegments]);

    useEffect(() => {
        if (selectedSequence == null || selectedIndex < 0) return;
        for (const seg of activeSegments) {
            if (seg.kind !== 'group') continue;
            const end = seg.startIndex + seg.items.length - 1;
            if (selectedIndex >= seg.startIndex && selectedIndex <= end) {
                setExpandedGroupKey(groupKey(seg.startIndex, seg.items.length));
                return;
            }
        }
        setExpandedGroupKey(null);
    }, [selectedSequence, selectedIndex, activeSegments]);

    useEffect(() => {
        if (selectedSequence == null) return;
        const id = requestAnimationFrame(() => {
            const stepEl = document.querySelector(`[data-timeline-seq="${selectedSequence}"]`);
            const anchorEl = document.querySelector('[data-timeline-group-anchor="1"]');
            (stepEl ?? anchorEl)?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' });
        });
        return () => cancelAnimationFrame(id);
    }, [selectedSequence, expandedGroupKey, activeSegments]);

    const toggleGroup = (startIndex: number, len: number) => {
        const key = groupKey(startIndex, len);
        setExpandedGroupKey(prev => (prev === key ? null : key));
    };

    // ── Keyboard navigation ──────────────────────────────────────────────
    const handleKeyNav = useCallback((e: KeyboardEvent) => {
        if (!activeTransitions?.length) return;
        const target = e.target as HTMLElement;
        if (target.tagName === 'INPUT') return;

        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
            e.preventDefault();
            const dir = e.key === 'ArrowLeft' ? -1 : 1;

            if (e.shiftKey) {
                // Jump to prev/next group boundary
                const currentSeg = selectedIndex >= 0
                    ? activeSegments.find(seg =>
                        seg.kind === 'group'
                            ? selectedIndex >= seg.startIndex && selectedIndex < seg.startIndex + seg.items.length
                            : seg.index === selectedIndex
                    )
                    : null;
                const segIdx = currentSeg ? activeSegments.indexOf(currentSeg) : -1;
                const targetSeg = activeSegments[segIdx + dir];
                if (targetSeg) {
                    const firstT = targetSeg.kind === 'single'
                        ? targetSeg.transition
                        : targetSeg.items[0];
                    onSelectEvent(firstT.event.sequenceNumber);
                }
            } else {
                // Step one event at a time
                const nextIndex = selectedIndex + dir;
                if (nextIndex >= 0 && nextIndex < activeTransitions.length) {
                    onSelectEvent(activeTransitions[nextIndex].event.sequenceNumber);
                }
            }
        }

        // Number keys 1-4 switch StateViewer tabs — dispatched as custom event
        if (['1', '2', '3', '4'].includes(e.key) && !e.ctrlKey && !e.metaKey && !e.altKey) {
            if (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA') {
                const tabMap: Record<string, string> = { '1': 'changes', '2': 'before-after', '3': 'raw' };
                window.dispatchEvent(new CustomEvent('eventlens:switchtab', { detail: tabMap[e.key] }));
            }
        }

        // Space = pause/resume live stream
        if (e.key === ' ' && target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA' && target.tagName !== 'BUTTON') {
            e.preventDefault();
            window.dispatchEvent(new CustomEvent('eventlens:togglestream'));
        }

        // Cmd/Ctrl+K focuses search
        if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            document.getElementById('aggregate-search')?.focus();
        }
    }, [activeTransitions, activeSegments, selectedIndex, onSelectEvent]);

    const handleKeyNavRef = useRef(handleKeyNav);
    handleKeyNavRef.current = handleKeyNav;

    useEffect(() => {
        const handler = (e: KeyboardEvent) => handleKeyNavRef.current(e);
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    const handleJump = () => {
        const seq = parseInt(jumpInput, 10);
        if (!isNaN(seq) && activeTransitions?.some(t => t.event.sequenceNumber === seq)) {
            onSelectEvent(seq);
            setJumpInput('');
        }
    };

    if (isLoading) {
        return (
            <div className="card">
                <div className="card-title">⏱ Event sequence</div>
                <div className="skeleton" style={{ height: 64 }} />
            </div>
        );
    }

    if (!transitions?.length) {
        return (
            <div className="card">
                <div className="card-title">⏱ Event sequence</div>
                <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>No events found for this aggregate.</p>
            </div>
        );
    }

    return (
        <div className="card">
            {/* Header row */}
            <div className="timeline-header-row">
                <div className="card-title" style={{ marginBottom: 0 }}>
                    ⏱ Event sequence
                    <span className="timeline-count-pill">
                        {filterType ? `${activeTransitions?.length} / ${transitions.length}` : transitions.length} events
                    </span>
                </div>

                {/* Jump to seq */}
                <div className="timeline-jump-group">
                    <input
                        className="timeline-jump-input"
                        type="number"
                        placeholder="Jump to seq"
                        value={jumpInput}
                        onChange={e => setJumpInput(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && handleJump()}
                        aria-label="Jump to sequence number"
                    />
                    <button type="button" className="timeline-jump-btn" onClick={handleJump}>↵</button>
                </div>
            </div>

            {/* Event type filter chips */}
            {eventTypes.length > 1 && (
                <div className="timeline-filter-chips" role="group" aria-label="Filter by event type">
                    <button
                        type="button"
                        className={`filter-chip ${!filterType ? 'active' : ''}`}
                        onClick={() => setFilterType('')}
                    >
                        All
                    </button>
                    {eventTypes.map(type => (
                        <button
                            key={type}
                            type="button"
                            className={`filter-chip ${filterType === type ? 'active' : ''}`}
                            onClick={() => setFilterType(t => t === type ? '' : type)}
                        >
                            {type}
                        </button>
                    ))}
                </div>
            )}

            <div className="timeline-rail">
                <div className="timeline-stepper" role="navigation" aria-label="Events in order">
                    <div className="timeline-stepper-track">
                        {activeSegments.map((seg, si) => (
                            <Fragment key={seg.kind === 'group' ? `g-${seg.startIndex}` : `s-${seg.transition.event.sequenceNumber}`}>
                                {si > 0 && <span className="timeline-step-arrow" aria-hidden>→</span>}
                                {seg.kind === 'single' ? (
                                    <StepButton
                                        transition={seg.transition}
                                        stepNumber={seg.index + 1}
                                        selectedSequence={selectedSequence}
                                        onSelectEvent={onSelectEvent}
                                        hasDiff={Object.keys(seg.transition.diff ?? {}).length > 0}
                                    />
                                ) : (
                                    <GroupSummaryChip
                                        segment={seg}
                                        selectedSequence={selectedSequence}
                                        expanded={expandedGroupKey === groupKey(seg.startIndex, seg.items.length)}
                                        onToggle={() => toggleGroup(seg.startIndex, seg.items.length)}
                                    />
                                )}
                            </Fragment>
                        ))}
                    </div>
                </div>

                {expandedSeg && (
                    <div className="timeline-expanded-deck">
                        <div className="timeline-expanded-head">
                            <span className="timeline-expanded-title">{expandedSeg.eventType}</span>
                            <span className="timeline-expanded-meta">
                                {expandedSeg.items.length} events · steps {expandedSeg.startIndex + 1}–
                                {expandedSeg.startIndex + expandedSeg.items.length}
                            </span>
                            <button type="button" className="timeline-expanded-close" onClick={() => setExpandedGroupKey(null)}>
                                Collapse
                            </button>
                        </div>
                        <div className="timeline-expanded-strip">
                            {expandedSeg.items.map((t, i) => (
                                <Fragment key={t.event.sequenceNumber}>
                                    {i > 0 && <span className="timeline-step-arrow timeline-step-arrow-compact" aria-hidden>→</span>}
                                    <StepButton
                                        transition={t}
                                        stepNumber={expandedSeg.startIndex + i + 1}
                                        selectedSequence={selectedSequence}
                                        onSelectEvent={onSelectEvent}
                                        compact
                                        hasDiff={Object.keys(t.diff ?? {}).length > 0}
                                    />
                                </Fragment>
                            ))}
                        </div>
                    </div>
                )}
            </div>

            <input
                type="range"
                className="timeline-slider"
                min={minSeq}
                max={maxSeq}
                value={selectedSequence ?? maxSeq}
                onChange={e => onSelectEvent(Number(e.target.value))}
                aria-label="Scrub event sequence"
            />

            <div className="timeline-info">
                <span className="timeline-info-edge">First · seq #{minSeq}</span>
                <span className="timeline-info-center">
                    {stepDisplay != null ? (
                        <>
                            <strong>Step {stepDisplay}</strong> of {activeTransitions?.length}
                            <span className="timeline-info-muted"> · sequence #{selectedSequence}</span>
                            <br />
                            <span className="timeline-info-type">
                                {activeTransitions?.find(t => t.event.sequenceNumber === selectedSequence)?.event.eventType ?? ''}
                            </span>
                        </>
                    ) : (
                        'Select an event above or drag the scrubber'
                    )}
                </span>
                <span className="timeline-info-edge">Last · seq #{maxSeq}</span>
            </div>
        </div>
    );
}

function GroupSummaryChip({
    segment,
    selectedSequence,
    expanded,
    onToggle,
}: {
    segment: Extract<Segment, { kind: 'group' }>;
    selectedSequence: number | null;
    expanded: boolean;
    onToggle: () => void;
}) {
    const { items, startIndex, eventType } = segment;
    const first = items[0].event;
    const last = items[items.length - 1].event;
    const dc = dotClass(eventType);
    const containsSelection =
        selectedSequence != null && items.some(t => t.event.sequenceNumber === selectedSequence);
    const showAnchor = containsSelection && !expanded;

    return (
        <button
            type="button"
            className={`timeline-group-chip timeline-step-${dc} ${containsSelection ? 'has-selection' : ''} ${expanded ? 'expanded' : ''} ${containsSelection && !expanded ? 'active' : ''}`}
            onClick={onToggle}
            aria-expanded={expanded}
            data-timeline-group-anchor={showAnchor ? '1' : undefined}
            title={`${items.length} × ${eventType}. Click to ${expanded ? 'collapse' : 'show every step'}.`}
        >
            <span className="timeline-group-chip-top">
                <span className="timeline-group-count">×{items.length}</span>
                <span className="timeline-group-chevron" aria-hidden>
                    {expanded ? '▲' : '▼'}
                </span>
            </span>
            <span className="timeline-group-type">{eventType}</span>
            <span className="timeline-group-range">
                steps {startIndex + 1}–{startIndex + items.length} · seq #{first.sequenceNumber}–#{last.sequenceNumber}
            </span>
        </button>
    );
}
