import { useEffect, useMemo, useRef, useState } from 'react';
import type { StoredEvent } from '../api/client';
import { useTimeline } from '../hooks/useTimeline';
import { parseEventTimestamp } from '../utils/time';

interface Props {
    aggregateId: string;
    selectedSequence: number | null;
    compareSequence?: number | null;
    onSelectEvent: (seq: number) => void;
    onSelectCompare?: (seq: number | null) => void;
    source?: string | null;
}

const MIN_SAME_TYPE_RUN = 4;
const ROW_HEIGHT = 58;
const VIEWPORT_HEIGHT = 360;
const OVERSCAN = 6;
const ZOOM_OPTIONS = [1, 6, 24, 168] as const;

type Segment =
    | { kind: 'single'; event: StoredEvent; index: number }
    | { kind: 'group'; eventType: string; items: StoredEvent[]; startIndex: number };

type TimelineRow =
    | { kind: 'single'; key: string; event: StoredEvent; stepNumber: number }
    | {
        kind: 'group';
        key: string;
        eventType: string;
        items: StoredEvent[];
        startIndex: number;
        expanded: boolean;
        containsSelection: boolean;
    }
    | { kind: 'group-item'; key: string; event: StoredEvent; stepNumber: number; parentKey: string };

function dotClass(eventType: string): string {
    const t = eventType.toLowerCase();
    if (t.includes('created') || t.includes('opened') || t.includes('placed') || t.includes('submitted')) return 'created';
    if (t.includes('deleted') || t.includes('closed') || t.includes('cancelled') || t.includes('rejected')) return 'deleted';
    if (t.includes('completed') || t.includes('resolved') || t.includes('accepted') || t.includes('approved') || t.includes('assigned')) return 'completed';
    if (t.includes('failed') || t.includes('error') || t.includes('blocked')) return 'failed';
    if (t.includes('transfer')) return 'transfer';
    return 'default';
}

function buildSegments(events: StoredEvent[]): Segment[] {
    const out: Segment[] = [];
    let i = 0;
    while (i < events.length) {
        const type = events[i].eventType;
        let j = i + 1;
        while (j < events.length && events[j].eventType === type) {
            j += 1;
        }
        const runLen = j - i;
        if (runLen >= MIN_SAME_TYPE_RUN) {
            out.push({
                kind: 'group',
                eventType: type,
                items: events.slice(i, j),
                startIndex: i,
            });
        } else {
            for (let k = i; k < j; k += 1) {
                out.push({ kind: 'single', event: events[k], index: k });
            }
        }
        i = j;
    }
    return out;
}

function groupKey(startIndex: number, len: number): string {
    return `${startIndex}-${len}`;
}

function flattenRows(
    segments: Segment[],
    expandedGroupKey: string | null,
    selectedSequence: number | null,
): TimelineRow[] {
    const rows: TimelineRow[] = [];
    for (const segment of segments) {
        if (segment.kind === 'single') {
            rows.push({
                kind: 'single',
                key: `single-${segment.event.sequenceNumber}`,
                event: segment.event,
                stepNumber: segment.index + 1,
            });
            continue;
        }

        const key = groupKey(segment.startIndex, segment.items.length);
        const containsSelection = selectedSequence != null
            && segment.items.some(item => item.sequenceNumber === selectedSequence);
        const expanded = expandedGroupKey === key;
        rows.push({
            kind: 'group',
            key: `group-${key}`,
            eventType: segment.eventType,
            items: segment.items,
            startIndex: segment.startIndex,
            expanded,
            containsSelection,
        });
        if (expanded) {
            rows.push(...segment.items.map((event, index) => ({
                kind: 'group-item' as const,
                key: `group-item-${event.sequenceNumber}`,
                event,
                stepNumber: segment.startIndex + index + 1,
                parentKey: key,
            })));
        }
    }
    return rows;
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
    const [scrollTop, setScrollTop] = useState(0);
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
        if (selectedRowIndex < 0 || !containerRef.current) return;
        const top = selectedRowIndex * ROW_HEIGHT;
        const bottom = top + ROW_HEIGHT;
        if (top < scrollTop) {
            containerRef.current.scrollTop = top;
        } else if (bottom > scrollTop + VIEWPORT_HEIGHT) {
            containerRef.current.scrollTop = bottom - VIEWPORT_HEIGHT;
        }
    }, [scrollTop, selectedRowIndex]);

    const startIndex = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN);
    const endIndex = Math.min(rows.length, Math.ceil((scrollTop + VIEWPORT_HEIGHT) / ROW_HEIGHT) + OVERSCAN);
    const renderedRows = rows.slice(startIndex, endIndex);

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
                    Enhanced Timeline
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

            <div
                ref={containerRef}
                className="timeline-virtual-container"
                style={{ position: 'relative', overflowY: 'auto', maxHeight: VIEWPORT_HEIGHT }}
                onScroll={e => setScrollTop(e.currentTarget.scrollTop)}
            >
                <div style={{ height: rows.length * ROW_HEIGHT, position: 'relative' }}>
                    {renderedRows.map((row, index) => {
                        const actualIndex = startIndex + index;
                        const top = actualIndex * ROW_HEIGHT;

                        if (row.kind === 'group') {
                            return (
                                <button
                                    key={row.key}
                                    type="button"
                                    className={`timeline-group-chip timeline-step-${dotClass(row.eventType)} ${row.containsSelection ? 'active' : ''} ${row.expanded ? 'expanded' : ''}`}
                                    style={{ position: 'absolute', top, left: 0, right: 0, height: ROW_HEIGHT - 6 }}
                                    onClick={() => setExpandedGroupKey(current => current === groupKey(row.startIndex, row.items.length) ? null : groupKey(row.startIndex, row.items.length))}
                                    aria-expanded={row.expanded}
                                >
                                    <span className="timeline-group-chip-top">
                                        <span className="timeline-group-count">x{row.items.length}</span>
                                        <span className="timeline-group-chevron" aria-hidden>{row.expanded ? 'v' : '>'}</span>
                                    </span>
                                    <span className="timeline-group-type">{row.eventType}</span>
                                    <span className="timeline-group-range">
                                        steps {row.startIndex + 1}-{row.startIndex + row.items.length} seq #{row.items[0].sequenceNumber}-#{row.items[row.items.length - 1].sequenceNumber}
                                    </span>
                                </button>
                            );
                        }

                        const event = row.event;
                        const selected = selectedSequence === event.sequenceNumber;
                        const compared = compareSequence === event.sequenceNumber;
                        const compactClass = row.kind === 'group-item' ? ' timeline-step-compact' : '';

                        return (
                            <button
                                key={row.key}
                                type="button"
                                className={`timeline-step timeline-step-${dotClass(event.eventType)} ${selected ? 'active' : ''} ${compared ? 'timeline-step-compare' : ''}${compactClass}`}
                                style={{ position: 'absolute', top, left: 0, right: 0, height: ROW_HEIGHT - 6 }}
                                onClick={e => {
                                    if (e.shiftKey && onSelectCompare) {
                                        onSelectCompare(event.sequenceNumber === compareSequence ? null : event.sequenceNumber);
                                    } else {
                                        onSelectEvent(event.sequenceNumber);
                                    }
                                }}
                                aria-current={selected ? 'step' : undefined}
                                aria-selected={selected}
                                aria-label={eventRowLabel(row.stepNumber, event)}
                            >
                                <span className="timeline-step-badge">Event {row.stepNumber}</span>
                                <span className="timeline-step-seq">seq #{event.sequenceNumber}</span>
                                <span className="timeline-step-type">{event.eventType}</span>
                                <span className="timeline-step-seq">{parseEventTimestamp(event.timestamp).toLocaleTimeString()}</span>
                            </button>
                        );
                    })}
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
                            <span className="timeline-info-muted"> in {rows.length} visible rows</span>
                        </>
                    ) : 'Select an event'}
                </span>
                <span className="timeline-info-edge">Last seq #{maxSeq}</span>
            </div>
        </div>
    );
}
