import { useState, useEffect } from 'react';

const SHORTCUTS = [
    { keys: '← →', desc: 'Navigate events' },
    { keys: 'Shift+← →', desc: 'Jump to group boundary' },
    { keys: '1 – 3', desc: 'Switch tabs (Changes / ⇄ Before-After / Raw)' },
    { keys: 'Cmd+K', desc: 'Focus search' },
    { keys: 'Space', desc: 'Pause / resume live stream' },
    { keys: '?', desc: 'Toggle this hint bar' },
];

export default function KeyboardHints() {
    const [expanded, setExpanded] = useState(false);

    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if ((e.target as HTMLElement).tagName === 'INPUT') return;
            if (e.key === '?') {
                e.preventDefault();
                setExpanded(v => !v);
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, []);

    return (
        <div className={`keyboard-hints ${expanded ? 'keyboard-hints--expanded' : ''}`} aria-label="Keyboard shortcuts">
            {expanded ? (
                <div className="keyboard-hints-grid">
                    {SHORTCUTS.map(s => (
                        <div key={s.keys} className="keyboard-hint-row">
                            <kbd className="keyboard-key">{s.keys}</kbd>
                            <span className="keyboard-hint-desc">{s.desc}</span>
                        </div>
                    ))}
                    <button
                        type="button"
                        className="keyboard-hints-close"
                        onClick={() => setExpanded(false)}
                        aria-label="Close shortcuts"
                    >
                        ✕ Close
                    </button>
                </div>
            ) : (
                <div className="keyboard-hints-bar">
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">← →</kbd> Navigate</span>
                    <span className="keyboard-hints-sep">·</span>
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">1–3</kbd> Tabs</span>
                    <span className="keyboard-hints-sep">·</span>
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">Space</kbd> Pause stream</span>
                    <span className="keyboard-hints-sep">·</span>
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">?</kbd> All shortcuts</span>
                </div>
            )}
        </div>
    );
}
