-- API-PLAN-05: an unfinalized Plan can be deleted, but shopping_audit stays append-only and keeps its rows
-- (including PLAN_DELETED). Audit now references the owner instead of the deletable Plan.
ALTER TABLE shopping_audit DROP CONSTRAINT shopping_audit_plan_id_owner_id_fkey;
ALTER TABLE shopping_audit ADD CONSTRAINT shopping_audit_owner_fkey FOREIGN KEY (owner_id) REFERENCES app_users(id);
