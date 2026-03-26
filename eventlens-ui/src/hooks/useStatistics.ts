import { useQuery } from '@tanstack/react-query';
import { getStatistics } from '../api/client';

export function useStatistics(source?: string | null, bucketHours = 1, maxBuckets = 24) {
    return useQuery({
        queryKey: ['statistics', source ?? 'default', bucketHours, maxBuckets],
        queryFn: () => getStatistics(source, bucketHours, maxBuckets),
        staleTime: 30_000,
    });
}
