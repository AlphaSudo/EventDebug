import { useQuery } from '@tanstack/react-query';
import { getTransitions } from '../api/client';

export function useTimeline(aggregateId: string, source?: string | null) {
    return useQuery({
        queryKey: ['transitions', aggregateId, source ?? 'default'],
        queryFn: () => getTransitions(aggregateId, source),
    });
}
