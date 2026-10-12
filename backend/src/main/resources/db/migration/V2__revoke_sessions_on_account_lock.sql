-- Locking an account permanently revokes its existing sessions; unlocking does not revive them.
CREATE FUNCTION revoke_sessions_on_account_lock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.status = 'LOCKED' AND OLD.status <> 'LOCKED')
       OR (NEW.locked_until IS NOT NULL AND
           (OLD.locked_until IS NULL OR OLD.locked_until IS DISTINCT FROM NEW.locked_until)) THEN
        UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
        WHERE user_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER account_lock_revokes_sessions AFTER UPDATE ON app_users
FOR EACH ROW EXECUTE FUNCTION revoke_sessions_on_account_lock();
