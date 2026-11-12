-- CatalogFlow prices were dropped for listings carrying a `stores` array. Forget the cursor so the next
-- full sync re-downloads the snapshot and re-normalizes every offer; cached rows stay until that sync commits.
DELETE FROM catalog_sync_state WHERE provider = 'catalogflow';
