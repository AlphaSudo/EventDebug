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

import { diffJson } from '../utils/jsonDiff';

self.onmessage = (event: MessageEvent<DiffRequest>) => {
    const start = performance.now();
    const payload: DiffResult = {
        requestId: event.data.requestId,
        patches: diffJson(event.data.left, event.data.right),
        durationMs: performance.now() - start,
    };
    self.postMessage(payload);
};
