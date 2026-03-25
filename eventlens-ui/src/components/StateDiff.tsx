import type { DiffPatch, FieldChange } from '../api/client';

interface Props {
    diff?: Record<string, FieldChange>;
    patches?: DiffPatch[];
    title?: string;
}

type Row = {
    field: string;
    oldValue: unknown;
    newValue: unknown;
    kind: 'added' | 'removed' | 'changed';
};

export default function StateDiff({ diff, patches, title = 'Changes' }: Props) {
    const rows: Row[] = patches && patches.length > 0
        ? patches.map(patch => ({
            field: patch.path,
            oldValue: patch.oldValue,
            newValue: patch.newValue,
            kind: patch.type,
        }))
        : Object.entries(diff ?? {}).map(([field, change]) => ({
            field,
            oldValue: change.oldValue,
            newValue: change.newValue,
            kind: 'changed',
        }));

    if (!rows.length) return null;

    return (
        <div className="diff-panel">
            <div className="diff-toolbar">
                <div className="diff-toolbar-title">
                    {title}
                    <span className="diff-count-badge" aria-live="polite">
                        {rows.length} {rows.length === 1 ? 'change' : 'changes'}
                    </span>
                </div>
            </div>
            <div className="diff-body">
                <div className="diff-scroll">
                    <div className="diff-list diff-list-split">
                        <div className="diff-split-head">
                            <span className="diff-split-label diff-split-old-label">Before</span>
                            <span className="diff-split-label diff-split-new-label">After</span>
                        </div>
                        {rows.map((row, index) => (
                            <div key={`${row.field}-${index}`} className={`diff-split-row diff-split-row--${row.kind}`}>
                                <span className="diff-line-no" aria-hidden>{index + 1}</span>
                                <div className="diff-split-cells">
                                    <div className="diff-split-cell diff-split-old">
                                        <span className="diff-field">{row.field}</span>
                                        <span className="diff-cell-value">{JSON.stringify(row.oldValue)}</span>
                                    </div>
                                    <div className="diff-split-cell diff-split-new">
                                        <span className="diff-field">{row.field}</span>
                                        <span className="diff-cell-value">{JSON.stringify(row.newValue)}</span>
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
}
