import type { DiffPatch } from '../api/types';

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function valuesEqual(left: unknown, right: unknown): boolean {
    return JSON.stringify(left) === JSON.stringify(right);
}

function diffRecursive(left: unknown, right: unknown, path: string, patches: DiffPatch[]): void {
    if (valuesEqual(left, right)) {
        return;
    }

    if (left === undefined || left === null) {
        patches.push({ path, type: 'added', newValue: right });
        return;
    }

    if (right === undefined || right === null) {
        patches.push({ path, type: 'removed', oldValue: left });
        return;
    }

    if (Array.isArray(left) && Array.isArray(right)) {
        const max = Math.max(left.length, right.length);
        for (let index = 0; index < max; index += 1) {
            diffRecursive(left[index], right[index], `${path}[${index}]`, patches);
        }
        return;
    }

    if (isObject(left) && isObject(right)) {
        const keys = new Set([...Object.keys(left), ...Object.keys(right)]);
        for (const key of keys) {
            diffRecursive(left[key], right[key], path === '$' ? `$.${key}` : `${path}.${key}`, patches);
        }
        return;
    }

    patches.push({ path, type: 'changed', oldValue: left, newValue: right });
}

export function diffJson(left: unknown, right: unknown): DiffPatch[] {
    const patches: DiffPatch[] = [];
    diffRecursive(left, right, '$', patches);
    return patches;
}
