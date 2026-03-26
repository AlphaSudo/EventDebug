import { useState } from 'react';

function JsonToggle({ open, onToggle }: { open: boolean; onToggle: () => void }) {
    return (
        <button
            type="button"
            className="json-tree-toggle"
            onClick={e => {
                e.stopPropagation();
                onToggle();
            }}
            aria-expanded={open}
            aria-label={open ? 'Collapse' : 'Expand'}
        >
            {open ? '-' : '+'}
        </button>
    );
}

function JsonPrimitive({ value }: { value: string | number | boolean | null }) {
    if (value === null) {
        return <span className="json-null">null</span>;
    }
    if (typeof value === 'boolean') {
        return <span className="json-boolean">{String(value)}</span>;
    }
    if (typeof value === 'number') {
        return <span className="json-number">{value}</span>;
    }
    return <span className="json-string">{JSON.stringify(value)}</span>;
}

interface NodeProps {
    value: unknown;
    depth: number;
    propertyKey?: string;
    changedKeys?: Set<string>;
    keyPath?: string;
}

export default function JsonTreeView({ value, changedKeys }: { value: unknown; changedKeys?: Set<string> }) {
    return (
        <div className="json-tree json-tree-root">
            <JsonNode value={value} depth={0} changedKeys={changedKeys} keyPath="" />
        </div>
    );
}

function JsonNode({ value, depth, propertyKey, changedKeys, keyPath = '' }: NodeProps) {
    const isChanged = changedKeys && propertyKey !== undefined && changedKeys.has(propertyKey);
    const isParentChanged = changedKeys && keyPath && [...changedKeys].some(k => k.startsWith(keyPath + '.'));

    const autoOpen = changedKeys
        ? depth < 3 || !!isChanged || !!isParentChanged
        : depth < 3;
    const [open, setOpen] = useState(autoOpen);

    const pad = { paddingLeft: Math.min(depth, 12) * 14 };
    const highlightStyle = isChanged ? { background: 'rgba(255, 170, 0, 0.12)', borderRadius: 3 } : {};

    if (value === null || typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string') {
        return (
            <div className={`json-tree-line${isChanged ? ' json-tree-changed' : ''}`} style={{ ...pad, ...highlightStyle }}>
                {propertyKey !== undefined && (
                    <>
                        <span className="json-key">&quot;{propertyKey}&quot;</span>
                        <span className="json-punct">: </span>
                    </>
                )}
                <JsonPrimitive value={value as never} />
            </div>
        );
    }

    const childPath = (key: string) => keyPath ? `${keyPath}.${key}` : key;

    if (Array.isArray(value)) {
        if (value.length === 0) {
            return (
                <div className="json-tree-line" style={pad}>
                    {propertyKey !== undefined && (
                        <>
                            <span className="json-key">&quot;{propertyKey}&quot;</span>
                            <span className="json-punct">: </span>
                        </>
                    )}
                    <span className="json-punct">[]</span>
                </div>
            );
        }
        return (
            <div className="json-tree-branch">
                <div className={`json-tree-line${isChanged ? ' json-tree-changed' : ''}`} style={{ ...pad, ...highlightStyle }}>
                    <JsonToggle open={open} onToggle={() => setOpen(o => !o)} />
                    {propertyKey !== undefined && (
                        <>
                            <span className="json-key">&quot;{propertyKey}&quot;</span>
                            <span className="json-punct">: </span>
                        </>
                    )}
                    <span className="json-punct">[</span>
                    {!open && (
                        <>
                            <span className="json-ellipsis"> {value.length} items </span>
                            <span className="json-punct">]</span>
                        </>
                    )}
                </div>
                {open && (
                    <>
                        {value.map((item, i) => (
                            <JsonNode
                                key={i}
                                value={item}
                                depth={depth + 1}
                                changedKeys={changedKeys}
                                keyPath={childPath(String(i))}
                            />
                        ))}
                        <div className="json-tree-line" style={pad}>
                            <span className="json-punct">]</span>
                        </div>
                    </>
                )}
            </div>
        );
    }

    if (typeof value === 'object') {
        const entries = Object.entries(value as Record<string, unknown>);
        if (entries.length === 0) {
            return (
                <div className="json-tree-line" style={pad}>
                    {propertyKey !== undefined && (
                        <>
                            <span className="json-key">&quot;{propertyKey}&quot;</span>
                            <span className="json-punct">: </span>
                        </>
                    )}
                    <span className="json-punct">{'{}'}</span>
                </div>
            );
        }
        return (
            <div className="json-tree-branch">
                <div className={`json-tree-line${isChanged ? ' json-tree-changed' : ''}`} style={{ ...pad, ...highlightStyle }}>
                    <JsonToggle open={open} onToggle={() => setOpen(o => !o)} />
                    {propertyKey !== undefined && (
                        <>
                            <span className="json-key">&quot;{propertyKey}&quot;</span>
                            <span className="json-punct">: </span>
                        </>
                    )}
                    <span className="json-punct">{'{'}</span>
                    {!open && (
                        <>
                            <span className="json-ellipsis"> {entries.length} keys </span>
                            <span className="json-punct">{'}'}</span>
                        </>
                    )}
                </div>
                {open && (
                    <>
                        {entries.map(([k, v]) => (
                            <JsonNode
                                key={k}
                                value={v}
                                depth={depth + 1}
                                propertyKey={k}
                                changedKeys={changedKeys}
                                keyPath={childPath(k)}
                            />
                        ))}
                        <div className="json-tree-line" style={pad}>
                            <span className="json-punct">{'}'}</span>
                        </div>
                    </>
                )}
            </div>
        );
    }

    return (
        <div className="json-tree-line" style={pad}>
            <span className="json-unknown">{String(value)}</span>
        </div>
    );
}
