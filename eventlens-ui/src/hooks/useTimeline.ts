import { useQuery } from '@tanstack/react-query';
import { getTimeline } from '../api/client';

export function useTimeline(aggregateId: string, source?: string | null) {
    return useQuery({
        queryKey: ['timeline', aggregateId, source ?? 'default', 'metadata'],
        queryFn: () => getTimeline(aggregateId, 500, 0, source, 'metadata'),
        enabled: !!aggregateId,
    });
}
