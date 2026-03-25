import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { StoredEvent } from '../api/client';
import { useTimeline } from '../hooks/useTimeline';
import { parseEventTimestamp } from '../utils/time';

interface Props {
    aggregateId: string;
    selectedSequence: number | null;
    onSelectEvent: (seq: number) => void;
    source?: string | null;
}

const MIN_SAME_TYPE_RUN = 4;

type Segment =
    | { kind: 'single'; event: StoredEvent; index: number }
    | { kind: 'group'; eventType: string; items: StoredEvent[]; startIndex: number };

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

function buildSegments(events: StoredEvent[]): Segment[] {
    const out: Segment[] = [];
    let i = 0;
    while (i < events.length) {
        const type = events[i].eventType;
        let j = i + 1;
        while (j < events.length && events[j].eventType === type) {
            j++;
        }
        const runLen = j - i;
        if (runLen >= MIN_SAME_TYPE_RUN) {
            out.push({
                kind: 'group',
                eventType: type,
                items: events.slice(i, j),
                startIndex: i,
            });
            i = j;
        } else {
            for (let k = i; k < j; k++) {
                out.push({ kind: 'single', event: events[k], index: k });
            }
            i = j;
        }
    }
    return out;
}

function groupKey(startIndex: number, len: number): string {
    return `${startIndex}-${len}`;
}

function StepButton({
    event,
    stepNumber,
    selectedSequence,
    onSelectEvent,
    compact,
}: {
    event: StoredEvent;
    stepNumber: number;
    selectedSequence: number | null;
    onSelectEvent: (seq: number) => void;
    compact?: boolean;
}) {
    const dc = dotClass(event.eventType);
    const active = selectedSequence === event.sequenceNumber;
    return (
        <button
            type="button"
            data-timeline-seq={event.sequenceNumber}
            className={`timeline-step timeline-step-${dc} ${compact ? 'timeline-step-compact' : ''} ${active ? 'active' : ''}`}
            onClick={() => onSelectEvent(event.sequenceNumber)}
            title={`${event.eventType}\n${parseEventTimestamp(event.timestamp).toLocaleString()}`}
            aria-current={active ? 'step' : undefined}
            aria-label={`Event ${stepNumber}, sequence ${event.sequenceNumber}, ${event.eventType}`}
        >
            <span className="timeline-step-badge">Event {stepNumber}</span>
            <span className="timeline-step-seq">seq #{event.sequenceNumber}</span>
            <span className="timeline-step-type">{event.eventType}</span>
        </button>
    );
}

export default function Timeline({ aggregateId, selectedSequence, onSelectEvent, source }: Props) {
    const { data: timeline, isLoading } = useTimeline(aggregateId, source);
    const events = timeline?.events ?? [];
    const totalEvents = timeline?.totalEvents ?? 0;
    const [expandedGroupKey, setExpandedGroupKey] = useState<string | null>(null);
    const [jumpInput, setJumpInput] = useState('');
    const [filterType, setFilterType] = useState('');

    const segments = useMemo(() => (events.length ? buildSegments(events) : []), [events]);
    const eventTypes = useMemo(() => (events.length ? [...new Set(events.map(event => event.eventType))].sort() : []), [events]);
    const filteredEvents = useMemo(() => (!filterType ? events : events.filter(event => event.eventType === filterType)), [events, filterType]);
    const filteredSegments = useMemo(() => (filteredEvents.length ? buildSegments(filteredEvents) : []), [filteredEvents]);
    const activeSegments = filterType ? filteredSegments : segments;
    const activeEvents = filterType ? filteredEvents : events;

    const selectedIndex = selectedSequence != null ? activeEvents.findIndex(event => event.sequenceNumber === selectedSequence) : -1;
    const stepDisplay = selectedIndex >= 0 ? selectedIndex + 1 : null;
    const minSeq = activeEvents[0]?.sequenceNumber ?? 0;
    const maxSeq = activeEvents[activeEvents.length - 1]?.sequenceNumber ?? 0;

    const expandedSeg = useMemo(() => {
        if (!expandedGroupKey) return null;
        for (const seg of activeSegments) {
            if (seg.kind === 'group' && groupKey(seg.startIndex, seg.items.length) === expandedGroupKey) {
                return seg;
            }
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

    const handleKeyNav = useCallback((e: KeyboardEvent) => {
        if (!activeEvents.length) return;
        const target = e.target as HTMLElement;
        if (target.tagName === 'INPUT') return;

        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
            e.preventDefault();
            const dir = e.key === 'ArrowLeft' ? -1 : 1;

            if (e.shiftKey) {
                const currentSeg = selectedIndex >= 0
                    ? activeSegments.find(seg => seg.kind === 'group'
                        ? selectedIndex >= seg.startIndex && selectedIndex < seg.startIndex + seg.items.length
                        : seg.index === selectedIndex)
                    : null;
                const segIdx = currentSeg ? activeSegments.indexOf(currentSeg) : -1;
                const targetSeg = activeSegments[segIdx + dir];
                if (targetSeg) {
                    const firstEvent = targetSeg.kind === 'single' ? targetSeg.event : targetSeg.items[0];
                    onSelectEvent(firstEvent.sequenceNumber);
                }
            } else {
                const nextIndex = selectedIndex + dir;
                if (nextIndex >= 0 && nextIndex < activeEvents.length) {
                    onSelectEvent(activeEvents[nextIndex].sequenceNumber);
                }
            }
        }

        if (['1', '2', '3', '4'].includes(e.key) && !e.ctrlKey && !e.metaKey && !e.altKey) {
            if (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA') {
                const tabMap: Record<string, string> = { '1': 'changes', '2': 'before-after', '3': 'raw' };
                window.dispatchEvent(new CustomEvent('eventlens:switchtab', { detail: tabMap[e.key] }));
            }
        }

        if (e.key === ' ' && target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA' && target.tagName !== 'BUTTON') {
            e.preventDefault();
            window.dispatchEvent(new CustomEvent('eventlens:togglestream'));
        }

        if (e.key === 'k' && (e.metaKey || e.ctrlKey)) {
            e.preventDefault();
            document.getElementById('aggregate-search')?.focus();
        }
    }, [activeEvents, activeSegments, selectedIndex, onSelectEvent]);

    const handleKeyNavRef = useRef(handleKeyNav);
    handleKeyNavRef.current = handleKeyNav;

    useEffect(() => {
        const handler = (e: KeyboardEvent) => handleKeyNavRef.current(e);
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    const handleJump = () => {
        const seq = parseInt(jumpInput, 10);
        if (!Number.isNaN(seq) && activeEvents.some(event => event.sequenceNumber === seq)) {
            onSelectEvent(seq);
            setJumpInput('');
        }
    };

    if (isLoading) {
        return (
            <div className="card">
                <div className="card-title">Event sequence</div>
                <div className="skeleton" style={{ height: 64 }} />
            </div>
        );
    }

    if (!events.length) {
        return (
            <div className="card">
                <div className="card-title">Event sequence</div>
                <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>No events found for this aggregate.</p>
            </div>
        );
    }

    return (
        <div className="card">
            <div className="timeline-header-row">
                <div className="card-title" style={{ marginBottom: 0 }}>
                    Event sequence
                    <span className="timeline-count-pill">
                        {filterType ? `${activeEvents.length} / ${totalEvents}` : totalEvents} events
                    </span>
                </div>

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
                    <button type="button" className="timeline-jump-btn" onClick={handleJump}>Go</button>
                </div>
            </div>

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
                            onClick={() => setFilterType(current => current === type ? '' : type)}
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
                            <Fragment key={seg.kind === 'group' ? `g-${seg.startIndex}` : `s-${seg.event.sequenceNumber}`}>
                                {si > 0 && <span className="timeline-step-arrow" aria-hidden>{'>'}</span>}
                                {seg.kind === 'single' ? (
                                    <StepButton
                                        event={seg.event}
                                        stepNumber={seg.index + 1}
                                        selectedSequence={selectedSequence}
                                        onSelectEvent={onSelectEvent}
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
                                {expandedSeg.items.length} events steps {expandedSeg.startIndex + 1}-
                                {expandedSeg.startIndex + expandedSeg.items.length}
                            </span>
                            <button type="button" className="timeline-expanded-close" onClick={() => setExpandedGroupKey(null)}>
                                Collapse
                            </button>
                        </div>
                        <div className="timeline-expanded-strip">
                            {expandedSeg.items.map((event, i) => (
                                <Fragment key={event.sequenceNumber}>
                                    {i > 0 && <span className="timeline-step-arrow timeline-step-arrow-compact" aria-hidden>{'>'}</span>}
                                    <StepButton
                                        event={event}
                                        stepNumber={expandedSeg.startIndex + i + 1}
                                        selectedSequence={selectedSequence}
                                        onSelectEvent={onSelectEvent}
                                        compact
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
                <span className="timeline-info-edge">First seq #{minSeq}</span>
                <span className="timeline-info-center">
                    {stepDisplay != null ? (
                        <>
                            <strong>Step {stepDisplay}</strong> of {activeEvents.length}
                            <span className="timeline-info-muted"> sequence #{selectedSequence}</span>
                            <br />
                            <span className="timeline-info-type">
                                {activeEvents.find(event => event.sequenceNumber === selectedSequence)?.eventType ?? ''}
                            </span>
                        </>
                    ) : (
                        'Select an event above or drag the scrubber'
                    )}
                </span>
                <span className="timeline-info-edge">Last seq #{maxSeq}</span>
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
    const first = items[0];
    const last = items[items.length - 1];
    const dc = dotClass(eventType);
    const containsSelection = selectedSequence != null && items.some(event => event.sequenceNumber === selectedSequence);
    const showAnchor = containsSelection && !expanded;

    return (
        <button
            type="button"
            className={`timeline-group-chip timeline-step-${dc} ${containsSelection ? 'has-selection' : ''} ${expanded ? 'expanded' : ''} ${containsSelection && !expanded ? 'active' : ''}`}
            onClick={onToggle}
            aria-expanded={expanded}
            data-timeline-group-anchor={showAnchor ? '1' : undefined}
            title={`${items.length} x ${eventType}. Click to ${expanded ? 'collapse' : 'show every step'}.`}
        >
            <span className="timeline-group-chip-top">
                <span className="timeline-group-count">x{items.length}</span>
                <span className="timeline-group-chevron" aria-hidden>
                    {expanded ? 'v' : '>'}
                </span>
            </span>
            <span className="timeline-group-type">{eventType}</span>
            <span className="timeline-group-range">
                steps {startIndex + 1}-{startIndex + items.length} seq #{first.sequenceNumber}-#{last.sequenceNumber}
            </span>
        </button>
    );
}
