import { FormEvent, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
    createManagedApiKey,
    getAuditEntries,
    getManagedApiKeys,
    revokeManagedApiKey,
} from '../api/client';
import { useToast } from './ToastProvider';
import { describeApiError } from '../utils/apiErrors';

function SecurityStateCard({
    title,
    detail,
    variant = 'error',
}: {
    title: string;
    detail: string;
    variant?: 'error' | 'warning';
}) {
    return (
        <div className={`admin-state admin-state--${variant}`} role="alert">
            <strong>{title}</strong>
            <p>{detail}</p>
        </div>
    );
}

export default function AdminConsole() {
    const queryClient = useQueryClient();
    const { notify } = useToast();
    const [principalUserId, setPrincipalUserId] = useState('');
    const [roles, setRoles] = useState('api-reader');
    const [description, setDescription] = useState('');
    const [expiresAt, setExpiresAt] = useState('');
    const [issuedKey, setIssuedKey] = useState<{ apiKey: string; keyPrefix: string } | null>(null);

    const auditQuery = useQuery({
        queryKey: ['admin-audit'],
        queryFn: () => getAuditEntries(25),
        retry: false,
        refetchOnWindowFocus: false,
    });

    const apiKeysQuery = useQuery({
        queryKey: ['admin-api-keys'],
        queryFn: getManagedApiKeys,
        retry: false,
        refetchOnWindowFocus: false,
    });

    const createKeyMutation = useMutation({
        mutationFn: createManagedApiKey,
        onSuccess: async created => {
            setIssuedKey({ apiKey: created.apiKey, keyPrefix: created.keyPrefix });
            setPrincipalUserId('');
            setRoles('api-reader');
            setDescription('');
            setExpiresAt('');
            notify(`Created API key ${created.keyPrefix}`);
            await queryClient.invalidateQueries({ queryKey: ['admin-api-keys'] });
            await queryClient.invalidateQueries({ queryKey: ['admin-audit'] });
        },
    });

    const revokeKeyMutation = useMutation({
        mutationFn: revokeManagedApiKey,
        onSuccess: async revoked => {
            notify(`Revoked API key ${revoked.apiKeyId}`);
            await queryClient.invalidateQueries({ queryKey: ['admin-api-keys'] });
            await queryClient.invalidateQueries({ queryKey: ['admin-audit'] });
        },
    });

    const handleCreateKey = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setIssuedKey(null);
        await createKeyMutation.mutateAsync({
            principalUserId: principalUserId.trim(),
            roles: roles.split(',').map(role => role.trim()).filter(Boolean),
            description: description.trim() || undefined,
            expiresAt: expiresAt.trim() || undefined,
        });
    };

    const auditError = auditQuery.error ? describeApiError(auditQuery.error) : null;
    const apiKeysError = apiKeysQuery.error ? describeApiError(apiKeysQuery.error) : null;
    const createError = createKeyMutation.error ? describeApiError(createKeyMutation.error) : null;

    return (
        <section className="admin-console" aria-label="Security administration">
            <div className="card admin-hero">
                <div className="card-title">Security Administration</div>
                <p className="admin-hero-copy">
                    Review recent security activity, issue machine credentials, and revoke keys without leaving the current EventLens workspace.
                </p>
            </div>

            <div className="admin-grid">
                <div className="card admin-card">
                    <div className="card-title">Recent Audit Activity</div>
                    {auditQuery.isLoading && <p className="admin-muted">Loading the latest audit entries from metadata-backed storage.</p>}
                    {auditError?.status === 403 && (
                        <SecurityStateCard
                            title="Audit access denied"
                            detail={`This session cannot view the audit log${auditError.permission ? ` (${auditError.permission})` : ''}.`}
                            variant="warning"
                        />
                    )}
                    {auditError && auditError.status !== 403 && (
                        <SecurityStateCard
                            title="Audit unavailable"
                            detail={auditError.message}
                        />
                    )}
                    {!auditQuery.isLoading && !auditError && (
                        <div className="admin-table-wrap">
                            <table className="admin-table">
                                <thead>
                                    <tr>
                                        <th>When</th>
                                        <th>Action</th>
                                        <th>Actor</th>
                                        <th>Auth</th>
                                        <th>Resource</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {auditQuery.data?.entries.map(entry => (
                                        <tr key={`${entry.auditId}-${entry.requestId ?? entry.createdAt}`}>
                                            <td>{new Date(entry.createdAt).toLocaleString()}</td>
                                            <td>{entry.action}</td>
                                            <td>{entry.userId}</td>
                                            <td>{entry.authMethod}</td>
                                            <td>{entry.resourceType}{entry.resourceId ? `:${entry.resourceId}` : ''}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>

                <div className="card admin-card">
                    <div className="card-title">API Key Management</div>
                    <p className="admin-muted">
                        Issue one-time machine credentials tied to existing RBAC roles. Raw keys are shown once and never listed again.
                    </p>

                    {apiKeysError?.status === 403 ? (
                        <SecurityStateCard
                            title="API key management denied"
                            detail={`This session cannot manage API keys${apiKeysError.permission ? ` (${apiKeysError.permission})` : ''}.`}
                            variant="warning"
                        />
                    ) : apiKeysError ? (
                        <SecurityStateCard title="API key management unavailable" detail={apiKeysError.message} />
                    ) : (
                        <>
                            <form className="admin-form" onSubmit={handleCreateKey}>
                                <label className="admin-field">
                                    <span className="auth-label">Principal User Id</span>
                                    <input className="auth-input" value={principalUserId} onChange={event => setPrincipalUserId(event.target.value)} required />
                                </label>
                                <label className="admin-field">
                                    <span className="auth-label">Roles</span>
                                    <input className="auth-input" value={roles} onChange={event => setRoles(event.target.value)} required />
                                </label>
                                <label className="admin-field">
                                    <span className="auth-label">Description</span>
                                    <input className="auth-input" value={description} onChange={event => setDescription(event.target.value)} />
                                </label>
                                <label className="admin-field">
                                    <span className="auth-label">Expires At (ISO-8601)</span>
                                    <input className="auth-input" value={expiresAt} onChange={event => setExpiresAt(event.target.value)} placeholder="2026-04-01T00:00:00Z" />
                                </label>
                                {createError && <SecurityStateCard title="Key creation failed" detail={createError.message} />}
                                <button className="auth-submit" type="submit" disabled={createKeyMutation.isPending}>
                                    {createKeyMutation.isPending ? 'Issuing key...' : 'Issue API key'}
                                </button>
                            </form>

                            {issuedKey && (
                                <div className="admin-issued-key" role="status">
                                    <strong>Copy this key now.</strong>
                                    <p>{issuedKey.apiKey}</p>
                                    <span>Stored prefix: {issuedKey.keyPrefix}</span>
                                </div>
                            )}

                            <div className="admin-table-wrap">
                                <table className="admin-table">
                                    <thead>
                                        <tr>
                                            <th>Prefix</th>
                                            <th>Principal</th>
                                            <th>Roles</th>
                                            <th>Last used</th>
                                            <th>Status</th>
                                            <th />
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {apiKeysQuery.data?.entries.map(entry => (
                                            <tr key={entry.apiKeyId}>
                                                <td>{entry.keyPrefix}</td>
                                                <td>{entry.principalUserId}</td>
                                                <td>{entry.roles.join(', ')}</td>
                                                <td>{entry.lastUsedAt ? new Date(entry.lastUsedAt).toLocaleString() : 'Never'}</td>
                                                <td>{entry.revokedAt ? 'Revoked' : entry.expiresAt ? `Expires ${new Date(entry.expiresAt).toLocaleString()}` : 'Active'}</td>
                                                <td>
                                                    {!entry.revokedAt && (
                                                        <button
                                                            className="admin-inline-action"
                                                            type="button"
                                                            disabled={revokeKeyMutation.isPending}
                                                            onClick={() => revokeKeyMutation.mutate(entry.apiKeyId)}
                                                        >
                                                            Revoke
                                                        </button>
                                                    )}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </>
                    )}
                </div>
            </div>
        </section>
    );
}
