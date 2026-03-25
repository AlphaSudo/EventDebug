import { useEffect, useMemo, useState } from 'react';
import type { DiffPatch } from '../api/client';
import type { DiffResult } from '../workers/json-diff.worker';

export function useJsonDiffWorker(left: unknown, right: unknown, enabled = true) {
    const [patches, setPatches] = useState<DiffPatch[]>([]);
    const [durationMs, setDurationMs] = useState(0);
    const [loading, setLoading] = useState(false);
    const requestId = useMemo(() => `${Date.now()}-${Math.random()}`, [left, right]);

    useEffect(() => {
        if (!enabled) {
            setPatches([]);
            setDurationMs(0);
            setLoading(false);
            return;
        }
        const worker = new Worker(new URL('../workers/json-diff.worker.ts', import.meta.url), { type: 'module' });
        setLoading(true);
        const handleMessage = (event: MessageEvent<DiffResult>) => {
            if (event.data.requestId !== requestId) {
                return;
            }
            setPatches(event.data.patches);
            setDurationMs(event.data.durationMs);
            setLoading(false);
        };
        worker.addEventListener('message', handleMessage);
        worker.postMessage({ left, right, requestId });
        return () => {
            worker.removeEventListener('message', handleMessage);
            worker.terminate();
        };
    }, [enabled, left, right, requestId]);

    return { patches, durationMs, loading };
}
