import { useRef, useState } from 'react';
import { FieldChange } from '../api/client';

interface Props {
    diff: Record<string, FieldChange>;
}

type ViewMode = 'summary' | 'inline' | 'split';

function formatValue(v: unknown): string {
    if (v === null) return 'null';
    if (typeof v === 'string') return v.length > 60 ? `"${v.slice(0, 57)}…"` : `"${v}"`;
    return JSON.stringify(v);
}

export default function StateDiff({ diff }: Props) {
    const entries = Object.entries(diff);
    const hasDiff = entries.length > 0;
    const [mode, setMode] = useState<ViewMode>('summary');
    const rowRefs = useRef<(HTMLDivElement | null)[]>([]);
    const scrollerRef = useRef<HTMLDivElement>(null);

    if (!hasDiff) return null;

    const showMinimap = entries.length > 5 && mode !== 'summary';

    const scrollToRow = (i: number) => {
        rowRefs.current[i]?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    };

    const jumpToNext = (currentIdx: number) => {
        const next = (currentIdx + 1) % entries.length;
        scrollToRow(next);
    };

    return (
        <div className="diff-panel">
            <div className="diff-toolbar">
                <div className="diff-toolbar-title">
                    Changes
                    <span className="diff-count-badge">{entries.length} {entries.length === 1 ? 'field' : 'fields'} modified</span>
                </div>
                <div className="diff-view-toggle" role="group" aria-label="Diff layout">
                    <button
                        type="button"
                        className={mode === 'summary' ? 'active' : ''}
                        onClick={() => setMode('summary')}
                    >
                        Summary
                    </button>
                    <button
                        type="button"
                        className={mode === 'inline' ? 'active' : ''}
                        onClick={() => setMode('inline')}
                    >
                        Inline
                    </button>
                    <button
                        type="button"
                        className={mode === 'split' ? 'active' : ''}
                        onClick={() => setMode('split')}
                    >
                        Side by side
                    </button>
                </div>
            </div>

            {/* Summary mode — human-readable at a glance */}
            {mode === 'summary' && (
                <div className="diff-summary-view">
                    {entries.map(([field, change]) => (
                        <div key={field} className="diff-summary-row">
                            <span className="diff-summary-field">{field}</span>
                            <span className="diff-summary-old">{formatValue(change.oldValue)}</span>
                            <span className="diff-summary-arrow">→</span>
                            <span className="diff-summary-new">{formatValue(change.newValue)}</span>
                        </div>
                    ))}
                </div>
            )}

            {/* Inline / split modes */}
            {mode !== 'summary' && (
                <div className="diff-body">
                    {showMinimap && (
                        <div className="diff-minimap" title="Jump to change">
                            {entries.map((_, i) => (
                                <button
                                    key={i}
                                    type="button"
                                    className="diff-minimap-chunk"
                                    onClick={() => scrollToRow(i)}
                                    aria-label={`Change ${i + 1} of ${entries.length}`}
                                />
                            ))}
                        </div>
                    )}

                    <div className="diff-scroll" ref={scrollerRef}>
                        {mode === 'inline' ? (
                            <div className="diff-list diff-list-inline">
                                {entries.map(([field, change], i) => (
                                    <div
                                        key={field}
                                        ref={el => { rowRefs.current[i] = el; }}
                                        className="diff-row"
                                        data-diff-index={i}
                                    >
                                        <span className="diff-line-no" aria-hidden>{i + 1}</span>
                                        <div className="diff-row-body">
                                            <span className="diff-field">{field}</span>
                                            <span className="diff-values-inline">
                                                <span className="diff-old">{JSON.stringify(change.oldValue)}</span>
                                                <span className="diff-arrow">→</span>
                                                <span className="diff-new">{JSON.stringify(change.newValue)}</span>
                                            </span>
                                            <button
                                                type="button"
                                                className="diff-jump-next"
                                                onClick={() => jumpToNext(i)}
                                                title="Jump to next change"
                                                aria-label="Jump to next change"
                                            >
                                                ↓ next
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <div className="diff-list diff-list-split">
                                <div className="diff-split-head">
                                    <span className="diff-split-label diff-split-old-label">Before</span>
                                    <span className="diff-split-label diff-split-new-label">After</span>
                                </div>
                                {entries.map(([field, change], i) => (
                                    <div
                                        key={field}
                                        ref={el => { rowRefs.current[i] = el; }}
                                        className="diff-split-row"
                                        data-diff-index={i}
                                    >
                                        <span className="diff-line-no" aria-hidden>{i + 1}</span>
                                        <div className="diff-split-cells">
                                            <div className="diff-split-cell diff-split-old">
                                                <span className="diff-field">{field}</span>
                                                <span className="diff-cell-value">{JSON.stringify(change.oldValue)}</span>
                                            </div>
                                            <div className="diff-split-cell diff-split-new">
                                                <span className="diff-field">{field}</span>
                                                <span className="diff-cell-value">{JSON.stringify(change.newValue)}</span>
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
