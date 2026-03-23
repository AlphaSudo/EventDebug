/**
 * EventLens returns either:
 * - ISO-8601 instants from Jackson (e.g. WebSocket / JSON `Instant` fields)
 * - Unix epoch seconds or milliseconds as numbers or numeric strings
 */
export function parseEventTimestamp(ts: string | number): Date {
    if (typeof ts === 'number') {
        if (Number.isNaN(ts)) return new Date();
        return ts < 1e12 ? new Date(ts * 1000) : new Date(ts);
    }
    const s = String(ts).trim();
    if (!s) return new Date();
    // ISO-8601 (Instant) — must run before parseFloat: parseFloat("2026-03-21T…") === 2026
    if (s.includes('T') || /^\d{4}-\d{2}-\d{2}/.test(s)) {
        const ms = Date.parse(s);
        if (!Number.isNaN(ms)) return new Date(ms);
    }
    const n = parseFloat(s);
    if (!Number.isNaN(n)) {
        return n < 1e12 ? new Date(n * 1000) : new Date(n);
    }
    return new Date();
}
