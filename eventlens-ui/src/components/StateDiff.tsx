import { useState } from 'react';
import { FieldChange } from '../api/client';

interface Props {
    diff: Record<string, FieldChange>;
}

type ViewMode = 'inline' | 'split';

export default function StateDiff({ diff }: Props) {
    const entries = Object.entries(diff);
    const hasDiff = entries.length > 0;
    const [mode, setMode] = useState<ViewMode>('inline');

    if (!hasDiff) return null;

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

            {/* Inline / split modes */}
            <div className="diff-body">

                    <div className="diff-scroll">
                        {mode === 'inline' ? (
                            <div className="diff-list diff-list-inline">
                                {entries.map(([field, change], i) => (
                                    <div
                                        key={field}
                                        className="diff-row"
                                    >
                                        <span className="diff-line-no" aria-hidden>{i + 1}</span>
                                        <div className="diff-row-body">
                                            <span className="diff-field">{field}</span>
                                            <span className="diff-values-inline">
                                                <span className="diff-old">{JSON.stringify(change.oldValue)}</span>
                                                <span className="diff-arrow">→</span>
                                                <span className="diff-new">{JSON.stringify(change.newValue)}</span>
                                            </span>
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
                                        className="diff-split-row"
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
        </div>
    );
}
