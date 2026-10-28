package com.toked.smartshopping.backend

/** Test-only reset. Includes every owner FK, and refuses any development database even if called accidentally. */
internal fun resetAuthTestData(db: Database) = db.transaction { c ->
    query(c, "SELECT current_database(),inet_server_port()").use { s -> s.executeQuery().use { r ->
        check(r.next() && r.getString(1) == "auth00_test" && r.getInt(2) == 55432) { "Refusing reset outside isolated PostgreSQL" }
    } }
    execute(c, """TRUNCATE catalog_refresh_jobs,catalog_entries,catalog_sources,shopping_history,shopping_session_items,shopping_sessions,shopping_audit,shopping_mutations,
        shopping_artifacts,shopping_revision_validity,shopping_plan_revisions,shopping_items,shopping_plan_categories,
        shopping_plan_selections,shopping_plans,shopping_offers,shopping_store_products,shopping_products,shopping_stores,shopping_retailers,
        profile_requests,auth_challenges,auth_identities,auth_rate_limits,refresh_tokens,auth_sessions,profiles,app_users""")
}
