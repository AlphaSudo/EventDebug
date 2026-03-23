/**
 * Frontend-only demo dataset — no backend changes.
 * Enable with `VITE_EVENTLENS_DEMO=true` (e.g. in `.env.development.local`).
 */
export function isDemoMode(): boolean {
    return import.meta.env.VITE_EVENTLENS_DEMO === 'true';
}
