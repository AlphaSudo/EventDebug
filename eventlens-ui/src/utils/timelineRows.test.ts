import { describe, expect, it } from 'vitest';
import type { StoredEvent } from '../api/types';
import { buildSegments, flattenRows, groupKey, toggleCompareSequence } from './timelineRows';

function event(sequenceNumber: number, eventType: string): StoredEvent {
    return {
        eventId: `evt-${sequenceNumber}`,
        aggregateId: 'agg-1',
        aggregateType: 'Order',
        sequenceNumber,
        eventType,
        payload: null,
        metadata: '{}',
        timestamp: '2026-03-25T10:00:00Z',
        globalPosition: sequenceNumber,
    };
}

describe('timeline row helpers', () => {
    it('groups long same-type runs while leaving short runs expanded as singles', () => {
        const segments = buildSegments([
            event(1, 'Created'),
            event(2, 'Updated'),
            event(3, 'Updated'),
            event(4, 'Updated'),
            event(5, 'Updated'),
            event(6, 'Published'),
        ]);

        expect(segments).toHaveLength(3);
        expect(segments[0].kind).toBe('single');
        expect(segments[1]).toMatchObject({ kind: 'group', eventType: 'Updated', startIndex: 1 });
        expect(segments[2].kind).toBe('single');
    });

    it('expands grouped rows and marks the selected group', () => {
        const segments = buildSegments([
            event(1, 'Updated'),
            event(2, 'Updated'),
            event(3, 'Updated'),
            event(4, 'Updated'),
        ]);
        const expandedKey = groupKey(0, 4);

        const rows = flattenRows(segments, expandedKey, 3);

        expect(rows[0]).toMatchObject({ kind: 'group', expanded: true, containsSelection: true });
        expect(rows.slice(1)).toHaveLength(4);
        expect(rows[2]).toMatchObject({ kind: 'group-item', stepNumber: 2 });
    });

    it('toggles compare selection off when the same event is shift-clicked again', () => {
        expect(toggleCompareSequence(null, 12)).toBe(12);
        expect(toggleCompareSequence(12, 12)).toBeNull();
        expect(toggleCompareSequence(12, 13)).toBe(13);
    });
});
