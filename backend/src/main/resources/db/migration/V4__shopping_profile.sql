-- Internal reference data is populated by the catalog/data workflow; no provider data is invented here.
CREATE TABLE shopping_categories (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    parent_id TEXT REFERENCES shopping_categories(id)
);
CREATE TABLE shopping_brands (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE TABLE profile_requests (
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    request_key TEXT NOT NULL,
    payload TEXT NOT NULL,
    response TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_id, request_key)
);
-- Both reference tables and profiles are retained; this only removes replay entries when test users are reset.
