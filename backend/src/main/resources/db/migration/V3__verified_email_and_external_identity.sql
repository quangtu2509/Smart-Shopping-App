ALTER TABLE app_users ADD COLUMN email_verified_at TIMESTAMPTZ;
ALTER TABLE app_users ALTER COLUMN password_hash DROP NOT NULL;
-- Existing accounts retain their data, but their former sessions cannot bypass verification.
UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP);

CREATE TABLE auth_identities (
    provider TEXT NOT NULL CHECK (provider = 'GOOGLE'),
    subject TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES app_users(id),
    PRIMARY KEY(provider, subject),
    UNIQUE(provider, user_id)
);
CREATE TABLE auth_challenges (
    id TEXT PRIMARY KEY,
    purpose TEXT NOT NULL CHECK (purpose IN ('REGISTER','LOGIN','RESET','RESET_GRANT','GOOGLE','LINK')),
    email VARCHAR(254) NOT NULL DEFAULT '',
    user_id UUID REFERENCES app_users(id),
    secret_hash CHAR(64) NOT NULL,
    password_hash TEXT,
    subject TEXT,
    device_name VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    consumed_at TIMESTAMPTZ
);
CREATE INDEX auth_challenge_owner ON auth_challenges(user_id, purpose);
CREATE INDEX auth_challenge_expiry ON auth_challenges(expires_at);
