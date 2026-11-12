-- Listings without `unit` were rejected; the decoder now defaults them to one listed unit. Forget the cursor
-- so the next full sync re-downloads the snapshot and imports them; cached rows stay until that sync commits.
DELETE FROM catalog_sync_state WHERE provider = 'catalogflow';
