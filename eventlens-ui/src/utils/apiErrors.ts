import axios from 'axios';

export interface ApiErrorDetails {
    status?: number;
    error?: string;
    reason?: string;
    permission?: string;
    message: string;
}

export function describeApiError(error: unknown): ApiErrorDetails {
    if (axios.isAxiosError(error)) {
        const status = error.response?.status;
        const data = (error.response?.data ?? {}) as Record<string, unknown>;
        return {
            status,
            error: typeof data.error === 'string' ? data.error : undefined,
            reason: typeof data.reason === 'string' ? data.reason : undefined,
            permission: typeof data.permission === 'string' ? data.permission : undefined,
            message:
                typeof data.message === 'string'
                    ? data.message
                    : typeof data.error === 'string'
                        ? data.error
                        : error.message || 'Request failed',
        };
    }

    if (error instanceof Error) {
        return { message: error.message };
    }

    return { message: 'Request failed' };
}
