import { useEffect, useRef, useState } from 'react';

export type WebSocketStatus = 'connecting' | 'connected' | 'disconnected';

const INITIAL_RECONNECT_MS = 1000;
const MAX_RECONNECT_MS = 30000;

export type UseWebSocketOptions = {
    /** When false, no socket is opened (e.g. frontend demo mode). */
    enabled?: boolean;
};

export function useWebSocket<T = unknown>(
    path: string,
    onMessage: (data: T) => void,
    options?: UseWebSocketOptions
) {
    const enabled = options?.enabled ?? true;
    const [status, setStatus] = useState<WebSocketStatus>(() => (enabled ? 'connecting' : 'connected'));
    const wsRef = useRef<WebSocket | null>(null);
    const onMessageRef = useRef(onMessage);
    const reconnectDelayRef = useRef(INITIAL_RECONNECT_MS);
    onMessageRef.current = onMessage;

    useEffect(() => {
        if (!enabled) {
            setStatus('connected');
            return () => {};
        }

        let closed = false;
        let timeoutId: ReturnType<typeof setTimeout>;

        const connect = () => {
            if (closed) return;
            const ws = new WebSocket(`${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}${path}`);
            wsRef.current = ws;

            ws.onopen = () => {
                reconnectDelayRef.current = INITIAL_RECONNECT_MS;
                setStatus('connected');
            };
            ws.onclose = () => {
                setStatus('disconnected');
                if (!closed) {
                    const delay = reconnectDelayRef.current;
                    timeoutId = setTimeout(() => {
                        reconnectDelayRef.current = Math.min(delay * 2, MAX_RECONNECT_MS);
                        connect();
                    }, delay);
                }
            };
            ws.onerror = () => setStatus('disconnected');
            ws.onmessage = msg => {
                try {
                    const parsed = JSON.parse(msg.data) as T;
                    onMessageRef.current(parsed);
                } catch {
                    // ignore malformed
                }
            };
        };

        connect();
        return () => {
            closed = true;
            clearTimeout(timeoutId);
            wsRef.current?.close();
        };
    }, [path, enabled]);

    return status;
}

