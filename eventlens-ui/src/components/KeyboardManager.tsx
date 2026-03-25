import { useEffect } from 'react';

interface Props {
    paletteOpen: boolean;
    onOpenPalette: () => void;
    onClosePalette: () => void;
}

export default function KeyboardManager({ paletteOpen, onOpenPalette, onClosePalette }: Props) {
    useEffect(() => {
        const handler = (event: KeyboardEvent) => {
            const target = event.target as HTMLElement | null;
            const isTyping = target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
                event.preventDefault();
                onOpenPalette();
                return;
            }
            if (event.key === '/' && !isTyping) {
                event.preventDefault();
                document.getElementById('aggregate-search')?.focus();
                return;
            }
            if (event.key === 'Escape' && paletteOpen) {
                event.preventDefault();
                onClosePalette();
                return;
            }
            if (!isTyping && (event.key === 'j' || event.key === 'k')) {
                window.dispatchEvent(new CustomEvent('eventlens:timeline-step', { detail: event.key === 'j' ? 1 : -1 }));
            }
        };
        window.addEventListener('keydown', handler);
        return () => window.removeEventListener('keydown', handler);
    }, [paletteOpen, onClosePalette, onOpenPalette]);

    return null;
}
