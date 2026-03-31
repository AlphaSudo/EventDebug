import { FormEvent, useMemo, useState } from 'react';
import { applySetup, type SetupApplyResponse, type SetupStatusResponse } from '../api/client';

type SetupMode = 'basic' | 'oidc' | 'disabled';

interface SetupWizardProps {
    status: SetupStatusResponse;
    onApplied: (result: SetupApplyResponse) => void;
}

export default function SetupWizard({ status, onApplied }: SetupWizardProps) {
    const [mode, setMode] = useState<SetupMode>('basic');
    const [username, setUsername] = useState('admin');
    const [password, setPassword] = useState('');
    const [issuer, setIssuer] = useState('');
    const [clientId, setClientId] = useState('');
    const [clientSecret, setClientSecret] = useState('');
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    const modeCopy = useMemo(() => {
        switch (mode) {
            case 'basic':
                return 'Use a local username/password and enable the v5 browser-session flow on top of it.';
            case 'oidc':
                return 'Use your OpenID Connect provider and keep EventLens sessions server-side.';
            case 'disabled':
                return 'Skip authentication for local-only debugging. This is not safe for shared environments.';
        }
    }, [mode]);

    const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setError(null);
        setSubmitting(true);
        try {
            const result = await applySetup({
                mode,
                username,
                password,
                issuer,
                clientId,
                clientSecret,
            });
            onApplied(result);
        } catch (err) {
            const message = err instanceof Error ? err.message : 'Setup failed. Review the values and try again.';
            setError(message);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="app auth-shell">
            <main className="auth-screen" role="main" aria-label="Instance setup">
                <section className="auth-card setup-card">
                    <div className="auth-eyebrow">EventLens Setup</div>
                    <h1 className="auth-title">Secure this instance</h1>
                    <p className="auth-copy">
                        This instance has not been configured yet. Choose one security mode now, save it to
                        <span className="setup-path"> {status.configPath}</span>, then restart EventLens once.
                    </p>

                    <div className="setup-mode-grid" role="radiogroup" aria-label="Security mode">
                        <button
                            type="button"
                            className={`setup-mode-card ${mode === 'basic' ? 'setup-mode-card--active' : ''}`}
                            onClick={() => setMode('basic')}
                        >
                            <strong>Basic Auth</strong>
                            <span>Fastest protected path for a small team or local container.</span>
                        </button>
                        <button
                            type="button"
                            className={`setup-mode-card ${mode === 'oidc' ? 'setup-mode-card--active' : ''}`}
                            onClick={() => setMode('oidc')}
                        >
                            <strong>OIDC / SSO</strong>
                            <span>Use your identity provider and keep browser sessions server-side.</span>
                        </button>
                        <button
                            type="button"
                            className={`setup-mode-card ${mode === 'disabled' ? 'setup-mode-card--active' : ''}`}
                            onClick={() => setMode('disabled')}
                        >
                            <strong>Local Dev Only</strong>
                            <span>No authentication. Good only for a private local sandbox.</span>
                        </button>
                    </div>

                    <p className="setup-mode-copy">{modeCopy}</p>

                    <form className="auth-form" onSubmit={handleSubmit}>
                        {mode === 'basic' && (
                            <>
                                <label className="auth-field">
                                    <span className="auth-label">Username</span>
                                    <input
                                        className="auth-input"
                                        type="text"
                                        value={username}
                                        onChange={event => setUsername(event.target.value)}
                                        disabled={submitting}
                                        required
                                    />
                                </label>
                                <label className="auth-field">
                                    <span className="auth-label">Password</span>
                                    <input
                                        className="auth-input"
                                        type="password"
                                        value={password}
                                        onChange={event => setPassword(event.target.value)}
                                        disabled={submitting}
                                        required
                                    />
                                </label>
                            </>
                        )}

                        {mode === 'oidc' && (
                            <>
                                <label className="auth-field">
                                    <span className="auth-label">Issuer</span>
                                    <input
                                        className="auth-input"
                                        type="url"
                                        placeholder="https://id.example.com/realms/eventlens"
                                        value={issuer}
                                        onChange={event => setIssuer(event.target.value)}
                                        disabled={submitting}
                                        required
                                    />
                                </label>
                                <label className="auth-field">
                                    <span className="auth-label">Client ID</span>
                                    <input
                                        className="auth-input"
                                        type="text"
                                        value={clientId}
                                        onChange={event => setClientId(event.target.value)}
                                        disabled={submitting}
                                        required
                                    />
                                </label>
                                <label className="auth-field">
                                    <span className="auth-label">Client Secret</span>
                                    <input
                                        className="auth-input"
                                        type="password"
                                        value={clientSecret}
                                        onChange={event => setClientSecret(event.target.value)}
                                        disabled={submitting}
                                        required
                                    />
                                </label>
                            </>
                        )}

                        {mode === 'disabled' && (
                            <div className="setup-warning">
                                EventLens will save a local no-auth configuration. Use this only for a private developer workstation.
                            </div>
                        )}

                        {error && <div className="auth-error" role="alert">{error}</div>}
                        <button className="auth-submit" type="submit" disabled={submitting}>
                            {submitting ? 'Saving...' : 'Save setup'}
                        </button>
                    </form>
                </section>
            </main>
        </div>
    );
}
