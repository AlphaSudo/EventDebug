/**
 * EventLens API returns timestamps as Unix epoch seconds (possibly with
 * fractional nanoseconds, e.g. 1773233914.762024). JavaScript's Date
 * constructor expects milliseconds. This helper detects which format
 * the value is in and returns a valid Date.
 */
export function parseEventTimestamp(ts: string | number): Date {
    const n = typeof ts === 'string' ? parseFloat(ts) : ts;
    if (isNaN(n)) return new Date();
    // If the value is less than 1e12 it's seconds; otherwise milliseconds
    return n < 1e12 ? new Date(n * 1000) : new Date(n);
}
