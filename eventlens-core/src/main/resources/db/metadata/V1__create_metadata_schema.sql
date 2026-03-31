CREATE TABLE IF NOT EXISTS sessions (
    session_id TEXT PRIMARY KEY,
    principal_user_id TEXT NOT NULL,
    display_name TEXT,
    auth_method TEXT NOT NULL,
    roles_json TEXT NOT NULL DEFAULT '[]',
    attributes_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    idle_expires_at TEXT NOT NULL,
    absolute_expires_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_idle_expires_at
    ON sessions(idle_expires_at);

CREATE TABLE IF NOT EXISTS api_keys (
    api_key_id TEXT PRIMARY KEY,
    key_prefix TEXT NOT NULL UNIQUE,
    key_hash TEXT NOT NULL,
    description TEXT,
    principal_user_id TEXT NOT NULL,
    scopes_json TEXT NOT NULL DEFAULT '[]',
    created_at TEXT NOT NULL,
    expires_at TEXT,
    revoked_at TEXT,
    last_used_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_api_keys_revoked_at
    ON api_keys(revoked_at);

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
    action TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT,
    user_id TEXT NOT NULL,
    auth_method TEXT NOT NULL,
    client_ip TEXT,
    request_id TEXT,
    user_agent TEXT,
    details_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_created_at
    ON audit_log(created_at);

CREATE INDEX IF NOT EXISTS idx_audit_log_user_id
    ON audit_log(user_id);

CREATE TRIGGER IF NOT EXISTS audit_log_prevent_update
BEFORE UPDATE ON audit_log
BEGIN
    SELECT RAISE(ABORT, 'Updates are prohibited on audit_log');
END;

CREATE TRIGGER IF NOT EXISTS audit_log_prevent_delete
BEFORE DELETE ON audit_log
BEGIN
    SELECT RAISE(ABORT, 'Deletes are prohibited on audit_log');
END;
