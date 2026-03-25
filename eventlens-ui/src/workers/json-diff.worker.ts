export interface DiffRequest {
    left: unknown;
    right: unknown;
    requestId: string;
}

export interface DiffPatch {
    path: string;
    type: 'added' | 'removed' | 'changed';
    oldValue?: unknown;
    newValue?: unknown;
}

export interface DiffResult {
    requestId: string;
    patches: DiffPatch[];
    durationMs: number;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function diffRecursive(left: unknown, right: unknown, path: string, patches: DiffPatch[]): void {
    if (JSON.stringify(left) === JSON.stringify(right)) {
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
        for (let i = 0; i < max; i += 1) {
            diffRecursive(left[i], right[i], `${path}[${i}]`, patches);
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

self.onmessage = (event: MessageEvent<DiffRequest>) => {
    const start = performance.now();
    const patches: DiffPatch[] = [];
    diffRecursive(event.data.left, event.data.right, '$', patches);
    const payload: DiffResult = {
        requestId: event.data.requestId,
        patches,
        durationMs: performance.now() - start,
    };
    self.postMessage(payload);
};
