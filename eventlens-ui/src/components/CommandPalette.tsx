import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { searchAggregates } from '../api/client';

interface Props {
    open: boolean;
    selectedSource?: string | null;
    onClose: () => void;
    onSelectAggregate: (id: string) => void;
    onOpenHome: () => void;
    onOpenStats: () => void;
}

interface CommandItem {
    id: string;
    label: string;
    action: () => void;
}

export default function CommandPalette({ open, selectedSource, onClose, onSelectAggregate, onOpenHome, onOpenStats }: Props) {
    const [query, setQuery] = useState('');
    const [selectedIndex, setSelectedIndex] = useState(0);
    const [previousFocus, setPreviousFocus] = useState<HTMLElement | null>(null);
    const { data: aggregateResults = [] } = useQuery({
        queryKey: ['palette-search', query, selectedSource ?? 'default'],
        queryFn: () => searchAggregates(query, 8, selectedSource),
        enabled: open && query.trim().length >= 2,
        staleTime: 5_000,
    });

    useEffect(() => {
        if (open) {
            setSelectedIndex(0);
            setPreviousFocus(document.activeElement instanceof HTMLElement ? document.activeElement : null);
        } else {
            setQuery('');
            previousFocus?.focus();
        }
    }, [open, previousFocus]);

    const commands = useMemo<CommandItem[]>(() => {
        const base: CommandItem[] = [
            { id: 'home', label: 'Go to main page', action: onOpenHome },
            { id: 'stats', label: 'Go to statistics panel', action: onOpenStats },
        ];
        const aggregateCommands = aggregateResults.map(id => ({
            id: `agg-${id}`,
            label: `Open aggregate ${id}`,
            action: () => onSelectAggregate(id),
        }));
        if (query.trim() && aggregateResults.length === 0) {
            base.unshift({ id: 'direct', label: `Open aggregate ${query.trim()}`, action: () => onSelectAggregate(query.trim()) });
        }
        return [...aggregateCommands, ...base];
    }, [aggregateResults, onOpenHome, onOpenStats, onSelectAggregate, query]);

    useEffect(() => {
        if (!open) return;
        const handler = (event: KeyboardEvent) => {
            if (event.key === 'ArrowDown') {
                event.preventDefault();
                setSelectedIndex(index => Math.min(index + 1, Math.max(commands.length - 1, 0)));
            } else if (event.key === 'ArrowUp') {
                event.preventDefault();
                setSelectedIndex(index => Math.max(index - 1, 0));
            } else if (event.key === 'Enter') {
                event.preventDefault();
                commands[selectedIndex]?.action();
                onClose();
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [commands, onClose, open, selectedIndex]);

    if (!open) return null;

    return (
        <div className="command-palette-backdrop" onClick={onClose}>
            <div className="command-palette" role="dialog" aria-modal="true" aria-label="Command palette" aria-describedby="command-palette-help" onClick={e => e.stopPropagation()}>
                <p id="command-palette-help" className="sr-only">Search aggregates, return to the main page, or open statistics. Use arrow keys to move and Enter to confirm.</p>
                <input
                    autoFocus
                    className="command-palette-input"
                    placeholder="Search aggregates or jump to a panel"
                    value={query}
                    onChange={e => setQuery(e.target.value)}
                    aria-label="Command palette search"
                />
                <ul className="command-palette-list" role="listbox" aria-label="Command results">
                    {commands.map((item, index) => (
                        <li
                            key={item.id}
                            className={`command-palette-item ${index === selectedIndex ? 'active' : ''}`}
                            role="option"
                            aria-selected={index === selectedIndex}
                            onMouseEnter={() => setSelectedIndex(index)}
                            onClick={() => {
                                item.action();
                                onClose();
                            }}
                        >
                            {item.label}
                        </li>
                    ))}
                    {commands.length === 0 && <li className="command-palette-item muted">Type at least two characters to search.</li>}
                </ul>
            </div>
        </div>
    );
}
