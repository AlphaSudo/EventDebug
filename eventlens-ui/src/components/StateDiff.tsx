import { FieldChange } from '../api/client';

interface Props {
    diff: Record<string, FieldChange>;
}

export default function StateDiff({ diff }: Props) {
    const hasDiff = Object.keys(diff).length > 0;
    if (!hasDiff) return null;

    return (
        <div className="diff-list">
            <div className="card-title" style={{ marginTop: 12, marginBottom: 8 }}>Changes</div>
            {Object.entries(diff).map(([field, change]) => (
                <div key={field} className="diff-row">
                    <span className="diff-field">{field}:</span>
                    <span className="diff-old">{JSON.stringify(change.oldValue) ?? 'undefined'}</span>
                    <span className="diff-arrow">→</span>
                    <span className="diff-new">{JSON.stringify(change.newValue) ?? 'undefined'}</span>
                </div>
            ))}
        </div>
    );
}

