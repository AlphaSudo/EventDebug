import { useQuery } from '@tanstack/react-query';
import { getTransitions } from '../api/client';

export function useReplay(aggregateId: string) {
    const query = useQuery({
        queryKey: ['transitions', aggregateId],
        queryFn: () => getTransitions(aggregateId),
    });

    return query;
}

