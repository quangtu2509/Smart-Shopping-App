-- Cursor and imported changes commit atomically. Existing shopping snapshots remain intact.
CREATE TABLE catalog_sync_state (
    provider TEXT PRIMARY KEY, dataset TEXT NOT NULL, cursor TEXT NOT NULL
);
CREATE TABLE catalog_provider_entries (
    provider TEXT NOT NULL, upstream_id TEXT NOT NULL,
    offer_id TEXT NOT NULL REFERENCES catalog_entries(offer_id) ON DELETE CASCADE,
    PRIMARY KEY(provider,upstream_id)
);
