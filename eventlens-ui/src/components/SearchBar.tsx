import { useState, useRef, useEffect } from 'react';
import { useQuery } from '@tanstack/react-query';
import { searchAggregates } from '../api/client';

interface Props {
    onSelect: (id: string) => void;
}

export default function SearchBar({ onSelect }: Props) {
    const [query, setQuery] = useState('');
    const [open, setOpen] = useState(false);
    const wrapperRef = useRef<HTMLDivElement>(null);

    const { data: results = [] } = useQuery({
        queryKey: ['search', query],
        queryFn: () => searchAggregates(query),
        enabled: query.length >= 2,
        staleTime: 5_000,
    });

    // Close dropdown on outside click
    useEffect(() => {
        const handler = (e: MouseEvent) => {
            if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
                setOpen(false);
            }
        };
        document.addEventListener('mousedown', handler);
        return () => document.removeEventListener('mousedown', handler);
    }, []);

    const handleSelect = (id: string) => {
        setQuery(id);
        setOpen(false);
        onSelect(id);
    };

    return (
        <div className="search-wrapper" ref={wrapperRef}>
            <span className="search-icon">🔎</span>
            <input
                id="aggregate-search"
                type="text"
                className="search-input"
                placeholder="Search for an aggregate ID (e.g. ACC-001)"
                value={query}
                onChange={e => { setQuery(e.target.value); setOpen(true); }}
                onFocus={() => query.length >= 2 && setOpen(true)}
                onKeyDown={e => {
                    if (e.key === 'Enter' && query.trim()) {
                        handleSelect(query.trim());
                    }
                    if (e.key === 'Escape') setOpen(false);
                }}
                autoComplete="off"
            />
            {open && results.length > 0 && (
                <div className="search-results" role="listbox">
                    {results.map(id => (
                        <button
                            key={id}
                            className="search-result-item"
                            onClick={() => handleSelect(id)}
                            role="option"
                        >
                            <span style={{ color: 'var(--text-muted)' }}>→</span>
                            <span>{id}</span>
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
}
