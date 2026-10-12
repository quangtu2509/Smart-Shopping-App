CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED')),
    failed_logins INTEGER NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE profiles (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    household_size INTEGER CHECK (household_size > 0),
    default_period TEXT,
    preferred_category_ids TEXT[] NOT NULL DEFAULT '{}',
    avoided_category_ids TEXT[] NOT NULL DEFAULT '{}',
    preferred_brand_ids TEXT[] NOT NULL DEFAULT '{}',
    avoided_brand_ids TEXT[] NOT NULL DEFAULT '{}',
    max_stores INTEGER NOT NULL DEFAULT 3 CHECK (max_stores BETWEEN 1 AND 3),
    optimization_mode TEXT NOT NULL DEFAULT 'BALANCED'
        CHECK (optimization_mode IN ('CHEAPEST','CONVENIENT','BALANCED'))
);

CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    access_hash CHAR(64) NOT NULL UNIQUE,
    access_expires_at TIMESTAMPTZ NOT NULL,
    current_refresh_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    device_name VARCHAR(100)
);
CREATE INDEX auth_sessions_owner ON auth_sessions(user_id, created_at, id);

-- Retain used hashes until the session is removed so refresh replay can revoke it.
CREATE TABLE refresh_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES auth_sessions(id),
    used_at TIMESTAMPTZ
);

-- Hashes of route + peer address; no credentials. Shared across backend processes.
CREATE TABLE auth_rate_limits (
    bucket_hash CHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL CHECK (attempts > 0)
);
