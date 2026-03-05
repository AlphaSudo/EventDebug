import { useEffect, useRef, useState } from 'react';

export type WebSocketStatus = 'connecting' | 'connected' | 'disconnected';

export function useWebSocket<T = unknown>(path: string, onMessage: (data: T) => void) {
    const [status, setStatus] = useState<WebSocketStatus>('connecting');
    const wsRef = useRef<WebSocket | null>(null);

    useEffect(() => {
        let closed = false;

        const connect = () => {
            if (closed) return;
            const ws = new WebSocket(`${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}${path}`);
            wsRef.current = ws;

            ws.onopen = () => setStatus('connected');
            ws.onclose = () => {
                setStatus('disconnected');
                if (!closed) {
                    setTimeout(connect, 3000);
                }
            };
            ws.onerror = () => setStatus('disconnected');
            ws.onmessage = msg => {
                try {
                    const parsed = JSON.parse(msg.data) as T;
                    onMessage(parsed);
                } catch {
                    // ignore malformed
                }
            };
        };

        connect();
        return () => {
            closed = true;
            wsRef.current?.close();
        };
    }, [path, onMessage]);

    return status;
}

