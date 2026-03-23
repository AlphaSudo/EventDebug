/**
 * Frontend-only demo dataset — no backend changes.
 *
 * Disabled by default. To enable, set both:
 * - `VITE_EVENTLENS_DEMO=true`
 * - `VITE_EVENTLENS_DEMO_ALLOW=true`
 *
 * (The extra `*_ALLOW` guard prevents demo mode from being accidentally enabled.)
 */
export function isDemoMode(): boolean {
    return import.meta.env.VITE_EVENTLENS_DEMO === 'true' && import.meta.env.VITE_EVENTLENS_DEMO_ALLOW === 'true';
}
