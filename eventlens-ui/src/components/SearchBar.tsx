import { useState, useRef, useEffect, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { searchAggregates } from '../api/client';

interface Props {
    onSelect: (id: string) => void;
}

function useDebounce<T>(value: T, delay: number): T {
    const [debounced, setDebounced] = useState(value);
    useEffect(() => {
        const id = setTimeout(() => setDebounced(value), delay);
        return () => clearTimeout(id);
    }, [value, delay]);
    return debounced;
}

export default function SearchBar({ onSelect }: Props) {
    const [query, setQuery] = useState('');
    const [open, setOpen] = useState(false);
    const wrapperRef = useRef<HTMLDivElement>(null);
    const debouncedQuery = useDebounce(query, 300);

    const { data: results = [] } = useQuery({
        queryKey: ['search', debouncedQuery],
        queryFn: () => searchAggregates(debouncedQuery),
        enabled: debouncedQuery.length >= 2,
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

    // Cmd/Ctrl+K — focus via custom event from Timeline keyboard handler
    const inputRef = useRef<HTMLInputElement>(null);
    const focus = useCallback(() => {
        inputRef.current?.focus();
        inputRef.current?.select();
    }, []);
    useEffect(() => {
        document.getElementById('aggregate-search')?.addEventListener('focus', focus as never);
    }, [focus]);

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
                ref={inputRef}
                type="text"
                className="search-input"
                placeholder="Search by aggregate ID (e.g. UUID or stream key)"
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
                            type="button"
                            className="search-result-item"
                            onClick={() => handleSelect(id)}
                            role="option"
                        >
                            <span className="search-result-chevron" aria-hidden>→</span>
                            <span className="search-result-body">
                                <span className="search-result-label">ID</span>
                                <span className="search-result-colon">:</span>
                                <span className="search-result-value">{id}</span>
                            </span>
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
}
