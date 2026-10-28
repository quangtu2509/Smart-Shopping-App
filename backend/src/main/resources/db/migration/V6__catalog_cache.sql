-- CATALOG-01: references/payloads remain in the DATA-00 tables; provider/cache lifecycle lives here.
CREATE TABLE catalog_sources (
    retailer_id TEXT PRIMARY KEY REFERENCES shopping_retailers(id),
    site TEXT NOT NULL UNIQUE, status TEXT NOT NULL CHECK (status IN ('READY','PARTIAL','EMPTY','FAILED')),
    error_code TEXT, retry_until TIMESTAMPTZ, fetched_at TIMESTAMPTZ
);
CREATE TABLE catalog_entries (
    offer_id TEXT PRIMARY KEY, kind TEXT NOT NULL DEFAULT 'CATALOG' CHECK (kind='CATALOG'),
    retailer_id TEXT NOT NULL REFERENCES shopping_retailers(id),
    category_ids TEXT[] NOT NULL, active BOOLEAN NOT NULL, fetched_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(kind,offer_id) REFERENCES shopping_offers(kind,id) ON DELETE CASCADE
);
CREATE INDEX catalog_entries_context ON catalog_entries(retailer_id,active,offer_id);
CREATE INDEX catalog_entries_categories ON catalog_entries USING GIN(category_ids);
CREATE TABLE catalog_cache_version (id INTEGER PRIMARY KEY CHECK(id=1), version BIGINT NOT NULL);
INSERT INTO catalog_cache_version VALUES(1,1);
CREATE TABLE catalog_refresh_jobs (
    plan_id UUID NOT NULL REFERENCES shopping_plans(id) ON DELETE CASCADE,
    version INTEGER NOT NULL, owner_id UUID NOT NULL REFERENCES app_users(id),
    status TEXT NOT NULL CHECK(status IN ('QUEUED','RUNNING','DONE')),
    lease_until TIMESTAMPTZ, PRIMARY KEY(plan_id,version)
);
