import { describe, expect, it } from 'vitest';
import { describeApiError } from './apiErrors';

describe('describeApiError', () => {
    it('extracts structured fields from axios-style errors', () => {
        const details = describeApiError({
            isAxiosError: true,
            message: 'Request failed with status code 403',
            response: {
                status: 403,
                data: {
                    error: 'forbidden',
                    reason: 'DENY_MISSING_PERMISSION',
                    permission: 'VIEW_AUDIT_LOG',
                },
            },
        });

        expect(details.status).toBe(403);
        expect(details.error).toBe('forbidden');
        expect(details.reason).toBe('DENY_MISSING_PERMISSION');
        expect(details.permission).toBe('VIEW_AUDIT_LOG');
    });

    it('falls back to error message for non-axios errors', () => {
        const details = describeApiError(new Error('boom'));
        expect(details.message).toBe('boom');
    });
});
