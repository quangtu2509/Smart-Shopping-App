ALTER TABLE catalog_provider_entries ADD COLUMN source_ids TEXT[] NOT NULL DEFAULT '{}';
CREATE INDEX catalog_provider_entry_sources ON catalog_provider_entries USING GIN(source_ids);
