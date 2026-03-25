import { useState, useEffect } from 'react';

const SHORTCUTS = [
    { keys: 'j / k', desc: 'Move selection through the timeline' },
    { keys: 'Shift+Click', desc: 'Pick a compare event for diff mode' },
    { keys: '1 - 3', desc: 'Switch state tabs' },
    { keys: 'Ctrl/Cmd+K', desc: 'Open command palette' },
    { keys: '/', desc: 'Focus aggregate search' },
    { keys: 'Space', desc: 'Pause or resume live stream' },
    { keys: '?', desc: 'Toggle shortcut help' },
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
                    <button type="button" className="keyboard-hints-close" onClick={() => setExpanded(false)} aria-label="Close shortcuts">
                        Close
                    </button>
                </div>
            ) : (
                <div className="keyboard-hints-bar">
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">j/k</kbd> Navigate</span>
                    <span className="keyboard-hints-sep">·</span>
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">Shift+Click</kbd> Compare</span>
                    <span className="keyboard-hints-sep">·</span>
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">Ctrl/Cmd+K</kbd> Palette</span>
                    <span className="keyboard-hints-sep">·</span>
                    <span className="keyboard-hints-item"><kbd className="keyboard-key-mini">?</kbd> All shortcuts</span>
                </div>
            )}
        </div>
    );
}
