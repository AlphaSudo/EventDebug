import type { StoredEvent } from '../api/types';

export const MIN_SAME_TYPE_RUN = 4;

export type Segment =
    | { kind: 'single'; event: StoredEvent; index: number }
    | { kind: 'group'; eventType: string; items: StoredEvent[]; startIndex: number };

export type TimelineRow =
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

export function buildSegments(events: StoredEvent[]): Segment[] {
    const out: Segment[] = [];
    let index = 0;
    while (index < events.length) {
        const type = events[index].eventType;
        let next = index + 1;
        while (next < events.length && events[next].eventType === type) {
            next += 1;
        }
        const runLength = next - index;
        if (runLength >= MIN_SAME_TYPE_RUN) {
            out.push({
                kind: 'group',
                eventType: type,
                items: events.slice(index, next),
                startIndex: index,
            });
        } else {
            for (let cursor = index; cursor < next; cursor += 1) {
                out.push({ kind: 'single', event: events[cursor], index: cursor });
            }
        }
        index = next;
    }
    return out;
}

export function groupKey(startIndex: number, length: number): string {
    return `${startIndex}-${length}`;
}

export function flattenRows(
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

export function toggleCompareSequence(currentCompareSequence: number | null | undefined, nextSequence: number): number | null {
    return currentCompareSequence === nextSequence ? null : nextSequence;
}
