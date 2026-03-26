import { describe, expect, it } from 'vitest';
import { diffJson } from './jsonDiff';

describe('diffJson', () => {
    it('returns no patches for structurally equal objects', () => {
        expect(diffJson({ a: 1, nested: { b: true } }, { a: 1, nested: { b: true } })).toEqual([]);
    });

    it('captures nested array and object changes', () => {
        expect(diffJson(
            { account: { balance: 10, tags: ['open', 'vip'] } },
            { account: { balance: 25, tags: ['open', 'priority'], owner: 'alice' } },
        )).toEqual([
            { path: '$.account.balance', type: 'changed', oldValue: 10, newValue: 25 },
            { path: '$.account.tags[1]', type: 'changed', oldValue: 'vip', newValue: 'priority' },
            { path: '$.account.owner', type: 'added', newValue: 'alice' },
        ]);
    });

    it('marks removals when right-side values disappear', () => {
        expect(diffJson(
            { before: { status: 'open', retries: 2 } },
            { before: { status: 'open' } },
        )).toEqual([
            { path: '$.before.retries', type: 'removed', oldValue: 2 },
        ]);
    });
});
