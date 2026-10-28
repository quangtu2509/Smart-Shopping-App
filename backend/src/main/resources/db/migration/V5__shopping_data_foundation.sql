-- DATA-00: storage primitives only. No provider data or business HTTP routes are installed.
CREATE TABLE shopping_currencies (
    code TEXT PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'),
    minor_units INTEGER NOT NULL CHECK (minor_units BETWEEN 0 AND 6)
);
INSERT INTO shopping_currencies(code,minor_units) VALUES ('VND',0);

-- NUMERIC without a typmod avoids PostgreSQL silently rounding input before a CHECK.
CREATE FUNCTION shopping_valid_amount(value NUMERIC, currency_code TEXT) RETURNS BOOLEAN
LANGUAGE SQL STABLE AS $$
    SELECT value IS NULL OR EXISTS (
        SELECT 1 FROM shopping_currencies c WHERE c.code=currency_code
        AND value::TEXT ~ '^[0-9]+([.][0-9]+)?$' AND value=trunc(value,c.minor_units)
    )
$$;

CREATE TABLE shopping_retailers (
    id TEXT PRIMARY KEY, name TEXT NOT NULL CHECK (btrim(name)<>''), code TEXT,
    logo_url TEXT, status TEXT NOT NULL CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE TABLE shopping_stores (
    id TEXT PRIMARY KEY, retailer_id TEXT REFERENCES shopping_retailers(id),
    owner_id UUID REFERENCES app_users(id), visibility TEXT NOT NULL CHECK (visibility IN ('PUBLIC','PRIVATE')),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object' AND payload ?& ARRAY['id','version','name','location']),
    CHECK ((visibility='PRIVATE' AND owner_id IS NOT NULL) OR (visibility='PUBLIC' AND owner_id IS NULL)),
    CHECK (coalesce(payload->>'id'=id AND payload->>'visibility'=visibility AND (payload->>'retailer_id') IS NOT DISTINCT FROM retailer_id,FALSE))
);
CREATE INDEX shopping_stores_owner ON shopping_stores(owner_id) WHERE owner_id IS NOT NULL;
CREATE TABLE shopping_products (
    id TEXT PRIMARY KEY, category_id TEXT NOT NULL REFERENCES shopping_categories(id),
    brand_id TEXT REFERENCES shopping_brands(id),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload)='object' AND payload ?& ARRAY['id','name','unit','quantity_step']),
    CHECK (coalesce(payload->>'id'=id AND payload->>'category_id'=category_id AND (payload->>'brand_id') IS NOT DISTINCT FROM brand_id,FALSE)),
    CHECK (coalesce(payload->>'quantity_step' ~ '^[0-9]+([.][0-9]+)?$' AND (payload->>'quantity_step')::NUMERIC>0,FALSE))
);
CREATE TABLE shopping_store_products (
    id TEXT PRIMARY KEY, store_id TEXT NOT NULL REFERENCES shopping_stores(id),
    product_id TEXT REFERENCES shopping_products(id), custom_name TEXT,
    category_id TEXT NOT NULL REFERENCES shopping_categories(id), unit TEXT NOT NULL CHECK (btrim(unit)<>''),
    quantity_step NUMERIC NOT NULL CHECK (quantity_step>0 AND quantity_step::TEXT ~ '^[0-9]+([.][0-9]+)?$'),
    CHECK ((product_id IS NOT NULL AND custom_name IS NULL) OR (product_id IS NULL AND custom_name IS NOT NULL AND btrim(custom_name)<>'')),
    UNIQUE (store_id,id)
);
-- Composite identity distinguishes CATALOG and PRIVATE even when source IDs happen to be equal.
CREATE TABLE shopping_offers (
    kind TEXT NOT NULL CHECK (kind IN ('CATALOG','PRIVATE')), id TEXT NOT NULL,
    product_id TEXT REFERENCES shopping_products(id), retailer_id TEXT REFERENCES shopping_retailers(id),
    store_id TEXT REFERENCES shopping_stores(id), store_product_id TEXT,
    currency TEXT NOT NULL REFERENCES shopping_currencies(code), payload JSONB NOT NULL,
    PRIMARY KEY (kind,id), FOREIGN KEY (store_id,store_product_id) REFERENCES shopping_store_products(store_id,id),
    CHECK ((kind='CATALOG' AND product_id IS NOT NULL AND retailer_id IS NOT NULL AND store_product_id IS NULL)
        OR (kind='PRIVATE' AND store_id IS NOT NULL AND store_product_id IS NOT NULL AND retailer_id IS NULL)),
    CHECK (jsonb_typeof(payload)='object' AND payload ?& ARRAY['ref','version','currency','price','price_scope','freshness','stock_status']),
    CHECK (coalesce(payload->'ref'->>'kind'=kind AND payload->'ref'->>'id'=id AND payload->>'currency'=currency
        AND (payload->>'store_id') IS NOT DISTINCT FROM store_id AND (payload->>'retailer_id') IS NOT DISTINCT FROM retailer_id
        AND (payload->>'store_product_id') IS NOT DISTINCT FROM store_product_id,FALSE)),
    CHECK (payload->>'price' IS NULL OR (jsonb_typeof(payload->'price')='string' AND payload->>'price' ~ '^[0-9]+([.][0-9]+)?$'
        AND shopping_valid_amount((payload->>'price')::NUMERIC,currency))),
    CHECK (payload->>'list_price' IS NULL OR shopping_valid_amount((payload->>'list_price')::NUMERIC,currency)),
    CHECK (payload->>'unit_price' IS NULL OR shopping_valid_amount((payload->>'unit_price')::NUMERIC,currency)),
    CHECK (coalesce((kind='PRIVATE' AND payload->>'price_scope'='USER_ENTERED' AND payload->>'source'='USER')
        OR (kind='CATALOG' AND ((store_id IS NULL AND payload->>'price_scope'='RETAILER') OR (store_id IS NOT NULL AND payload->>'price_scope'='STORE'))),FALSE))
);

CREATE TABLE shopping_plans (
    id UUID PRIMARY KEY, owner_id UUID NOT NULL REFERENCES app_users(id),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version>0), revision INTEGER NOT NULL DEFAULT 0 CHECK (revision>=0),
    current_finalized_revision INTEGER, currency TEXT NOT NULL REFERENCES shopping_currencies(code),
    budget NUMERIC NOT NULL CHECK (budget>0), max_stores INTEGER NOT NULL CHECK (max_stores BETWEEN 1 AND 3),
    selected_mode TEXT NOT NULL CHECK (selected_mode IN ('CHEAPEST','CONVENIENT','BALANCED')),
    status TEXT NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','LOADING_PRODUCTS','BROWSING_PRODUCTS','BUILDING_LIST','COMPARING','OPTIMIZING','FINALIZED','IN_PROGRESS','COMPLETED','CANCELLED','EXPIRED')),
    catalog_status TEXT NOT NULL DEFAULT 'EMPTY' CHECK (catalog_status IN ('LOADING','READY','PARTIAL','EMPTY','FAILED')),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (id,owner_id), CHECK (shopping_valid_amount(budget,currency)),
    CHECK (current_finalized_revision IS NULL OR current_finalized_revision=revision),
    CHECK (status NOT IN ('FINALIZED','IN_PROGRESS','COMPLETED') OR current_finalized_revision IS NOT NULL)
);
CREATE INDEX shopping_plans_owner ON shopping_plans(owner_id,created_at,id);
CREATE TABLE shopping_plan_selections (
    plan_id UUID NOT NULL REFERENCES shopping_plans(id), position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 2),
    retailer_id TEXT REFERENCES shopping_retailers(id), store_id TEXT REFERENCES shopping_stores(id),
    PRIMARY KEY (plan_id,position), CHECK (retailer_id IS NOT NULL OR store_id IS NOT NULL)
);
CREATE UNIQUE INDEX shopping_selection_store ON shopping_plan_selections(plan_id,store_id) WHERE store_id IS NOT NULL;
CREATE UNIQUE INDEX shopping_selection_retailer ON shopping_plan_selections(plan_id,retailer_id) WHERE store_id IS NULL;
CREATE TABLE shopping_plan_categories (
    plan_id UUID NOT NULL REFERENCES shopping_plans(id), category_id TEXT NOT NULL REFERENCES shopping_categories(id),
    PRIMARY KEY (plan_id,category_id)
);
CREATE TABLE shopping_items (
    id UUID PRIMARY KEY, plan_id UUID NOT NULL REFERENCES shopping_plans(id),
    product_id TEXT, custom_name TEXT,
    offer_kind TEXT, offer_id TEXT, quantity NUMERIC NOT NULL, quantity_step NUMERIC NOT NULL,
    unit TEXT NOT NULL CHECK (btrim(unit)<>''), priority TEXT NOT NULL CHECK (priority IN ('ESSENTIAL','RECOMMENDED','OPTIONAL')),
    product_snapshot JSONB, store_snapshot JSONB, selected_price_snapshot JSONB,
    -- Historical identities are validated on selection, not FKs to mutable/deletable catalog rows.
    UNIQUE (plan_id,id),
    CHECK ((product_id IS NOT NULL AND custom_name IS NULL) OR (product_id IS NULL AND custom_name IS NOT NULL AND btrim(custom_name)<>'')),
    CHECK ((offer_kind IS NULL)=(offer_id IS NULL)), CHECK ((offer_kind IS NULL)=(selected_price_snapshot IS NULL)),
    CHECK (quantity>0 AND quantity::TEXT ~ '^[0-9]+([.][0-9]+)?$'),
    CHECK (quantity_step>0 AND quantity_step::TEXT ~ '^[0-9]+([.][0-9]+)?$' AND mod(quantity,quantity_step)=0)
);
CREATE INDEX shopping_items_plan ON shopping_items(plan_id,id);
CREATE TABLE shopping_plan_revisions (
    plan_id UUID NOT NULL REFERENCES shopping_plans(id), revision INTEGER NOT NULL CHECK (revision>0),
    finalized_at TIMESTAMPTZ NOT NULL, payload JSONB NOT NULL,
    PRIMARY KEY (plan_id,revision),
    CHECK (jsonb_typeof(payload)='object' AND payload ?& ARRAY['plan','items','products','stores','allocations','finalized_at']),
    CHECK (coalesce(payload->'plan'->>'id'=plan_id::TEXT AND (payload->'plan'->>'revision')::INTEGER=revision,FALSE))
);
ALTER TABLE shopping_plans ADD CONSTRAINT shopping_current_revision
    FOREIGN KEY (id,current_finalized_revision) REFERENCES shopping_plan_revisions(plan_id,revision) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE shopping_revision_validity (
    plan_id UUID NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL CHECK (status IN ('CURRENT','INVALIDATED')),
    PRIMARY KEY(plan_id,revision), FOREIGN KEY(plan_id,revision) REFERENCES shopping_plan_revisions(plan_id,revision)
);
CREATE TABLE shopping_artifacts (
    id UUID PRIMARY KEY, plan_id UUID NOT NULL REFERENCES shopping_plans(id), input_version INTEGER NOT NULL CHECK (input_version>0),
    kind TEXT NOT NULL CHECK (kind IN ('SUGGESTION','OPTIMIZATION','ROUTE')),
    status TEXT NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','READY','ACTIVE','COMPLETED','INVALIDATED'))
);
CREATE INDEX shopping_artifacts_plan ON shopping_artifacts(plan_id,input_version);
CREATE TABLE shopping_mutations (
    owner_id UUID NOT NULL REFERENCES app_users(id), method TEXT NOT NULL, path TEXT NOT NULL, request_key TEXT NOT NULL,
    request_hash CHAR(64) NOT NULL, status INTEGER NOT NULL CHECK (status BETWEEN 200 AND 299), data JSONB, location TEXT,
    created_at TIMESTAMPTZ NOT NULL, PRIMARY KEY(owner_id,method,path,request_key)
);
CREATE TABLE shopping_audit (
    id UUID PRIMARY KEY, plan_id UUID NOT NULL, owner_id UUID NOT NULL,
    action TEXT NOT NULL, version INTEGER NOT NULL CHECK (version>0), occurred_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(plan_id,owner_id) REFERENCES shopping_plans(id,owner_id)
);

-- Session/History are storage contracts for later services, not implementations of checklist/complete endpoints.
CREATE TABLE shopping_sessions (
    id UUID PRIMARY KEY, plan_id UUID NOT NULL, plan_revision INTEGER NOT NULL, owner_id UUID NOT NULL,
    version INTEGER NOT NULL CHECK (version>0), status TEXT NOT NULL CHECK (status IN ('IN_PROGRESS','COMPLETED','CANCELLED')),
    currency TEXT NOT NULL REFERENCES shopping_currencies(code), budget NUMERIC NOT NULL CHECK (budget>0),
    initial_items JSONB NOT NULL CHECK (jsonb_typeof(initial_items)='array'),
    FOREIGN KEY(plan_id,owner_id) REFERENCES shopping_plans(id,owner_id),
    FOREIGN KEY(plan_id,plan_revision) REFERENCES shopping_plan_revisions(plan_id,revision),
    UNIQUE(id,owner_id), CHECK(shopping_valid_amount(budget,currency))
);
CREATE UNIQUE INDEX shopping_active_session ON shopping_sessions(plan_id) WHERE status='IN_PROGRESS';
CREATE TABLE shopping_session_items (
    id UUID PRIMARY KEY, session_id UUID NOT NULL REFERENCES shopping_sessions(id),
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot)='object'),
    status TEXT NOT NULL CHECK (status IN ('PENDING','PURCHASED','REMOVED_BY_USER','REPLACED','SKIPPED_AT_COMPLETION')),
    origin TEXT NOT NULL CHECK (origin IN ('INITIAL_PLAN','ADDED_DURING_SHOPPING','REPLACEMENT')), replaces_session_item_id UUID,
    UNIQUE(session_id,id), FOREIGN KEY(session_id,replaces_session_item_id) REFERENCES shopping_session_items(session_id,id),
    CHECK ((origin='REPLACEMENT')=(replaces_session_item_id IS NOT NULL)), CHECK (id IS DISTINCT FROM replaces_session_item_id)
);
CREATE TABLE shopping_history (
    id UUID PRIMARY KEY, session_id UUID NOT NULL UNIQUE, owner_id UUID NOT NULL, payload JSONB NOT NULL,
    FOREIGN KEY(session_id,owner_id) REFERENCES shopping_sessions(id,owner_id),
    CHECK (jsonb_typeof(payload)='object' AND payload ?& ARRAY['initial_items','final_items','final_purchased_total','currency']),
    CHECK (coalesce(jsonb_typeof(payload->'final_purchased_total')='string'
        AND payload->>'final_purchased_total' ~ '^[0-9]+([.][0-9]+)?$'
        AND shopping_valid_amount((payload->>'final_purchased_total')::NUMERIC,payload->>'currency'),FALSE))
);
CREATE FUNCTION shopping_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Shopping snapshots are immutable' USING ERRCODE='55000'; END $$;
CREATE TRIGGER shopping_revision_immutable BEFORE UPDATE OR DELETE ON shopping_plan_revisions FOR EACH ROW EXECUTE FUNCTION shopping_immutable();
CREATE TRIGGER shopping_history_immutable BEFORE UPDATE OR DELETE ON shopping_history FOR EACH ROW EXECUTE FUNCTION shopping_immutable();
CREATE TRIGGER shopping_audit_immutable BEFORE UPDATE OR DELETE ON shopping_audit FOR EACH ROW EXECUTE FUNCTION shopping_immutable();
CREATE FUNCTION shopping_session_snapshot_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.plan_id,NEW.plan_revision,NEW.owner_id,NEW.currency,NEW.budget,NEW.initial_items) IS DISTINCT FROM
       (OLD.plan_id,OLD.plan_revision,OLD.owner_id,OLD.currency,OLD.budget,OLD.initial_items) THEN
        RAISE EXCEPTION 'Initial session snapshot is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER shopping_session_snapshot_immutable BEFORE UPDATE ON shopping_sessions FOR EACH ROW EXECUTE FUNCTION shopping_session_snapshot_immutable();
CREATE FUNCTION shopping_session_item_immutable() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.session_id,NEW.snapshot,NEW.origin,NEW.replaces_session_item_id) IS DISTINCT FROM
       (OLD.id,OLD.session_id,OLD.snapshot,OLD.origin,OLD.replaces_session_item_id) THEN
        RAISE EXCEPTION 'Session item description is immutable' USING ERRCODE='55000';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER shopping_session_item_immutable BEFORE UPDATE ON shopping_session_items FOR EACH ROW EXECUTE FUNCTION shopping_session_item_immutable();
CREATE TRIGGER shopping_session_item_no_delete BEFORE DELETE ON shopping_session_items FOR EACH ROW EXECUTE FUNCTION shopping_immutable();
CREATE FUNCTION shopping_completed_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE session_key UUID; session_status TEXT; has_history BOOLEAN;
BEGIN
    IF TG_TABLE_NAME='shopping_history' THEN session_key=NEW.session_id; ELSE session_key=NEW.id; END IF;
    SELECT status INTO session_status FROM shopping_sessions WHERE id=session_key;
    SELECT EXISTS(SELECT 1 FROM shopping_history WHERE session_id=session_key) INTO has_history;
    IF (session_status='COMPLETED') IS DISTINCT FROM has_history THEN
        RAISE EXCEPTION 'Completed session and unique history must commit together' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER shopping_completed_history AFTER INSERT OR UPDATE ON shopping_sessions
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shopping_completed_history();
CREATE CONSTRAINT TRIGGER shopping_history_completed AFTER INSERT ON shopping_history
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION shopping_completed_history();
