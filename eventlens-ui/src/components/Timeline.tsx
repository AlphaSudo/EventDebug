import { useEffect, useMemo, useRef, useState } from 'react';
import type { StoredEvent } from '../api/client';
import { useTimeline } from '../hooks/useTimeline';
import { parseEventTimestamp } from '../utils/time';
import { buildSegments, flattenRows, groupKey, toggleCompareSequence, type Segment, type TimelineRow } from '../utils/timelineRows';

interface Props {
    aggregateId: string;
    selectedSequence: number | null;
    compareSequence?: number | null;
    onSelectEvent: (seq: number) => void;
    onSelectCompare?: (seq: number | null) => void;
    source?: string | null;
}

const ZOOM_OPTIONS = [1, 6, 24, 168] as const;

function dotClass(eventType: string): string {
    const t = eventType.toLowerCase();
    if (t.includes('created') || t.includes('opened') || t.includes('placed') || t.includes('submitted')) return 'created';
    if (t.includes('deleted') || t.includes('closed') || t.includes('cancelled') || t.includes('rejected')) return 'deleted';
    if (t.includes('completed') || t.includes('resolved') || t.includes('accepted') || t.includes('approved') || t.includes('assigned')) return 'completed';
    if (t.includes('failed') || t.includes('error') || t.includes('blocked')) return 'failed';
    if (t.includes('transfer')) return 'transfer';
    return 'default';
}

function firstSequenceForRow(row: TimelineRow): number {
    switch (row.kind) {
        case 'single':
        case 'group-item':
            return row.event.sequenceNumber;
        case 'group':
            return row.items[0]?.sequenceNumber ?? 0;
    }
}

function eventRowLabel(stepNumber: number, event: StoredEvent): string {
    return `Event ${stepNumber}, sequence ${event.sequenceNumber}, ${event.eventType}`;
}

function containsSequence(segment: Segment, sequence: number | null | undefined): boolean {
    if (sequence == null) {
        return false;
    }
    if (segment.kind === 'single') {
        return segment.event.sequenceNumber === sequence;
    }
    return segment.items.some(item => item.sequenceNumber === sequence);
}

function renderEventStep(
    row: Extract<TimelineRow, { kind: 'single' | 'group-item' }>,
    selectedSequence: number | null,
    compareSequence: number | null | undefined,
    onSelectEvent: (seq: number) => void,
    onSelectCompare: ((seq: number | null) => void) | undefined,
) {
    const event = row.event;
    const selected = selectedSequence === event.sequenceNumber;
    const compared = compareSequence === event.sequenceNumber;
    const compactClass = row.kind === 'group-item' ? ' timeline-step-compact' : '';

    return (
        <button
            key={row.key}
            type="button"
            className={`timeline-step timeline-step-${dotClass(event.eventType)} ${selected ? 'active' : ''} ${compared ? 'timeline-step-compare' : ''}${compactClass}`}
            onClick={e => {
                if (e.shiftKey && onSelectCompare) {
                    onSelectCompare(toggleCompareSequence(compareSequence, event.sequenceNumber));
                } else {
                    onSelectEvent(event.sequenceNumber);
                }
            }}
            aria-current={selected ? 'step' : undefined}
            aria-label={eventRowLabel(row.stepNumber, event)}
        >
            <span className="timeline-step-badge">Event {row.stepNumber}</span>
            <span className="timeline-step-seq">seq #{event.sequenceNumber}</span>
            <span className="timeline-step-type">{event.eventType}</span>
            <span className="timeline-step-seq">{parseEventTimestamp(event.timestamp).toLocaleTimeString()}</span>
        </button>
    );
}

export default function Timeline({
    aggregateId,
    selectedSequence,
    compareSequence,
    onSelectEvent,
    onSelectCompare,
    source,
}: Props) {
    const { data: timeline, isLoading } = useTimeline(aggregateId, source);
    const [filterType, setFilterType] = useState('');
    const [jumpInput, setJumpInput] = useState('');
    const [zoomHours, setZoomHours] = useState<number | 'all'>('all');
    const [expandedGroupKey, setExpandedGroupKey] = useState<string | null>(null);
    const containerRef = useRef<HTMLDivElement>(null);

    const events = timeline?.events ?? [];
    const totalEvents = timeline?.totalEvents ?? 0;

    const visibleEvents = useMemo(() => {
        let next = events;
        if (filterType) {
            next = next.filter(event => event.eventType === filterType);
        }
        if (zoomHours !== 'all' && next.length > 0) {
            const last = parseEventTimestamp(next[next.length - 1].timestamp).getTime();
            const threshold = last - (zoomHours * 60 * 60 * 1000);
            next = next.filter(event => parseEventTimestamp(event.timestamp).getTime() >= threshold);
        }
        return next;
    }, [events, filterType, zoomHours]);

    const segments = useMemo(() => buildSegments(visibleEvents), [visibleEvents]);
    const eventTypes = useMemo(() => [...new Set(events.map(event => event.eventType))].sort(), [events]);

    useEffect(() => {
        if (selectedSequence == null) {
            return;
        }
        for (const segment of segments) {
            if (segment.kind !== 'group') continue;
            if (segment.items.some(item => item.sequenceNumber === selectedSequence)) {
                setExpandedGroupKey(groupKey(segment.startIndex, segment.items.length));
                return;
            }
        }
        setExpandedGroupKey(null);
    }, [segments, selectedSequence]);

    const rows = useMemo(
        () => flattenRows(segments, expandedGroupKey, selectedSequence),
        [expandedGroupKey, segments, selectedSequence],
    );

    const selectedRowIndex = useMemo(() => {
        if (selectedSequence == null) return -1;
        return rows.findIndex(row => {
            if (row.kind === 'group') {
                return row.items.some(item => item.sequenceNumber === selectedSequence);
            }
            return row.event.sequenceNumber === selectedSequence;
        });
    }, [rows, selectedSequence]);

    useEffect(() => {
        const handler = (event: Event) => {
            const delta = (event as CustomEvent<number>).detail;
            if (!rows.length) return;

            if (delta < 0 || delta > 0) {
                const startIndex = selectedRowIndex >= 0 ? selectedRowIndex : 0;
                const nextIndex = Math.max(0, Math.min(rows.length - 1, startIndex + delta));
                onSelectEvent(firstSequenceForRow(rows[nextIndex]));
            }
        };
        window.addEventListener('eventlens:timeline-step', handler);
        return () => window.removeEventListener('eventlens:timeline-step', handler);
    }, [onSelectEvent, rows, selectedRowIndex]);

    useEffect(() => {
        const handler = (event: KeyboardEvent) => {
            const target = event.target as HTMLElement | null;
            const isTyping = !!target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
            if (isTyping || !rows.length) {
                return;
            }

            if ((event.key === 'ArrowLeft' || event.key === 'ArrowRight') && event.shiftKey) {
                event.preventDefault();
                const direction = event.key === 'ArrowLeft' ? -1 : 1;
                const groupRows = rows.filter((row): row is Extract<TimelineRow, { kind: 'group' }> => row.kind === 'group');
                if (!groupRows.length) {
                    return;
                }

                const currentGroupIndex = groupRows.findIndex(row =>
                    selectedSequence != null && row.items.some(item => item.sequenceNumber === selectedSequence),
                );
                const nextGroupIndex = currentGroupIndex >= 0
                    ? Math.max(0, Math.min(groupRows.length - 1, currentGroupIndex + direction))
                    : (direction > 0 ? 0 : groupRows.length - 1);
                onSelectEvent(groupRows[nextGroupIndex].items[0].sequenceNumber);
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [onSelectEvent, rows, selectedSequence]);

    useEffect(() => {
        if (!containerRef.current || selectedSequence == null) {
            return;
        }
        const selectedElement = containerRef.current.querySelector<HTMLElement>('[aria-current="step"]');
        if (!selectedElement) {
            return;
        }
        const containerRect = containerRef.current.getBoundingClientRect();
        const selectedRect = selectedElement.getBoundingClientRect();
        const currentScrollLeft = containerRef.current.scrollLeft;
        const selectedLeft = currentScrollLeft + (selectedRect.left - containerRect.left);
        const selectedRight = selectedLeft + selectedRect.width;
        const visibleLeft = currentScrollLeft;
        const visibleRight = currentScrollLeft + containerRef.current.clientWidth;

        if (selectedLeft < visibleLeft + 24) {
            containerRef.current.scrollTo({
                left: Math.max(0, selectedLeft - 24),
                behavior: 'smooth',
            });
        } else if (selectedRight > visibleRight - 24) {
            containerRef.current.scrollTo({
                left: selectedRight - containerRef.current.clientWidth + 24,
                behavior: 'smooth',
            });
        }
    }, [expandedGroupKey, selectedSequence]);

    if (isLoading) {
        return (
            <div className="card">
                <div className="card-title">Timeline</div>
                <div className="skeleton" style={{ height: 160 }} />
            </div>
        );
    }

    if (!events.length) {
        return (
            <div className="card">
                <div className="card-title">Timeline</div>
                <p style={{ color: 'var(--text-muted)' }}>No events found.</p>
            </div>
        );
    }

    const minSeq = visibleEvents[0]?.sequenceNumber ?? 0;
    const maxSeq = visibleEvents[visibleEvents.length - 1]?.sequenceNumber ?? 0;

    return (
        <div className="card">
            <div className="timeline-header-row">
                <div className="card-title" style={{ marginBottom: 0 }}>
                    Event Sequence
                    <span className="timeline-count-pill">{visibleEvents.length} / {totalEvents} events</span>
                </div>
                <div className="timeline-jump-group">
                    <input
                        className="timeline-jump-input"
                        type="number"
                        placeholder="Jump to seq"
                        value={jumpInput}
                        onChange={e => setJumpInput(e.target.value)}
                        onKeyDown={e => {
                            if (e.key === 'Enter') {
                                const seq = Number(jumpInput);
                                if (visibleEvents.some(event => event.sequenceNumber === seq)) {
                                    onSelectEvent(seq);
                                    setJumpInput('');
                                }
                            }
                        }}
                        aria-label="Jump to sequence number"
                    />
                </div>
            </div>

            <p className="timeline-hint">
                Scroll the rail horizontally from left to right. Repeated event runs collapse into grouped cards; click a group to open the full strip below.
            </p>

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

            <div className="timeline-filter-chips" role="group" aria-label="Zoom range">
                <button
                    type="button"
                    className={`filter-chip ${zoomHours === 'all' ? 'active' : ''}`}
                    onClick={() => setZoomHours('all')}
                >
                    All
                </button>
                {ZOOM_OPTIONS.map(hours => (
                    <button
                        key={hours}
                        type="button"
                        className={`filter-chip ${zoomHours === hours ? 'active' : ''}`}
                        onClick={() => setZoomHours(hours)}
                    >
                        {hours >= 24 ? `${hours / 24}d` : `${hours}h`}
                    </button>
                ))}
            </div>

            <div className="timeline-rail">
                <div ref={containerRef} className="timeline-stepper">
                    <div className="timeline-stepper-track">
                        {segments.map((segment, index) => {
                            const arrow = index < segments.length - 1 ? <span key={`arrow-${index}`} className="timeline-step-arrow" aria-hidden>→</span> : null;

                            if (segment.kind === 'single') {
                                const row: Extract<TimelineRow, { kind: 'single' }> = {
                                    kind: 'single',
                                    key: `single-${segment.event.sequenceNumber}`,
                                    event: segment.event,
                                    stepNumber: segment.index + 1,
                                };
                                return (
                                    <div key={row.key} className="timeline-track-item">
                                        {renderEventStep(row, selectedSequence, compareSequence, onSelectEvent, onSelectCompare)}
                                        {arrow}
                                    </div>
                                );
                            }

                            const key = groupKey(segment.startIndex, segment.items.length);
                            const expanded = expandedGroupKey === key;
                            const containsSelected = containsSequence(segment, selectedSequence);
                            const containsCompared = containsSequence(segment, compareSequence);

                            return (
                                <div key={`group-${key}`} className="timeline-track-item timeline-track-item--group">
                                    <button
                                        type="button"
                                        className={`timeline-group-chip timeline-step-${dotClass(segment.eventType)} ${containsSelected ? 'active' : ''} ${containsCompared ? 'timeline-step-compare' : ''} ${expanded ? 'expanded' : ''}`}
                                        onClick={() => setExpandedGroupKey(current => current === key ? null : key)}
                                        aria-expanded={expanded}
                                    >
                                        <span className="timeline-group-chip-top">
                                            <span className="timeline-group-count">x{segment.items.length}</span>
                                            <span className="timeline-group-chevron" aria-hidden>{expanded ? 'v' : '>'}</span>
                                        </span>
                                        <span className="timeline-group-type">{segment.eventType}</span>
                                        <span className="timeline-group-range">
                                            steps {segment.startIndex + 1}-{segment.startIndex + segment.items.length} seq #{segment.items[0].sequenceNumber}-#{segment.items[segment.items.length - 1].sequenceNumber}
                                        </span>
                                    </button>
                                    {arrow}
                                    {expanded && (
                                        <div className="timeline-expanded-deck">
                                            <div className="timeline-expanded-head">
                                                <div className="timeline-expanded-title">{segment.eventType}</div>
                                                <div className="timeline-group-range">
                                                    Expanded run of {segment.items.length} events
                                                </div>
                                                <button
                                                    type="button"
                                                    className="timeline-collapse-btn"
                                                    onClick={() => setExpandedGroupKey(null)}
                                                >
                                                    Collapse
                                                </button>
                                            </div>
                                            <div className="timeline-stepper-track">
                                                {segment.items.map((event, itemIndex) => {
                                                    const row: Extract<TimelineRow, { kind: 'group-item' }> = {
                                                        kind: 'group-item',
                                                        key: `group-item-${event.sequenceNumber}`,
                                                        event,
                                                        stepNumber: segment.startIndex + itemIndex + 1,
                                                        parentKey: key,
                                                    };
                                                    return (
                                                        <div key={row.key} className="timeline-track-item">
                                                            {renderEventStep(row, selectedSequence, compareSequence, onSelectEvent, onSelectCompare)}
                                                            {itemIndex < segment.items.length - 1 && <span className="timeline-step-arrow timeline-step-arrow-compact" aria-hidden>→</span>}
                                                        </div>
                                                    );
                                                })}
                                            </div>
                                        </div>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </div>
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
                    {selectedSequence != null ? (
                        <>
                            <strong>Selected seq #{selectedSequence}</strong>
                            {compareSequence != null && ` compared with #${compareSequence}`}
                            <span className="timeline-info-muted"> across {segments.length} visible segments</span>
                        </>
                    ) : 'Select an event'}
                </span>
                <span className="timeline-info-edge">Last seq #{maxSeq}</span>
            </div>
        </div>
    );
}
